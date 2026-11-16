package wtf.lennard.thorbro

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates ThorBro from an update feed. The feed is either
 *  - a GitHub/Forgejo "latest release" API URL (tag_name + an .apk asset), or
 *  - a plain JSON file: {"versionName": "0.4.0", "url": "https://.../thorbro.apk"}.
 * With auto-update on, the server checks every 6 hours and installs newer versions by itself.
 */
object Updater {
    const val DEFAULT_FEED = "https://api.github.com/repos/Lennardsmw/thorbro/releases"
    private const val EVERY = 6L * 3600 * 1000

    fun feed(ctx: Context): String = Prefs.of(ctx).getString("updateFeed", null) ?: DEFAULT_FEED
    fun auto(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("autoUpdate", true)

    fun configure(ctx: Context, feed: String?, auto: Boolean?) {
        val e = Prefs.of(ctx).edit()
        if (feed != null) e.putString("updateFeed", feed.trim().ifEmpty { DEFAULT_FEED })
        if (auto != null) e.putBoolean("autoUpdate", auto)
        e.apply()
    }

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** {current, latest, url, available, feed, auto} or {error} */
    fun check(ctx: Context): JSONObject {
        val out = JSONObject().put("current", current(ctx)).put("feed", feed(ctx)).put("auto", auto(ctx))
        val body = try { get(feed(ctx)) } catch (e: Exception) {
            // GitHub's API allows only 60 anonymous requests per hour and connection; the release feed has no such limit
            return fromAtom(ctx, out) ?: out.put("error", "Update-Quelle nicht erreichbar: ${e.message}")
        }
        // a release list (GitHub /releases): take the newest app release ("v1.2.3" with an .apk), skip desktop ones
        val j = try {
            if (body.trimStart().startsWith("[")) {
                val arr = org.json.JSONArray(body)
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { r -> Regex("^v\\d").containsMatchIn(r.optString("tag_name")) && !r.optBoolean("draft") &&
                        r.optJSONArray("assets")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("name").endsWith(".apk") } } == true }
                    .maxWithOrNull { a, b -> if (newer(a.getString("tag_name").removePrefix("v"), b.getString("tag_name").removePrefix("v"))) 1 else -1 }
                    ?: return out.put("error", "Kein App-Release gefunden")
            } else JSONObject(body)
        } catch (_: Exception) { return out.put("error", "Update-Quelle liefert kein JSON") }
        val latest: String
        val url: String?
        if (j.has("tag_name")) {
            latest = j.getString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets")
            url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }?.optString("browser_download_url")
        } else {
            latest = j.optString("versionName")
            url = j.optString("url").ifEmpty { null }
        }
        if (latest.isEmpty() || url == null) return out.put("error", "Kein APK im neuesten Release gefunden")
        return out.put("latest", latest).put("url", url).put("available", newer(latest, current(ctx)))
    }

    private fun fromAtom(ctx: Context, out: JSONObject): JSONObject? = runCatching {
        if (!feed(ctx).contains("api.github.com/repos/Lennardsmw/thorbro")) return null
        val atom = get("https://github.com/Lennardsmw/thorbro/releases.atom")
        val latest = Regex("/releases/tag/v(\\d+\\.\\d+\\.\\d+)").findAll(atom).map { it.groupValues[1] }
            .maxWithOrNull { a, b -> if (newer(a, b)) 1 else -1 } ?: return null
        out.put("latest", latest).put("url", "https://github.com/Lennardsmw/thorbro/releases/download/v$latest/ThorBro-$latest.apk")
            .put("available", newer(latest, current(ctx))).put("via", "atom")
    }.getOrNull()

    /** Downloads and installs the newest version. Returns the check result plus "session" when an install started. */
    fun update(ctx: Context): JSONObject {
        val c = check(ctx)
        if (c.has("error") || !c.optBoolean("available")) return c
        val apk = File(ctx.cacheDir, "update.apk")
        try { download(c.getString("url"), apk) } catch (e: Exception) { return c.put("error", "Download fehlgeschlagen: ${e.message}") }
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != ctx.packageName) { apk.delete(); return c.put("error", "Heruntergeladene Datei ist keine ThorBro-APK") }
        Logs.i("update", "installiere ${c.optString("latest")} (von ${c.optString("current")})")
        val id = Installer.install(ctx, apk)
        return c.put("session", id).put("status", Installer.status[id])
    }

    // ---- periodic check while the server runs ----
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null

    fun startSchedule(ctx: Context) {
        stopSchedule()
        val r = object : Runnable {
            override fun run() {
                if (auto(ctx)) thread(name = "thorbro-update") { runCatching { update(ctx) } }
                if (Saves.auto(ctx)) thread(name = "thorbro-saves") {
                    runCatching { Saves.backup(ctx, null) }
                    if (RommSync.auto(ctx) && Romm.status(ctx).optBoolean("connected")) runCatching { RommSync.syncSaves(ctx, null) }
                }
                handler.postDelayed(this, EVERY)
            }
        }
        task = r
        handler.postDelayed(r, 60_000) // first check one minute after start
    }

    fun stopSchedule() { task?.let { handler.removeCallbacks(it) }; task = null }

    // ---- helpers ----
    fun newer(a: String, b: String): Boolean {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ThorBro-Updater")
        setRequestProperty("Accept", "application/json, application/octet-stream")
    }

    private fun get(url: String): String = open(url).run {
        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, target: File) = open(url).run {
        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
        inputStream.use { i -> target.outputStream().use { i.copyTo(it, 1 shl 20) } }
    }
}
