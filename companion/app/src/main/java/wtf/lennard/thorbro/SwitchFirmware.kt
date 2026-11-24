package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Uploads Eden's Switch keys and system firmware to RomM (firmware of platform "switch"), so RomBro on the Mac
 * can install them from there: prod.keys/title.keys one by one, the firmware NCAs as one
 * "Switch Firmware <date>.zip". Only file names ever reach logs or the history, never key contents.
 */
object SwitchFirmware {
    private const val PKG = "dev.eden.eden_emulator"
    private val KEYS = listOf("prod.keys", "title.keys")
    private val ZIP_NAME = Regex("^Switch Firmware .*\\.zip$")
    /** fixed entry time: the same NCAs always give the same zip, so its md5 says whether anything changed */
    private const val ENTRY_TIME = 1577836800000L // 2020-01-01

    private val edenDir get() = File(Storage.primary, "Android/data/$PKG/files")
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "romm-firmware-state.json")
    private fun state(ctx: Context) = runCatching { JSONObject(stateFile(ctx).readText()) }.getOrDefault(JSONObject())

    /** the running manual upload, so the web panel finds it again */
    @Volatile var current: Jobs.Job? = null

    fun start(ctx: Context): Jobs.Job {
        current?.takeIf { !it.done }?.let { return it }
        val app = ctx.applicationContext
        return Jobs.start("Switch-Keys & Firmware zu RomM") { job -> sync(app, force = true, job = job) }.also { current = it }
    }

    /**
     * Keys + firmware from Eden into RomM. [force] = false (automatic sync) skips the work entirely while the
     * files look the same as at the last successful run.
     * Result: uploaded (names), unchanged, and either error, needsRepair (pairing lacks firmware.write) or missing.
     */
    @Synchronized
    fun sync(ctx: Context, force: Boolean = false, job: Jobs.Job? = null): JSONObject {
        val out = JSONObject().put("uploaded", JSONArray()).put("unchanged", 0)
        if (!Romm.status(ctx).optBoolean("connected")) return out.put("error", "RomM nicht verbunden")
        if (!Romm.hasScope(ctx, "firmware.write")) return out.put("needsRepair", true).put("error", "RomM neu koppeln für Firmware")
        var staging: File? = null
        try {
            job?.message = "Lese Eden-Ordner"
            var src = edenDir
            if (File(src, "keys").listFiles() == null) { // not readable directly (Android/data): copy with shell rights
                staging = stageViaShell(src) ?: return out.put("missing", if (Shell.ready()) "Eden-Keys nicht gefunden" else "Eden-Ordner nicht lesbar – Shizuku starten und ThorBro erlauben")
                src = staging
            }
            val keys = KEYS.map { File(src, "keys/$it") }.filter { it.isFile && it.length() > 0 }
            val ncas = File(src, "nand/system/Contents/registered").listFiles()?.filter { it.name.endsWith(".nca", true) }?.sortedBy { it.name }.orEmpty()
            if (keys.isEmpty() && ncas.isEmpty()) return out.put("missing", "Eden hat keine Keys/Firmware")

            val fp = fingerprint(keys + ncas)
            val st = state(ctx)
            if (!force && st.optString("fingerprint") == fp) return out

            val pid = RommSync.remote(ctx, maxAgeMs = 30 * 60_000L).first["switch"] ?: Romm.createPlatform(ctx, "switch")
            val remote = Romm.listFirmware(ctx, pid)
            val uploaded = st.optJSONObject("uploaded") ?: JSONObject()
            val todo = mutableListOf<Pair<String, File>>()
            var zip: File? = null
            for (k in keys) todo += k.name to k
            if (ncas.isNotEmpty()) {
                job?.message = "Packe ${ncas.size} Firmware-Dateien"
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ncas.maxOf { newest(it) }))
                val name = "Switch Firmware $date.zip"
                zip = File(ctx.cacheDir, name).also { zipNcas(ncas, it) }
                todo += name to zip
            }
            val send = todo.filter { (name, f) ->
                val hash = RommSync.md5(f)
                val r = remote.firstOrNull { it.optString("file_name") == name }
                // RomM keeps the old md5 when a file of the same name is overwritten: also trust what we sent last time
                val same = r != null && (r.optString("md5_hash") == hash || uploaded.optString(name) == hash)
                if (!same) uploaded.put(name, hash)
                !same
            }
            out.put("unchanged", todo.size - send.size)
            if (send.isNotEmpty()) {
                job?.message = "Lade ${send.joinToString(", ") { it.first }} hoch"
                Romm.uploadFirmware(ctx, pid, send)
                Logs.i("romm", "Switch-Firmware hochgeladen: ${send.joinToString(", ") { it.first }}")
                History.add(ctx, "firmware_up", "Switch-Keys & Firmware", "switch", send.joinToString(", ") { it.first })
                send.forEach { out.getJSONArray("uploaded").put(it.first) }
            }
            // a newer firmware zip replaces the older ones ThorBro put there: RomBro unpacks every zip into the same folder
            val zipName = zip?.name
            if (zipName != null) {
                val old = remote.filter { ZIP_NAME.matches(it.optString("file_name")) && it.optString("file_name") != zipName }.map { it.getInt("id") }
                if (old.isNotEmpty()) runCatching { Romm.deleteFirmware(ctx, old) }.onFailure { Logs.e("romm", "alte Switch-Firmware entfernen", it) }
            }
            zip?.delete()
            stateFile(ctx).writeText(st.put("fingerprint", fp).put("uploaded", uploaded).toString())
            job?.message = if (send.isEmpty()) "RomM hat Keys & Firmware schon" else "${send.size} Datei(en) hochgeladen"
            return out
        } catch (e: Romm.MissingScope) {
            return out.put("needsRepair", true).put("error", e.message)
        } catch (e: Exception) {
            Logs.e("romm", "Switch-Firmware hochladen", e)
            return out.put("error", e.message ?: e.javaClass.simpleName)
        } finally {
            staging?.deleteRecursively()
        }
    }

    /** copies keys + system NCAs with shell rights into a private-ish staging folder; null when that fails */
    private fun stageViaShell(src: File): File? {
        if (!Shell.ready()) return null
        val dir = File(Storage.primary, "ThorBro/.staging/eden-fw").apply { deleteRecursively(); mkdirs(); File(parentFile, ".nomedia").createNewFile() }
        val q = { f: File -> "'" + f.path.replace("'", "'\\''") + "'" }
        val reg = File(dir, "nand/system/Contents/registered").apply { mkdirs() }
        val keys = File(dir, "keys").apply { mkdirs() }
        val script = "cp ${q(File(src, "keys"))}/*.keys ${q(keys)}/ 2>/dev/null; cp -r ${q(File(src, "nand/system/Contents/registered"))}/. ${q(reg)}/ 2>/dev/null; true"
        Shell.exec("sh", "-c", script) ?: return null
        return dir.takeIf { (keys.listFiles()?.isNotEmpty() == true) || (reg.listFiles()?.isNotEmpty() == true) }
            ?: run { dir.deleteRecursively(); null }
    }

    /** an NCA is a file, or (when big) a folder of parts 00, 01, … */
    private fun newest(f: File): Long = if (f.isDirectory) f.listFiles()?.maxOfOrNull { it.lastModified() } ?: f.lastModified() else f.lastModified()

    private fun fingerprint(fs: List<File>): String {
        val md = MessageDigest.getInstance("SHA-1")
        fs.forEach { f ->
            val parts = if (f.isDirectory) f.listFiles()?.sortedBy { it.name }.orEmpty() else listOf(f)
            parts.forEach { md.update("${f.name}/${it.name}|${it.length()}|${it.lastModified()}\n".toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun zipNcas(ncas: List<File>, zip: File) = ZipOutputStream(zip.outputStream().buffered()).use { z ->
        for (f in ncas) {
            z.putNextEntry(ZipEntry(f.name).apply { time = ENTRY_TIME })
            val parts = if (f.isDirectory) f.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty() else listOf(f)
            parts.forEach { p -> p.inputStream().use { it.copyTo(z) } }
            z.closeEntry()
        }
    }
}
