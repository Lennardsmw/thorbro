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
 *  - a plain JSON file: {"versionName": "0.4.0", "url": "https://.../thorbro.apk"} (with "versionCode": compared by code,
 *    so builds of a branch, "0.23.3-lanplay+abc1234", update each other).
 * Release builds use GitHub; test builds (version with "+commit") the dev channel in the home network, filled by
 * scripts/publish-dev.sh — what is being tested is not released.
 * With auto-update on, the server checks every 6 hours and installs newer versions by itself.
 */
object Updater {
    const val DEFAULT_FEED = "https://api.github.com/repos/Lennardsmw/thorbro/releases"
    /** the old home-network dev channel; ignored now, test builds come from GitHub pre-releases too */
    private const val OLD_DEV_FEED = "http://192.168.178.97:8097/feed.json"
    private const val EVERY = 6L * 3600 * 1000

    /**
     * Test builds ("<version>-<branch>+<commit>") update from GitHub pre-releases tagged "dev-<versionCode>"
     * (scripts/publish-dev.sh), so testers anywhere get them; normal builds only take "v1.2.3" releases.
     */
    fun dev(ctx: Context) = current(ctx).contains('+')
    fun defaultFeed(@Suppress("UNUSED_PARAMETER") ctx: Context) = DEFAULT_FEED
    fun feed(ctx: Context): String = Prefs.of(ctx).getString("updateFeed", null)
        ?.takeUnless { it == OLD_DEV_FEED } ?: defaultFeed(ctx)
    fun auto(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("autoUpdate", true)

    fun configure(ctx: Context, feed: String?, auto: Boolean?) {
        val e = Prefs.of(ctx).edit()
        if (feed != null) e.putString("updateFeed", feed.trim().ifEmpty { defaultFeed(ctx) })
        if (auto != null) e.putBoolean("autoUpdate", auto)
        e.apply()
    }

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    fun currentCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** {current, latest, url, available, feed, auto} or {error} */
    fun check(ctx: Context): JSONObject {
        val out = JSONObject().put("current", current(ctx)).put("feed", feed(ctx)).put("auto", auto(ctx))
        val body = try { get(feed(ctx)) } catch (e: Exception) {
            // GitHub's API allows only 60 anonymous requests per hour and connection; the release feed has no such limit
            return fromAtom(ctx, out) ?: out.put("error", "Update-Quelle nicht erreichbar: ${e.message}")
        }
        // a release list (GitHub /releases): take the newest app release ("v1.2.3" with an .apk), skip desktop ones;
        // a test build takes the newest test pre-release ("dev-<versionCode>") instead
        if (dev(ctx) && body.trimStart().startsWith("[")) return devRelease(ctx, org.json.JSONArray(body), out)
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
        val available = if (j.has("versionCode")) j.optLong("versionCode") > currentCode(ctx) && latest != current(ctx)
            else newer(latest, current(ctx))
        return out.put("latest", latest).put("url", url).put("available", available)
    }

    private fun devRelease(ctx: Context, arr: org.json.JSONArray, out: JSONObject): JSONObject {
        val r = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && Regex("^dev-\\d+$").matches(it.optString("tag_name")) }
            .maxByOrNull { it.optString("tag_name").removePrefix("dev-").toLong() } ?: return out.put("error", "Noch kein Test-Build auf GitHub")
        val code = r.getString("tag_name").removePrefix("dev-").toLong()
        val assets = r.optJSONArray("assets")
        val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", true) }?.optString("browser_download_url") ?: return out.put("error", "Test-Build ohne APK")
        return out.put("latest", r.optString("name").removePrefix("ThorBro ").ifEmpty { "dev-$code" }).put("url", url)
            .put("available", code > currentCode(ctx))
    }

    private fun fromAtom(ctx: Context, out: JSONObject): JSONObject? = runCatching {
        if (!feed(ctx).contains("api.github.com/repos/Lennardsmw/thorbro")) return null
        if (dev(ctx)) { // test builds: newest "dev-<versionCode>" tag, its APK always has the same name
            val atom = get("https://github.com/Lennardsmw/thorbro/releases.atom")
            val code = Regex("/releases/tag/dev-(\\d+)").findAll(atom).map { it.groupValues[1].toLong() }.maxOrNull() ?: return null
            return out.put("latest", "dev-$code").put("url", "https://github.com/Lennardsmw/thorbro/releases/download/dev-$code/ThorBro-dev.apk")
                .put("available", code > currentCode(ctx)).put("via", "atom")
        }
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
                    runCatching { GameSave.backupChanged(ctx) }
                    if (DataSaver.autoSync(ctx) && DataSaver.netOk(ctx) && Romm.status(ctx).optBoolean("connected")) runCatching { RommSync.syncSaves(ctx, null) }
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
