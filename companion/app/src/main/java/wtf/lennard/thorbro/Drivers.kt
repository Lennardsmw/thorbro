package wtf.lennard.thorbro

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * GPU drivers for emulators (adrenotools packages: Turnip and Qualcomm builds).
 * ThorBro lists the releases of the known driver repos, downloads them to ThorBro/GPU-Treiber,
 * and the emulator imports them from there (Android 13 keeps other apps out of Android/data,
 * so the import itself happens in the emulator's driver manager).
 */
object Drivers {
    class Source(val id: String, val name: String, val repo: String, val note: String)

    val sources = listOf(
        Source("k11", "K11MCH1 AdrenoToolsDrivers", "K11MCH1/AdrenoToolsDrivers", "Turnip-Builds und originale Qualcomm-Treiber"),
        Source("purple", "MrPurple Turnip", "MrPurple666/purple-turnip", "Turnip mit Fixes für Switch-Emulatoren"),
        Source("mainline", "Mainline Turnip (whitebelyash)", "whitebelyash/freedreno_turnip-CI", "Turnip direkt aus Mesa"),
    )

    /** emulators that load custom drivers, with where the import lives in their menus */
    private val targets = listOf(
        Triple("eden", "dev.eden.eden_emulator", "Einstellungen → GPU-Treiber verwalten → Installieren"),
        Triple("azahar", "org.azahar_emu.azahar", "Einstellungen → Grafik → GPU-Treiber → Installieren"),
        Triple("dolphin", "org.dolphinemu.dolphinemu", "Einstellungen → Grafik → GPU-Treiber → Installieren"),
    )

    fun dir(): File = File(Storage.primary, "ThorBro/GPU-Treiber")

    private const val CACHE_MS = 3600_000L
    @Volatile private var cache: Pair<Long, JSONArray>? = null

    /** {soc, gpu, sources:[{id,name,note,releases:[{tag,name,date,assets:[{name,url,size}]}],error?}], downloaded, emulators} */
    fun overview(ctx: Context, refresh: Boolean): JSONObject {
        val c = cache
        val src = if (!refresh && c != null && System.currentTimeMillis() - c.first < CACHE_MS) c.second else
            JSONArray(sources.map { fetch(it) }).also { cache = System.currentTimeMillis() to it }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return JSONObject()
            .put("soc", soc)
            .put("gpu", gpus[soc.uppercase().replace(Regex("^QC[SM]"), "SM")])
            .put("sources", src)
            .put("downloaded", downloaded())
            .put("dir", dir().path)
            .put("emulators", JSONArray(targets.mapNotNull { (id, pkg, where) ->
                val e = Emulators.all.firstOrNull { it.id == id } ?: return@mapNotNull null
                JSONObject().put("id", id).put("name", e.name).put("pkg", pkg).put("where", where)
                    .put("installed", runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess)
            }))
    }

    private val gpus = mapOf("SM8550" to "Adreno 740", "SM8650" to "Adreno 750", "SM8750" to "Adreno 830",
        "SM8250" to "Adreno 650", "SM8475" to "Adreno 730", "SM8450" to "Adreno 730", "SM7675" to "Adreno 735")

    private fun fetch(s: Source): JSONObject {
        val out = JSONObject().put("id", s.id).put("name", s.name).put("note", s.note).put("repo", s.repo)
        return try {
            val arr = JSONArray(get("https://api.github.com/repos/${s.repo}/releases?per_page=8"))
            out.put("releases", JSONArray((0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { !it.optBoolean("draft") }
                .map { r ->
                    val a = r.optJSONArray("assets") ?: JSONArray()
                    JSONObject().put("tag", r.optString("tag_name")).put("name", r.optString("name").ifEmpty { r.optString("tag_name") })
                        .put("date", r.optString("published_at").take(10))
                        .put("assets", JSONArray((0 until a.length()).map { a.getJSONObject(it) }
                            .filter { it.optString("name").endsWith(".zip", true) || it.optString("name").endsWith(".adpkg", true) }
                            .map { JSONObject().put("name", it.getString("name")).put("url", it.getString("browser_download_url")).put("size", it.optLong("size")) }))
                }.filter { it.getJSONArray("assets").length() > 0 }))
        } catch (e: Exception) {
            out.put("releases", JSONArray()).put("error", e.message)
        }
    }

    fun downloaded(): JSONArray = JSONArray((dir().listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.endsWith(".part") }
        .sortedByDescending { it.lastModified() }
        .map { f -> JSONObject().put("name", f.name).put("path", f.path).put("size", f.length()).put("driver", meta(f)) })

    /** name/version from an adrenotools package's meta.json, null when the zip has none */
    private fun meta(f: File): JSONObject? = runCatching {
        ZipFile(f).use { z ->
            val e = z.getEntry("meta.json") ?: return null
            val m = JSONObject(z.getInputStream(e).bufferedReader().readText())
            JSONObject().put("name", m.optString("name")).put("version", m.optString("driverVersion"))
                .put("vendor", m.optString("vendor")).put("description", m.optString("description"))
        }
    }.getOrNull()

    /** only release assets of the known repos may be downloaded */
    fun download(url: String, job: Jobs.Job): JSONObject {
        val ok = sources.any { url.startsWith("https://github.com/${it.repo}/releases/download/") }
        if (!ok) throw IllegalArgumentException("Unbekannte Treiber-Quelle")
        val name = url.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        dir().mkdirs()
        val target = File(dir(), name)
        val part = File(dir(), "$name.part")
        val c = open(url)
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        job.message = name
        c.inputStream.use { i -> part.outputStream().use { o ->
            val buf = ByteArray(1 shl 16); var done = 0L
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                if (total > 0) job.progress = done.toDouble() / total
            }
        } }
        target.delete(); part.renameTo(target)
        Logs.i("drivers", "geladen: $name")
        return JSONObject().put("name", name).put("path", target.path).put("driver", meta(target))
            .put("warning", if (meta(target) == null) "Keine meta.json im Archiv – vermutlich kein adrenotools-Treiber" else null)
    }

    fun delete(name: String): Boolean {
        if (!Regex("^[A-Za-z0-9._-]+$").matches(name)) return false
        return File(dir(), name).delete()
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ThorBro")
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
    }

    private fun get(url: String): String = open(url).run {
        if (responseCode == 403 || responseCode == 429) throw IllegalStateException("GitHub-Limit erreicht, später noch mal")
        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    }
}
