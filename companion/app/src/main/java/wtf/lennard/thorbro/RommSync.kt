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
        // ports/recomps: their save folder belongs to the port's placeholder entry
        val portEntries = Ports.entries(ctx)
        for ((p, dir) in Ports.saveDirs(ctx)) {
            val entry = portEntries.firstOrNull { it.optString("portId") == p.id } ?: continue
            out += SaveItem(dir, "ports", entry, true)
        }
        return out.distinctBy { it.file.path.lowercase() }
    }

    private fun md5(f: File): String = MessageDigest.getInstance("MD5").let { md ->
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { x -> md.update(x.relativeTo(f).path.toByteArray()); x.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } } }
        else f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** per save: hash of the local files and the RomM save ("id@updated_at") at the last sync */
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "romm-save-state.json")
    private fun state(ctx: Context): JSONObject = runCatching { JSONObject(stateFile(ctx).readText()) }.getOrDefault(JSONObject())
    private fun remoteKey(s: JSONObject) = "${s.getInt("id")}@${s.optString("updated_at")}"
    private fun remoteTime(s: JSONObject): Long = runCatching { java.time.OffsetDateTime.parse(s.getString("updated_at")).toInstant().toEpochMilli() }
        .getOrElse { java.time.LocalDateTime.parse(s.getString("updated_at").take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }

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
        val st = state(ctx)
        val saves = localSaves(ctx)
        val pendingSaves = saves.count { s -> st.optJSONObject(s.file.path)?.optString("local") != md5(s.file) }
        return JSONObject().put("systems", systems).put("saves", saves.size).put("savesPending", pendingSaves)
            .put("canWrite", Romm.hasScope(ctx, "roms.write")).put("auto", auto(ctx))
            .put("watcher", SaveWatcher.last).put("wifi", SaveWatcher.onWifi(ctx))
    }

    /** the running "upload to RomM" job, so the UI finds it again after leaving the app */
    @Volatile var current: Jobs.Job? = null

    /** starts the upload in the background service process; keeps running when you switch apps */
    fun start(ctx: Context, systems: Set<String>, withSaves: Boolean): Jobs.Job {
        current?.takeIf { !it.done }?.let { return it }
        val app = ctx.applicationContext
        val j = Jobs.start("RomM-Abgleich") { job ->
            Transfers.begin(app)
            val ticker = kotlin.concurrent.thread(isDaemon = true) {
                while (!job.done) { Progress.show(app, "Spiele zu RomM", job.progress, job.message); runCatching { Thread.sleep(2000) } }
            }
            try { run(app, systems, withSaves, job) } finally {
                Transfers.end(); ticker.interrupt()
                Progress.done(app, "Abgleich mit RomM fertig", job.message)
            }
        }
        current = j
        return j
    }

    fun run(ctx: Context, systems: Set<String>, withSaves: Boolean, job: Jobs.Job): JSONObject {
        var romsUp = 0; var savesUp = 0; var savesDown = 0; val errors = JSONArray()
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
            } catch (e: Exception) { errors.put("${f.name}: ${e.message}"); Logs.e("romm", "Upload ${f.name}", e) }
            done += rom.getLong("size")
        }
        if (withSaves) {
            // fetch first, then upload: a newer save from another device must not be overwritten
            val d = pullSaves(ctx)
            savesDown = d.optInt("downloaded"); d.optJSONArray("errors")?.let { for (i in 0 until it.length()) errors.put(it.get(i)) }
            val r = syncSaves(ctx, job)
            savesUp = r.optInt("uploaded"); r.optJSONArray("errors")?.let { for (i in 0 until it.length()) errors.put(it.get(i)) }
        }
        return JSONObject().put("romsUploaded", romsUp).put("savesUploaded", savesUp).put("savesDownloaded", savesDown).put("errors", errors)
    }

    /** uploads changed saves into the user's RomM account; games RomM lacks are skipped */
    @Synchronized
    fun syncSaves(ctx: Context, job: Jobs.Job?): JSONObject {
        val (bySlug, remoteFiles) = remote(ctx)
        val st = state(ctx)
        var n = 0; val errors = JSONArray(); var skipped = 0
        for (s in localSaves(ctx)) {
            val hash = md5(s.file)
            val prev = st.optJSONObject(s.file.path)
            if (prev?.optString("local") == hash) continue
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) }
            if (romId == null) { skipped++; continue }
            // RomM has a save we have not seen yet (another device): only upload when ours is newer
            if (s.isFolder) {
                val newest = runCatching { Romm.listSaves(ctx, romId).maxByOrNull { it.optString("updated_at") } }.getOrNull()
                if (newest != null && remoteKey(newest) != prev?.optString("remote")) {
                    val localTime = s.file.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
                    if (localTime <= remoteTime(newest) + 30_000) continue
                }
            }
            job?.message = "Spielstand ${s.file.name}"
            try {
                val payload = if (s.isFolder) File(ctx.cacheDir, s.file.name + ".zip").also { zipFolder(s.file, it) } else s.file
                val res = Romm.uploadSave(ctx, romId, payload, emulator = s.system, contentHash = hash)
                if (s.isFolder) payload.delete()
                st.put(s.file.path, JSONObject().put("local", hash).put("remote", remoteKey(res))); n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}") }
        }
        stateFile(ctx).writeText(st.toString())
        return JSONObject().put("uploaded", n).put("skippedNoGame", skipped).put("errors", errors)
    }

    /**
     * Fetches newer saves from RomM (e.g. played on the Mac) for folder saves (ports/recomps, Switch).
     * Newest wins: when RomM has a different save that is newer than the local files, the local state is
     * backed up first and the RomM one is unpacked into the save folder (and its ".work" twin if present).
     */
    @Synchronized
    fun pullSaves(ctx: Context): JSONObject {
        val (bySlug, remoteFiles) = remote(ctx)
        val up = uploaded(ctx)
        var n = 0; val errors = JSONArray()
        for (s in localSaves(ctx).filter { it.isFolder }) {
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) } ?: continue
            try {
                val newest = Romm.listSaves(ctx, romId).maxByOrNull { it.optString("updated_at") } ?: continue
                val st = state(ctx)
                val prev = st.optJSONObject(s.file.path)
                val localHash = md5(s.file)
                val remoteChanged = remoteKey(newest) != prev?.optString("remote")
                val localChanged = localHash != prev?.optString("local")
                if (!remoteChanged) continue
                if (localChanged) { // both changed (or first sync): the newer one wins
                    val localTime = s.file.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
                    if (remoteTime(newest) <= localTime + 30_000) continue // local is newer: syncSaves uploads it
                }
                val zip = File(ctx.cacheDir, "romm-save-${newest.getInt("id")}.zip")
                Romm.downloadSave(ctx, newest.getInt("id"), zip)
                runCatching { Saves.backup(ctx, null, force = true, label = "vor-romm") }
                val targets = listOf(s.file) + listOf(File(s.file.path + ".work")).filter { it.isDirectory }
                targets.forEach { unzipStripped(zip, it) }
                zip.delete()
                val st2 = state(ctx)
                st2.put(s.file.path, JSONObject().put("local", md5(s.file)).put("remote", remoteKey(newest)))
                stateFile(ctx).writeText(st2.toString())
                n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}") }
        }
        uploadedFile(ctx).writeText(up.toString())
        return JSONObject().put("downloaded", n).put("errors", errors)
    }

    /** unpacks a save zip whose entries sit in one top folder ("<folder>/slot_00/...") into [dir] */
    private fun unzipStripped(zip: File, dir: File) {
        val base = dir.canonicalPath
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val rel = e.name.substringAfter('/', e.name)
                if (rel.isEmpty() || e.isDirectory) continue
                val t = File(dir, rel).canonicalFile
                if (!t.path.startsWith("$base/")) continue
                t.parentFile?.mkdirs()
                t.outputStream().use { z.copyTo(it) }
            }
        }
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
