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
    /** chunks in flight at once: one connection alone does not fill Wi-Fi 6 */
    private const val PARALLEL = 4

    private fun localSystems(ctx: Context): List<Pair<String, List<JSONObject>>> {
        val s = Roms.scan(ctx, false).getJSONArray("systems")
        return (0 until s.length()).map { s.getJSONObject(it) }.filter { it.getString("id") != "_unassigned" }
            .map { sys -> sys.getString("id") to sys.getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    }

    /** RomM platforms by fs_slug, and the file names RomM has per platform */
    internal fun remote(ctx: Context, maxAgeMs: Long = 0): Pair<Map<String, Int>, Map<Int, Map<String, Int>>> {
        remoteCache?.let { (at, v) -> if (maxAgeMs > 0 && System.currentTimeMillis() - at < maxAgeMs) return v }
        return fetchRemote(ctx).also { remoteCache = System.currentTimeMillis() to it }
    }

    /** the full platform + ROM list is big; the periodic pull reuses it for half an hour */
    @Volatile private var remoteCache: Pair<Long, Pair<Map<String, Int>, Map<Int, Map<String, Int>>>>? = null

    private fun fetchRemote(ctx: Context): Pair<Map<String, Int>, Map<Int, Map<String, Int>>> {
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
        // 3DS (Azahar): one save folder per title id
        for (rom in systems["n3ds"].orEmpty()) {
            val dir = N3ds.saveDir(File(rom.getString("path"))) ?: continue
            if (dir.listFiles()?.isNotEmpty() == true) out += SaveItem(dir, "n3ds", rom, true)
        }
        // ports/recomps: their save folder belongs to the port's placeholder entry
        val portEntries = Ports.entries(ctx)
        for ((p, dir) in Ports.saveDirs(ctx)) {
            val entry = portEntries.firstOrNull { it.optString("portId") == p.id } ?: continue
            out += SaveItem(dir, "ports", entry, true)
        }
        return out.distinctBy { it.file.path.lowercase() }
    }

    internal fun md5(f: File): String = MessageDigest.getInstance("MD5").let { md ->
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { x -> md.update(x.relativeTo(f).path.toByteArray()); x.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } } }
        else f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** per save: hash of the local files and the RomM save ("id@updated_at") at the last sync */
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "romm-save-state.json")
    internal fun state(ctx: Context): JSONObject = runCatching { JSONObject(stateFile(ctx).readText()) }.getOrDefault(JSONObject())
    /** sync state is per profile: the same save file holds a different profile's save after a swap */
    internal fun stateKey(ctx: Context, s: SaveItem): String {
        val o = Profiles.owner(ctx, s.rom.optString("path"))
        return if (o == Profiles.MAIN) s.file.path else "${s.file.path}#$o"
    }
    /** RomM saves of the profile that is live for this save (its slot) */
    private fun slotSaves(ctx: Context, romId: Int, s: SaveItem): List<JSONObject> {
        val slot = Profiles.slot(ctx, Profiles.owner(ctx, s.rom.optString("path")))
        return Romm.listSaves(ctx, romId).filter { Romm.slotOf(it) == slot }
    }
    private fun remoteKey(s: JSONObject) = "${s.getInt("id")}@${s.optString("updated_at")}"
    private fun parseTime(t: String): Long? = runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(t.take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    /**
     * is [s] the RomM save we saw at the last sync? Same id and same second: the upload answer and the save list
     * write the time differently (fractions, offset), so comparing the raw strings re-downloaded our own uploads
     */
    private fun sameRemote(prev: String?, s: JSONObject): Boolean {
        if (prev.isNullOrEmpty()) return false
        val id = prev.substringBefore('@'); val t = prev.substringAfter('@', "")
        if (id != s.getInt("id").toString()) return false
        val a = parseTime(t); val b = parseTime(s.optString("updated_at"))
        return if (a != null && b != null) a / 1000 == b / 1000 else t == s.optString("updated_at")
    }
    private fun remoteTime(s: JSONObject): Long = runCatching { java.time.OffsetDateTime.parse(s.getString("updated_at")).toInstant().toEpochMilli() }
        .getOrElse { java.time.LocalDateTime.parse(s.getString("updated_at").take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }

    private fun uploadedFile(ctx: Context) = File(ctx.filesDir, "romm-saves-uploaded.json")
    private fun uploaded(ctx: Context): JSONObject = runCatching { JSONObject(uploadedFile(ctx).readText()) }.getOrDefault(JSONObject())

    // ---------- what RomM still needs ----------

    /** games this Thor has uploaded ("slug/file" -> size): RomM only lists them after its next scan, so a restarted
     *  batch would otherwise send them again */
    private fun ledgerFile(ctx: Context) = File(ctx.filesDir, "romm-roms-uploaded.json")
    private val ledgerLock = Any()
    internal fun ledger(ctx: Context): JSONObject = synchronized(ledgerLock) { runCatching { JSONObject(ledgerFile(ctx).readText()) }.getOrDefault(JSONObject()) }
    private fun remember(ctx: Context, slug: String, file: String, size: Long) = synchronized(ledgerLock) {
        val l = ledger(ctx).put("$slug/$file", size); ledgerFile(ctx).writeText(l.toString())
    }
    /** uploaded from this Thor and waiting for RomM's next scan */
    fun uploadedByUs(system: String, rom: JSONObject, led: JSONObject): Boolean =
        rom.optLong("size") > 0 && led.optLong("${slugFor(system)}/${rom.getString("file")}", -1L) == rom.optLong("size")
    /** not in RomM, not uploaded by us already, and not an empty leftover file */
    private fun missing(rom: JSONObject, slug: String, have: Map<String, Int>, led: JSONObject): Boolean {
        val file = rom.getString("file"); val size = rom.optLong("size")
        return size > 0 && file !in have && led.optLong("$slug/$file", -1L) != size
    }

    // ---------- plan + run ----------

    fun plan(ctx: Context): JSONObject {
        val (bySlug, remoteFiles) = remote(ctx)
        val systems = JSONArray()
        for ((sys, roms) in localSystems(ctx)) {
            val pid = bySlug[slugFor(sys)]
            val have = pid?.let { remoteFiles[it] } ?: emptyMap()
            val led = ledger(ctx)
            val missing = roms.filter { missing(it, slugFor(sys), have, led) }
            systems.put(JSONObject().put("system", sys).put("slug", slugFor(sys)).put("local", roms.size)
                .put("missing", missing.size).put("missingSize", missing.sumOf { it.getLong("size") }).put("inRomm", roms.size - missing.size))
        }
        val st = state(ctx)
        val saves = localSaves(ctx)
        val pendingSaves = saves.count { s -> st.optJSONObject(stateKey(ctx, s))?.optString("local") != md5(s.file) }
        return JSONObject().put("systems", systems).put("saves", saves.size).put("savesPending", pendingSaves)
            .put("canWrite", Romm.hasScope(ctx, "roms.write")).put("auto", auto(ctx))
            .put("watcher", SaveWatcher.last).put("wifi", SaveWatcher.onWifi(ctx))
    }

    /** the running "upload to RomM" job, so the UI finds it again after leaving the app */
    @Volatile var current: Jobs.Job? = null

    /** starts the upload in the background service process; keeps running when you switch apps */
    /** [systems] null = every console RomM is missing games of; the job starts at once and works that out itself */
    /** [only] limits the upload to these local ROM paths (one game: base + updates/DLC) */
    fun start(ctx: Context, systems: Set<String>?, withSaves: Boolean, only: Set<String>? = null): Jobs.Job {
        current?.takeIf { !it.done }?.let { return it }
        val app = ctx.applicationContext
        val j = Jobs.start(if (only != null) "Zu RomM hochladen" else "RomM-Abgleich") { job ->
            job.message = "Verbinde mit RomM …"
            Transfers.begin(app)
            val ticker = kotlin.concurrent.thread(isDaemon = true) {
                while (!job.done) { Progress.show(app, "Spiele zu RomM", job.progress, job.message); runCatching { Thread.sleep(2000) } }
            }
            try { run(app, systems, withSaves, job, only) } finally {
                Transfers.end(); ticker.interrupt()
                Progress.done(app, "Abgleich mit RomM fertig", job.message)
            }
        }
        current = j
        return j
    }

    /** uploads one game (all [paths] of it) on its own; the running job if a batch is already going */
    fun uploadGame(ctx: Context, paths: Set<String>): Jobs.Job {
        val systems = localSystems(ctx).filter { (_, roms) -> roms.any { it.getString("path") in paths } }.map { it.first }.toSet()
        require(systems.isNotEmpty()) { "Spiel nicht gefunden" }
        return start(ctx, systems, withSaves = false, only = paths)
    }

    fun run(ctx: Context, systems: Set<String>?, withSaves: Boolean, job: Jobs.Job, only: Set<String>? = null): JSONObject {
        var romsUp = 0; var savesUp = 0; var savesDown = 0; val errors = JSONArray()
        job.message = "Verbinde mit RomM …"
        var (bySlug, remoteFiles) = remote(ctx)
        job.message = "Prüfe, was RomM fehlt …"
        val led = ledger(ctx)
        val todo = localSystems(ctx).filter { systems == null || it.first in systems }.flatMap { (sys, roms) ->
            val have = bySlug[slugFor(sys)]?.let { remoteFiles[it] } ?: emptyMap()
            roms.filter { (only == null || it.getString("path") in only) && missing(it, slugFor(sys), have, led) }.map { sys to it }
        }
        if (todo.isEmpty()) job.message = if (only != null) "RomM hat das Spiel schon" else "RomM hat schon alle Spiele"
        val total = todo.sumOf { it.second.getLong("size") }.coerceAtLeast(1)
        var done = 0L
        job.total = todo.size
        for ((n, pair) in todo.withIndex()) {
            val (sys, rom) = pair
            job.index = n + 1
            val slug = slugFor(sys)
            val pid = bySlug[slug] ?: Romm.createPlatform(ctx, slug).also { bySlug = bySlug + (slug to it) }
            val f = File(rom.getString("path"))
            job.game(rom.optString("name").ifEmpty { f.nameWithoutExtension }, sys, file = f.path)
            val romSize = rom.getLong("size")
            job.message = "Lade ${f.name} hoch"
            /** the file actually sent can differ from the listed size (zipped folders): scale it onto the rom's share */
            fun report(file: File): (Long) -> Unit { val len = file.length().coerceAtLeast(1); return { sent ->
                val now = done + sent * romSize / len
                job.progress = now.toDouble() / total
                job.bytes(now, total); job.gameBytes(sent, len)
            } }
            if (Romm.status(ctx).optBoolean("viaLan")) job.message = "Lade ${f.name} hoch · Heimnetz"
            val t0 = System.currentTimeMillis()
            val assembling = { job.message = "RomM setzt ${f.name} zusammen …"; job.gameBytes(0, -1) }
            try {
                if (f.isDirectory) {
                    job.message = "Packe ${f.name} …"
                    val zip = File(ctx.cacheDir, "romm-upload.zip")
                    try { zipFolder(f, zip); upload(ctx, pid, f.name + ".zip", zip, assembling, report(zip)) } finally { zip.delete() }
                } else upload(ctx, pid, f.name, f, assembling, report(f))
                remember(ctx, slug, rom.getString("file"), romSize)
                romsUp++; History.add(ctx, "rom_up", rom.optString("name").ifEmpty { f.name }, sys, ms = System.currentTimeMillis() - t0, size = romSize, path = f.path)
            } catch (e: Exception) { errors.put("${f.name}: ${e.message}"); Logs.e("romm", "Upload ${f.name}", e) }
            done += romSize
            job.progress = done.toDouble() / total
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
            val key = stateKey(ctx, s)
            val prev = st.optJSONObject(key)
            if (prev?.optString("local") == hash) continue
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) }
            if (romId == null) { skipped++; continue }
            // RomM has a save we have not seen yet (another device): only upload when ours is newer
            if (s.isFolder) {
                val newest = runCatching { slotSaves(ctx, romId, s).maxByOrNull { it.optString("updated_at") } }.getOrNull()
                if (newest != null && !sameRemote(prev?.optString("remote"), newest)) {
                    val localTime = s.file.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
                    if (localTime <= remoteTime(newest) + 30_000) continue
                }
            }
            job?.message = "Spielstand ${s.file.name}"
            try {
                // folder saves carry the device in the name ("saves (AYN Thor).zip"), so RomM and ThorBro show where a save comes from
                val payload = if (s.isFolder) File(ctx.cacheDir, "${s.file.name} (${Device.label(ctx)}).zip").also { zipFolder(s.file, it) } else s.file
                val res = Romm.uploadSave(ctx, romId, payload, emulator = s.system, contentHash = hash,
                    slot = Profiles.slot(ctx, Profiles.owner(ctx, s.rom.optString("path"))))
                if (s.isFolder) payload.delete()
                st.put(key, JSONObject().put("local", hash).put("remote", remoteKey(res))); n++
                History.add(ctx, "save_up", s.rom.optString("name"), s.system)
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}"); Logs.e("romm", "Spielstand hochladen ${s.file.path}", e) }
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
        val (bySlug, remoteFiles) = remote(ctx, maxAgeMs = 30 * 60_000L)
        val up = uploaded(ctx)
        var n = 0; val errors = JSONArray()
        for (s in localSaves(ctx).filter { it.isFolder }) {
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) } ?: continue
            try {
                val newest = slotSaves(ctx, romId, s).maxByOrNull { it.optString("updated_at") } ?: continue
                val st = state(ctx)
                val key = stateKey(ctx, s)
                val prev = st.optJSONObject(key)
                val remoteChanged = !sameRemote(prev?.optString("remote"), newest)
                if (!remoteChanged) continue
                // hashing a whole save folder is expensive: only when RomM actually has something new
                val localChanged = md5(s.file) != prev?.optString("local")
                if (localChanged) { // both changed (or first sync): the newer one wins
                    val localTime = s.file.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
                    if (remoteTime(newest) <= localTime + 30_000) continue // local is newer: syncSaves uploads it
                }
                val zip = File(ctx.cacheDir, "romm-save-${newest.getInt("id")}.zip")
                Romm.downloadSave(ctx, newest.getInt("id"), zip)
                runCatching { Saves.backup(ctx, null, label = "vor-romm") } // only changed locations; unchanged ones already have a matching snapshot
                val targets = listOf(s.file) + listOf(File(s.file.path + ".work")).filter { it.isDirectory }
                targets.forEach { unzipStripped(zip, it) }
                zip.delete()
                val st2 = state(ctx)
                st2.put(key, JSONObject().put("local", md5(s.file)).put("remote", remoteKey(newest)))
                History.add(ctx, "save_down", s.rom.optString("name"), s.system)
                stateFile(ctx).writeText(st2.toString())
                n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}"); Logs.e("romm", "Spielstand holen ${s.file.path}", e) }
        }
        uploadedFile(ctx).writeText(up.toString())
        return JSONObject().put("downloaded", n).put("errors", errors)
    }

    /** unpacks a save zip whose entries sit in one top folder ("<folder>/slot_00/...") into [dir] */
    internal fun unzipStripped(zip: File, dir: File) {
        val base = dir.canonicalPath
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val rel = e.name.substringAfter('/', e.name)
                if (rel.isEmpty() || e.isDirectory) continue
                val t = File(dir, rel).canonicalFile
                if (!t.path.startsWith("$base/")) continue
                SaveFs.write(t, z)
            }
        }
    }

    /** the unfinished upload, so a restart (app closed, Thor asleep) carries on at the next chunk; RomM keeps a session 24 h */
    private fun sessionFile(ctx: Context) = File(ctx.filesDir, "romm-upload-session.json")

    /** uploads [f] in [CHUNK]-sized pieces, [PARALLEL] at a time (RomM stores each chunk by index);
     *  [progress] gets the bytes on the wire so far, updated every 256 KB */
    internal fun upload(ctx: Context, platformId: Int, name: String, f: File, onAssemble: () -> Unit = {}, progress: (Long) -> Unit) {
        val size = f.length()
        val chunks = ((size + CHUNK - 1) / CHUNK).toInt()
        val key = "$platformId|$name|${f.path}|$size|${f.lastModified()}"
        val old = runCatching { JSONObject(sessionFile(ctx).readText()) }.getOrNull()
            ?.takeIf { it.optString("key") == key && System.currentTimeMillis() - it.optLong("t") < 20 * 3600_000L }
        val done = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val id = if (old != null) {
            old.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done += a.getInt(i) }
            old.getString("id")
        } else Romm.uploadStart(ctx, platformId, name, size, chunks)
        val sessionLock = Any()
        fun saveSession() = synchronized(sessionLock) {
            runCatching { sessionFile(ctx).writeText(JSONObject().put("key", key).put("id", id).put("t", old?.optLong("t") ?: System.currentTimeMillis())
                .put("done", JSONArray(done.sorted())).toString()) }
        }
        saveSession()
        fun len(i: Int) = minOf(CHUNK.toLong(), size - i.toLong() * CHUNK)
        val sent = java.util.concurrent.atomic.AtomicLong(done.sumOf { len(it) })
        if (sent.get() > 0) progress(sent.get())
        val todo = java.util.concurrent.ConcurrentLinkedQueue((0 until chunks).filter { it !in done })
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val workers = (1..minOf(PARALLEL, todo.size)).map {
            kotlin.concurrent.thread(name = "romm-up-$it") {
                try {
                    RandomAccessFile(f, "r").use { r ->
                        val buf = ByteArray(CHUNK)
                        while (failure.get() == null) {
                            val i = todo.poll() ?: break
                            r.seek(i.toLong() * CHUNK)
                            val n = len(i).toInt()
                            r.readFully(buf, 0, n)
                            Romm.uploadChunk(ctx, id, i, buf, n) { d -> progress(sent.addAndGet(d.toLong())) }
                            done += i; saveSession()
                        }
                    }
                } catch (e: Throwable) { failure.compareAndSet(null, e) }
            }
        }
        workers.forEach { it.join() }
        failure.get()?.let { e ->
            // the server forgot the session (expired, RomM restarted): start this game over once
            if (old != null && e.message?.contains("HTTP 404") == true) {
                sessionFile(ctx).delete(); return upload(ctx, platformId, name, f, onAssemble, progress)
            }
            throw e
        }
        onAssemble()
        Romm.uploadComplete(ctx, id)
        sessionFile(ctx).delete()
    }

    internal fun zipFolder(dir: File, zip: File) = ZipOutputStream(zip.outputStream().buffered()).use { z ->
        z.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
    }

    fun auto(ctx: Context) = Prefs.of(ctx).getBoolean("rommSaveSync", true)
    fun setAuto(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putBoolean("rommSaveSync", on).apply()
}
