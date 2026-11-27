package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * "Upload per Link": the Thor loads a file from an http(s) link itself, into the ROM folder of its console
 * (guessed from the file extension), into Download/ThorBro for an APK (then installed), or into a folder given.
 * Runs in the [TransferQueue] like a store download: resumes via .thorbro-part + Range, cancel deletes the part.
 */
object UrlDownload {
    const val APK_DIR = "/storage/emulated/0/Download/ThorBro"
    private val KEEP_ZIPPED = setOf("nes", "snes", "gb", "gbc", "gba", "megadrive", "mastersystem", "gamegear", "pcengine", "atari2600", "lynx", "ngp", "wonderswan", "arcade", "n64")

    fun validUrl(url: String) = runCatching { URL(url).protocol.lowercase() in setOf("http", "https") }.getOrDefault(false)

    /** a short name for the list before anything is loaded: the last part of the link's path */
    fun nameOf(url: String): String = runCatching {
        URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8")
    }.getOrDefault("").let(::clean).ifEmpty { runCatching { URL(url).host }.getOrDefault("Download") }

    private fun clean(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(200)

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    fun systemFor(name: String): String {
        val e = ext(name)
        if (e == "apk") return "apk"
        return Roms.systems.firstOrNull { e in it.exts }?.id ?: ""
    }

    /** follows redirects (also http ↔ https, which HttpURLConnection won't do on its own) */
    private fun open(url: String, from: Long): HttpURLConnection {
        var u = URL(url)
        repeat(8) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "ThorBro")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            if (c.responseCode in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Weiterleitung ohne Ziel")
                c.disconnect(); u = URL(u, loc)
                if (u.protocol.lowercase() !in setOf("http", "https")) error("Weiterleitung auf ${u.protocol}: nicht erlaubt")
            } else return c
        }
        error("Zu viele Weiterleitungen")
    }

    /** the file name the server gives (Content-Disposition), else the one from the final link */
    private fun fileName(c: HttpURLConnection, fallback: String): String {
        val cd = c.getHeaderField("Content-Disposition") ?: ""
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        val fromUrl = runCatching { URLDecoder.decode(c.url.path.substringAfterLast('/'), "UTF-8") }.getOrNull()
        return listOfNotNull(star, plain, fromUrl, fallback).map(::clean).firstOrNull { it.isNotEmpty() } ?: "download"
    }

    /**
     * Loads [e] (kind "url": url, system = "" auto / console id / "apk" / "-" = ROM folder itself, dir = optional folder,
     * extract = null auto / true / false). Updates the queue entry's name once the real file name is known.
     */
    fun start(ctx: Context, e: JSONObject, rename: (String) -> Unit): Jobs.Job {
        val app = ctx.applicationContext
        return Jobs.start("Link-Download") { job ->
            val t0 = System.currentTimeMillis()
            val url = e.getString("url")
            val known = e.optString("file")
            // a resumed item knows its file already: continue its .thorbro-part
            val guessDir = { name: String -> targetDir(app, e, name) }
            var target = known.takeIf { it.isNotEmpty() }?.let(::File)
            val part0 = target?.let { File(it.path + ".thorbro-part") }
            val c = open(url, part0?.takeIf { it.exists() }?.length() ?: 0L)
            if (c.responseCode !in listOf(200, 206)) { c.disconnect(); error("Server antwortet mit HTTP ${c.responseCode}") }
            if (target == null) {
                val name = fileName(c, nameOf(url))
                val dir = guessDir(name).apply { mkdirs() }
                if (!dir.isDirectory) error("Ordner ${dir.path} geht nicht")
                target = File(dir, name)
                e.put("file", target.path); rename(name)
            }
            val part = File(target.path + ".thorbro-part")
            val append = c.responseCode == 206 && part.exists()
            val len = c.contentLengthLong
            val total = if (len > 0) len + (if (append) part.length() else 0L) else 0L
            if (total > 0 && Storage.free(target.parentFile!!) in 0 until (total - (if (append) part.length() else 0L))) {
                c.disconnect(); error("Nicht genug Platz (${total / 1_000_000} MB nötig)")
            }
            job.message = target.name
            job.game(target.name, e.optString("system"))
            Transfers.begin(app)
            try {
                c.inputStream.use { inp ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = if (append) part.length() else 0L
                        while (true) {
                            job.check()
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) { job.progress = done.toDouble() / total; job.bytes(done, total); job.gameBytes(done, total) }
                            else job.message = "${target.name} · ${done / 1_000_000} MB"
                        }
                        if (total > 0 && done < total) error("Verbindung abgebrochen bei ${done * 100 / total} %")
                    }
                }
            } catch (s: Jobs.Stopped) {
                if (s.cancel) part.delete()
                throw s
            } finally { Transfers.end(); runCatching { c.disconnect() } }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("Umbenennen ging nicht")
            var path = target.path
            val sys = systemFor(target.name).takeIf { e.optString("system").isEmpty() } ?: e.optString("system")
            val extract = if (e.has("extract") && !e.isNull("extract")) e.optBoolean("extract") else Archives.isArchive(target) && sys !in KEEP_ZIPPED && sys != "apk"
            if (extract && Archives.isArchive(target)) {
                job.message = "entpacke ${target.name}"
                Archives.extract(target, target.parentFile!!, deleteAfter = true, job = job)
                path = target.parentFile!!.path
            }
            Roms.invalidate()
            History.add(app, "download", target.name, sys, ms = System.currentTimeMillis() - t0, size = target.length().takeIf { it > 0 } ?: total)
            if (target.name.endsWith(".apk", true) && target.exists()) {
                job.message = "installiere (auf dem Thor bestätigen)"
                runCatching { Installer.install(app, target) }
            }
            Progress.done(app, "Download fertig", target.name)
            JSONObject().put("path", path).put("name", target.name)
        }
    }

    private fun targetDir(ctx: Context, e: JSONObject, name: String): File {
        e.optString("dir").takeIf { it.isNotEmpty() }?.let { d -> return Storage.resolve(d) ?: error("Ordner nicht erlaubt") }
        val sys = e.optString("system").ifEmpty { systemFor(name) }
        return when (sys) {
            "apk" -> File(APK_DIR)
            "", "-" -> Library.romsRoot(ctx)
            else -> File(Library.romsRoot(ctx), sys)
        }
    }
}
