package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pushes the device's library to RomM: games RomM does not have yet go into the shared library,
 * save files go into the signed-in user's account (attached to the matching game).
 */
object RommSync {
    /** ThorBro console id -> RomM platform folder (fs_slug) */
    private val toSlug = mapOf("n3ds" to "3ds", "gc" to "ngc", "dreamcast" to "dc", "megadrive" to "genesis",
        "mastersystem" to "sms", "pcengine" to "tg16", "ngp" to "ngpc")
    fun slugFor(system: String) = toSlug[system] ?: system

    private const val CHUNK = 8 * 1024 * 1024

    private fun localSystems(ctx: Context): List<Pair<String, List<JSONObject>>> {
        val s = Roms.scan(ctx, false).getJSONArray("systems")
        return (0 until s.length()).map { s.getJSONObject(it) }.filter { it.getString("id") != "_unassigned" }
            .map { sys -> sys.getString("id") to sys.getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    }

    /** RomM platforms by fs_slug, and the file names RomM has per platform */
    private fun remote(ctx: Context): Pair<Map<String, Int>, Map<Int, Map<String, Int>>> {
        val plats = Romm.rawPlatforms(ctx)
        val bySlug = plats.associate { it.optString("fs_slug") to it.getInt("id") }
        val files = bySlug.values.associateWith { pid -> Romm.rawRoms(ctx, pid).associate { it.optString("fs_name") to it.getInt("id") } }
        return bySlug to files
    }

    // ---------- save matching ----------

    class SaveItem(val file: File, val system: String, val rom: JSONObject, val isFolder: Boolean)

    private val saveExt = Regex("(?i).*\\.(sav|srm|dsv|eep|fla|sra|mpk|mcr)$")

    /** saves that can be tied to a local ROM: same base name, or Switch title id in the folder name */
    fun localSaves(ctx: Context): List<SaveItem> {
        val out = mutableListOf<SaveItem>()
        val systems = localSystems(ctx).toMap()
        val byBase = systems.flatMap { (sys, roms) -> roms.map { Triple(it.getString("file").substringBeforeLast('.').lowercase(), sys, it) } }
            .groupBy { it.first }
        val dirs = Saves.locations(ctx).map { it.dir }.filter { it.isDirectory }.distinctBy { it.path.lowercase() }
        for (d in dirs) {
            d.walkTopDown().maxDepth(4).filter { it.isFile && saveExt.matches(it.name) && !it.name.startsWith("._") }.forEach { f ->
                val base = f.name.substringBeforeLast('.').lowercase()
                val hit = byBase[base]?.firstOrNull() ?: byBase.entries.firstOrNull { (k, _) -> base.startsWith(k) && k.length > 6 }?.value?.first()
                if (hit != null) out += SaveItem(f, hit.second, hit.third, false)
            }
            // Switch: folders like "Echoes-of-Wisdom_01008CF01BAAC000"
            d.walkTopDown().maxDepth(3).filter { it.isDirectory && Regex("_([0-9A-Fa-f]{16})$").containsMatchIn(it.name) }.forEach { f ->
                val tid = Regex("_([0-9A-Fa-f]{16})$").find(f.name)!!.groupValues[1].uppercase()
                val rom = systems["switch"]?.firstOrNull { it.optString("baseId") == tid && it.optString("switchKind") == "base" }
                if (rom != null && (f.listFiles()?.isNotEmpty() == true)) out += SaveItem(f, "switch", rom, true)
            }
        }
        return out.distinctBy { it.file.path.lowercase() }
    }

    private fun md5(f: File): String = MessageDigest.getInstance("MD5").let { md ->
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { x -> md.update(x.relativeTo(f).path.toByteArray()); x.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } } }
        else f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uploadedFile(ctx: Context) = File(ctx.filesDir, "romm-saves-uploaded.json")
    private fun uploaded(ctx: Context): JSONObject = runCatching { JSONObject(uploadedFile(ctx).readText()) }.getOrDefault(JSONObject())

    // ---------- plan + run ----------

    fun plan(ctx: Context): JSONObject {
        val (bySlug, remoteFiles) = remote(ctx)
        val systems = JSONArray()
        for ((sys, roms) in localSystems(ctx)) {
            val pid = bySlug[slugFor(sys)]
            val have = pid?.let { remoteFiles[it] } ?: emptyMap()
            val missing = roms.filter { it.getString("file") !in have }
            systems.put(JSONObject().put("system", sys).put("slug", slugFor(sys)).put("local", roms.size)
                .put("missing", missing.size).put("missingSize", missing.sumOf { it.getLong("size") }).put("inRomm", roms.size - missing.size))
        }
        val up = uploaded(ctx)
        val saves = localSaves(ctx)
        val pendingSaves = saves.count { s -> up.optString(s.file.path) != "${s.file.length()}|${s.file.lastModified()}" }
        return JSONObject().put("systems", systems).put("saves", saves.size).put("savesPending", pendingSaves)
            .put("canWrite", Romm.hasScope(ctx, "roms.write")).put("auto", auto(ctx))
            .put("watcher", SaveWatcher.last).put("wifi", SaveWatcher.onWifi(ctx))
    }

    fun run(ctx: Context, systems: Set<String>, withSaves: Boolean, job: Jobs.Job): JSONObject {
        var romsUp = 0; var savesUp = 0; val errors = JSONArray()
        var (bySlug, remoteFiles) = remote(ctx)
        val todo = localSystems(ctx).filter { it.first in systems }.flatMap { (sys, roms) ->
            val have = bySlug[slugFor(sys)]?.let { remoteFiles[it] } ?: emptyMap()
            roms.filter { it.getString("file") !in have }.map { sys to it }
        }
        val total = todo.sumOf { it.second.getLong("size") }.coerceAtLeast(1)
        var done = 0L
        for ((sys, rom) in todo) {
            val slug = slugFor(sys)
            val pid = bySlug[slug] ?: Romm.createPlatform(ctx, slug).also { bySlug = bySlug + (slug to it) }
            val f = File(rom.getString("path"))
            job.message = "Lade ${f.name} hoch"
            try {
                if (f.isDirectory) {
                    val zip = File(ctx.cacheDir, "romm-upload.zip")
                    zipFolder(f, zip); upload(ctx, pid, f.name + ".zip", zip) { n -> job.progress = (done + n).toDouble() / total }; zip.delete()
                } else upload(ctx, pid, f.name, f) { n -> job.progress = (done + n).toDouble() / total }
                romsUp++
            } catch (e: Exception) { errors.put("${f.name}: ${e.message}") }
            done += rom.getLong("size")
        }
        if (withSaves) {
            val r = syncSaves(ctx, job)
            savesUp = r.optInt("uploaded"); r.optJSONArray("errors")?.let { for (i in 0 until it.length()) errors.put(it.get(i)) }
        }
        return JSONObject().put("romsUploaded", romsUp).put("savesUploaded", savesUp).put("errors", errors)
    }

    /** uploads changed saves into the user's RomM account; games RomM lacks are skipped */
    @Synchronized
    fun syncSaves(ctx: Context, job: Jobs.Job?): JSONObject {
        val (bySlug, remoteFiles) = remote(ctx)
        val up = uploaded(ctx)
        var n = 0; val errors = JSONArray(); var skipped = 0
        for (s in localSaves(ctx)) {
            val stamp = "${s.file.length()}|${s.file.lastModified()}"
            if (up.optString(s.file.path) == stamp) continue
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) }
            if (romId == null) { skipped++; continue }
            job?.message = "Spielstand ${s.file.name}"
            try {
                val payload = if (s.isFolder) File(ctx.cacheDir, s.file.name + ".zip").also { zipFolder(s.file, it) } else s.file
                Romm.uploadSave(ctx, romId, payload, emulator = s.system, contentHash = md5(s.file))
                if (s.isFolder) payload.delete()
                up.put(s.file.path, stamp); n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}") }
        }
        uploadedFile(ctx).writeText(up.toString())
        return JSONObject().put("uploaded", n).put("skippedNoGame", skipped).put("errors", errors)
    }

    private fun upload(ctx: Context, platformId: Int, name: String, f: File, progress: (Long) -> Unit) {
        val chunks = ((f.length() + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        val id = Romm.uploadStart(ctx, platformId, name, f.length(), chunks)
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(CHUNK)
            for (i in 0 until chunks) {
                r.seek(i.toLong() * CHUNK)
                val n = r.read(buf).coerceAtLeast(0)
                Romm.uploadChunk(ctx, id, i, buf, n)
                progress(i.toLong() * CHUNK + n)
            }
        }
        Romm.uploadComplete(ctx, id)
    }

    private fun zipFolder(dir: File, zip: File) = ZipOutputStream(zip.outputStream().buffered()).use { z ->
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
    }

    fun auto(ctx: Context) = Prefs.of(ctx).getBoolean("rommSaveSync", true)
    fun setAuto(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putBoolean("rommSaveSync", on).apply()
}
