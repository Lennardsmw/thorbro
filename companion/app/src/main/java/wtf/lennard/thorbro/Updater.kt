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

    // ---- Dev dialog: list test builds from a chosen source and install one (protocol: Ordnung doc "Dev-Versionen") ----
    const val HOME_INDEX = "http://192.168.178.97:8097/thorbro/index.json"
    val DEV_SOURCES = listOf("github" to "GitHub", "home" to "Heimnetz", "custom" to "Eigene URL")
    fun devSource(ctx: Context): String = Prefs.of(ctx).getString("devSource", null)?.takeIf { s -> DEV_SOURCES.any { it.first == s } } ?: "github"
    fun devCustom(ctx: Context): String = Prefs.of(ctx).getString("devCustom", "") ?: ""
    fun setDevSource(ctx: Context, source: String?, custom: String?) {
        val e = Prefs.of(ctx).edit()
        if (source != null && DEV_SOURCES.any { it.first == source }) e.putString("devSource", source)
        if (custom != null) e.putString("devCustom", custom.trim())
        e.apply()
    }

    /** where the list for [source] comes from */
    fun devIndexUrl(ctx: Context, source: String = devSource(ctx)): String = when (source) {
        "home" -> HOME_INDEX
        "custom" -> devCustom(ctx).let { if (it.isEmpty() || it.endsWith(".json")) it else it.trimEnd('/') + "/index.json" }
        else -> DEFAULT_FEED
    }

    /**
     * {source, custom, index, current, currentCode, versions:[{versionName, versionCode, branch, commit, published, features,
     *  url, size, current, older}]} (newest first) or with "error". "older" builds cannot be installed over this one (Android).
     */
    fun devVersions(ctx: Context): JSONObject {
        val source = devSource(ctx)
        val index = devIndexUrl(ctx, source)
        val code = currentCode(ctx)
        val cur = current(ctx)
        val out = JSONObject().put("source", source).put("custom", devCustom(ctx)).put("index", index)
            .put("current", cur).put("currentCode", code)
        if (index.isEmpty()) return out.put("versions", org.json.JSONArray()).put("error", "Keine URL angegeben")
        val list = try {
            if (source == "github") githubDevs() else indexDevs(get(index))
        } catch (e: Exception) {
            if (source == "github") runCatching { atomDevs() }.getOrNull()
                ?: return out.put("versions", org.json.JSONArray()).put("error", "GitHub nicht erreichbar: ${e.message}")
            else return out.put("versions", org.json.JSONArray()).put("error", "Quelle nicht erreichbar: ${e.message}")
        }
        val arr = org.json.JSONArray()
        list.sortedByDescending { it.optLong("versionCode") }.forEach { v ->
            val vc = v.optLong("versionCode")
            arr.put(v.put("current", vc == code || v.optString("versionName") == cur).put("older", vc in 1 until code))
        }
        return out.put("versions", arr)
    }

    private fun indexDevs(body: String): List<JSONObject> {
        val a = JSONObject(body).optJSONArray("versions") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").isNotEmpty() }
    }

    private val DEV_LINE = Regex("<!--\\s*dev:\\s*(\\{.*?\\})\\s*-->", RegexOption.DOT_MATCHES_ALL)

    private fun githubDevs(): List<JSONObject> {
        val arr = org.json.JSONArray(get("$DEFAULT_FEED?per_page=50"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && Regex("^dev-\\d+$").matches(it.optString("tag_name")) }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) } ?: return@mapNotNull null
                val notes = r.optString("body")
                val meta = DEV_LINE.find(notes)?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
                val v = meta ?: JSONObject()
                    .put("versionName", r.optString("name").removePrefix("ThorBro ").ifEmpty { r.optString("tag_name") })
                    .put("features", org.json.JSONArray(notes.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }))
                if (!v.has("versionCode")) v.put("versionCode", r.getString("tag_name").removePrefix("dev-").toLong())
                if (!v.has("branch")) v.put("branch", Regex("-([^+]+)\\+").find(v.optString("versionName"))?.groupValues?.get(1) ?: "")
                if (!v.has("published")) v.put("published", r.optString("published_at"))
                v.put("url", apk.optString("browser_download_url")).put("size", apk.optLong("size"))
            }
    }

    /** GitHub's API allows 60 anonymous requests an hour; the Atom feed has no limit but only names and dates */
    private fun atomDevs(): List<JSONObject> {
        val atom = get("https://github.com/Lennardsmw/thorbro/releases.atom")
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(atom).mapNotNull { m ->
            val e = m.groupValues[1]
            val code = Regex("/releases/tag/dev-(\\d+)").find(e)?.groupValues?.get(1)?.toLong() ?: return@mapNotNull null
            val title = Regex("<title>(.*?)</title>").find(e)?.groupValues?.get(1)?.removePrefix("ThorBro ") ?: "dev-$code"
            JSONObject().put("versionName", title).put("versionCode", code)
                .put("branch", Regex("-([^+]+)\\+").find(title)?.groupValues?.get(1) ?: "")
                .put("published", Regex("<updated>(.*?)</updated>").find(e)?.groupValues?.get(1) ?: "")
                .put("features", org.json.JSONArray())
                .put("url", "https://github.com/Lennardsmw/thorbro/releases/download/dev-$code/ThorBro-dev.apk")
        }.toList()
    }

    /** downloads [url] (a ThorBro APK from the Dev list) and installs it; {session, status, latest} or {error} */
    fun installDev(ctx: Context, url: String): JSONObject {
        val out = JSONObject().put("current", current(ctx)).put("url", url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return out.put("error", "Ungültige URL")
        val apk = File(ctx.cacheDir, "update.apk")
        try { download(url, apk) } catch (e: Exception) { return out.put("error", "Download fehlgeschlagen: ${e.message}") }
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != ctx.packageName) { apk.delete(); return out.put("error", "Heruntergeladene Datei ist keine ThorBro-APK") }
        if (info.longVersionCode < currentCode(ctx)) { apk.delete(); return out.put("error", "Älter als die installierte Version – nur nach Deinstallation") }
        out.put("latest", info.versionName ?: "")
        Logs.i("update", "Dev-Version ${info.versionName} installieren (von ${current(ctx)})")
        val id = Installer.install(ctx, apk)
        return out.put("session", id).put("status", Installer.status[id])
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
