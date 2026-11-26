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
    internal fun stateFile(ctx: Context) = File(ctx.filesDir, "romm-save-state.json")
    internal fun state(ctx: Context): JSONObject = runCatching { JSONObject(stateFile(ctx).readText()) }.getOrDefault(JSONObject())
    /** sync state is per profile: the same save file holds a different profile's save after a swap */
    internal fun stateKey(ctx: Context, s: SaveItem): String {
        val o = Profiles.owner(ctx, s.rom.optString("path"))
        return if (o == Profiles.MAIN) s.file.path else "${s.file.path}#$o"
    }
    /** RomM saves of the profile that is live for this save (its slot) */
    internal fun slotSaves(ctx: Context, romId: Int, s: SaveItem): List<JSONObject> {
        val slot = Profiles.slot(ctx, Profiles.owner(ctx, s.rom.optString("path")))
        return Romm.listSaves(ctx, romId).filter { Romm.slotOf(it) == slot }
    }
    internal fun remoteKey(s: JSONObject) = "${s.getInt("id")}@${s.optString("updated_at")}"
    private fun parseTime(t: String): Long? = runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(t.take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    /**
     * is [s] the RomM save we saw at the last sync? Same id and same second: the upload answer and the save list
     * write the time differently (fractions, offset), so comparing the raw strings re-downloaded our own uploads
     */
    internal fun sameRemote(prev: String?, s: JSONObject): Boolean {
        if (prev.isNullOrEmpty()) return false
        val id = prev.substringBefore('@'); val t = prev.substringAfter('@', "")
        if (id != s.getInt("id").toString()) return false
        val a = parseTime(t); val b = parseTime(s.optString("updated_at"))
        return if (a != null && b != null) a / 1000 == b / 1000 else t == s.optString("updated_at")
    }
    internal fun remoteTime(s: JSONObject): Long = runCatching { java.time.OffsetDateTime.parse(s.getString("updated_at")).toInstant().toEpochMilli() }
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
    /** "Game (EU).3ds" -> "game (eu)": a short extension off, lowercase; folders without one stay as they are */
    private fun baseOf(name: String): String =
        name.lowercase().let { n -> val e = n.substringAfterLast('.', ""); if (e.length in 1..5 && e.all { it.isLetterOrDigit() }) n.dropLast(e.length + 1) else n }

    /** RomM's name for the local game [file], or null. Same game also when the name differs only by extension or case:
     *  folders go up zipped ("X" -> "X.zip") and RomM keeps some single files as a folder ("X.3ds" -> "X") */
    fun rommName(file: String, names: Collection<String>): String? =
        names.firstOrNull { it == file } ?: baseOf(file).let { b -> names.firstOrNull { baseOf(it) == b } }

    /** not in RomM, not uploaded by us already, and not an empty leftover file */
    private fun missing(rom: JSONObject, slug: String, have: Map<String, Int>, led: JSONObject): Boolean {
        val file = rom.getString("file"); val size = rom.optLong("size")
        return size > 0 && rommName(file, have.keys) == null && led.optLong("$slug/$file", -1L) != size
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
            .put("canWrite", Romm.hasScope(ctx, "roms.write")).put("canFirmware", Romm.hasScope(ctx, "firmware.write")).put("auto", auto(ctx))
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
            // Switch keys + firmware from Eden, for RomBro on the Mac; unchanged files cost only a fingerprint
            job.message = "Switch-Keys & Firmware"
            val fw = SwitchFirmware.sync(ctx)
            fw.optString("error").takeIf { it.isNotEmpty() }?.let { errors.put("Switch-Firmware: $it") }
        }
        return JSONObject().put("romsUploaded", romsUp).put("savesUploaded", savesUp).put("savesDownloaded", savesDown).put("errors", errors)
    }

    /** uploads changed saves into the user's RomM account; games RomM lacks are skipped */
    @Synchronized
    fun syncSaves(ctx: Context, job: Jobs.Job?, onlyRom: String? = null, force: Boolean = false): JSONObject {
        if (!DataSaver.rommAllowed(ctx)) return JSONObject().put("uploaded", 0).put("skippedNoGame", 0).put("errors", JSONArray()).put("off", true)
        val (bySlug, remoteFiles) = remote(ctx, maxAgeMs = if (onlyRom != null) 30 * 60_000L else 0)
        val st = state(ctx)
        var n = 0; val errors = JSONArray(); var skipped = 0
        for (s in localSaves(ctx).filter { onlyRom == null || it.rom.optString("path") == onlyRom }) {
            val hash = md5(s.file)
            val key = stateKey(ctx, s)
            val prev = st.optJSONObject(key)
            if (!force && prev?.optString("local") == hash) continue
            val romId = bySlug[slugFor(s.system)]?.let { remoteFiles[it]?.get(s.rom.getString("file")) }
                ?: (if (onlyRom != null) SharedSaves.romId(ctx, s.system, onlyRom) else null)
            if (romId == null) { skipped++; continue }
            // RomM has a save we have not seen yet (another device): only upload when ours is newer
            if (s.isFolder && !force) {
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
     * Fetches newer saves from RomM (e.g. played on the Mac) for folder saves (ports/recomps, Switch) and,
     * through [pullFileSaves], for single-file saves.
     * Newest wins: when RomM has a different save that is newer than the local files, the local state is
     * backed up first and the RomM one is unpacked into the save folder (and its ".work" twin if present).
     */
    @Synchronized
    fun pullSaves(ctx: Context): JSONObject {
        if (!DataSaver.rommAllowed(ctx)) return JSONObject().put("downloaded", 0).put("errors", JSONArray()).put("off", true)
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
                pullFolder(ctx, s, newest)
                n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}"); Logs.e("romm", "Spielstand holen ${s.file.path}", e) }
        }
        uploadedFile(ctx).writeText(up.toString())
        n += pullFileSaves(ctx, bySlug, remoteFiles, errors)
        return JSONObject().put("downloaded", n).put("errors", errors)
    }

    /** writes RomM save [newest] (a zip) into the folder save [s]; local state is backed up first */
    internal fun pullFolder(ctx: Context, s: SaveItem, newest: JSONObject) {
        val zip = File(ctx.cacheDir, "romm-save-${newest.getInt("id")}.zip")
        try {
            Romm.downloadSave(ctx, newest.getInt("id"), zip)
            runCatching { Saves.backup(ctx, null, label = "vor-romm") } // only changed locations; unchanged ones already have a matching snapshot
            runCatching { GameSave.backup(ctx, s.rom.optString("path"), GameSave.Reason.CLOUD, listOf(s)) }
            val targets = listOf(s.file) + listOf(File(s.file.path + ".work")).filter { it.isDirectory }
            targets.forEach { unzipStripped(zip, it) }
        } finally { zip.delete() }
        val st2 = state(ctx)
        st2.put(stateKey(ctx, s), JSONObject().put("local", md5(s.file)).put("remote", remoteKey(newest)))
        History.add(ctx, "save_down", s.rom.optString("name"), s.system)
        stateFile(ctx).writeText(st2.toString())
    }

    /**
     * the newest RomM save of the live profile for one game. [s] = the local save (folder or file) if there is one:
     * folders take the newest save of the slot, files only saves with the ROM's or the file's base name and a save extension
     */
    internal fun cloudNewest(ctx: Context, romId: Int, rom: JSONObject, s: SaveItem?): JSONObject? {
        val owner = Profiles.owner(ctx, rom.optString("path"))
        val slot = Profiles.slot(ctx, owner)
        val saves = Romm.listSaves(ctx, romId)
        // the first profile also sees saves without a slot (RomBro on the Mac, RomM's web upload) – they are this game's saves too
        val all = saves.filter { Romm.slotOf(it) == slot }.ifEmpty {
            if (owner == Profiles.MAIN) saves.filter { Romm.slotOf(it) == null } else emptyList()
        }
        if (s != null && s.isFolder) return all.maxByOrNull { remoteTime(it) }
        val romBase = rom.optString("file").substringBeforeLast('.').lowercase()
        val saveBase = s?.file?.name?.substringBeforeLast('.')?.lowercase() ?: romBase
        val files = all.filter { r -> remoteBaseExt(r)?.first?.let { it == romBase || it == saveBase } == true }
        // the list is already this game's (per RomM id): if no name matches (GameCube .gci / memory cards, other names), the newest one counts
        return files.ifEmpty { all }.maxByOrNull { remoteTime(it) }
    }

    private val saveExtOnly = Regex("(?i)^(sav|srm|dsv|eep|fla|sra|mpk|mcr)$")

    /** RomM file name without its upload-time tag, split into lowercase base name and extension */
    internal fun remoteBaseExt(s: JSONObject): Pair<String, String>? {
        val name = SharedSaves.cleanName(s.optString("file_name")).trim()
        val ext = name.substringAfterLast('.', "")
        if (!saveExtOnly.matches(ext)) return null // zips (folder saves), states, …
        return name.substringBeforeLast('.').lowercase() to ext.lowercase()
    }

    /**
     * Single-file saves (.srm/.sav/…): RomBro on the Mac uploads "Game.srm", the Thor may keep "Game.sav" –
     * same content, so any RomM save with the ROM's base name and a save extension counts and is written into
     * the local file under its local name. Same rules as folders: only when RomM has something new, the newer
     * side wins (30 s tolerance), backup first. A save missing locally is created only when the system's other
     * saves show one clear place (folder + extension) for it.
     */
    private fun pullFileSaves(ctx: Context, bySlug: Map<String, Int>, remoteFiles: Map<Int, Map<String, Int>>, errors: JSONArray): Int {
        var n = 0
        val local = localSaves(ctx).filter { !it.isFolder }
        val romIdOf = { sys: String, rom: JSONObject -> bySlug[slugFor(sys)]?.let { remoteFiles[it]?.get(rom.getString("file")) } }
        fun matching(saves: List<JSONObject>, romBase: String, saveBase: String) = saves.filter { r ->
            remoteBaseExt(r)?.first?.let { it == romBase || it == saveBase } == true
        }
        // one request per platform instead of one per game: the periodic pull runs every 15 minutes
        val perPlatform = mutableMapOf<Int, List<JSONObject>?>()
        fun platformSaves(sys: String): List<JSONObject>? {
            val pid = bySlug[slugFor(sys)] ?: return null
            return perPlatform.getOrPut(pid) { runCatching { Romm.listPlatformSaves(ctx, pid) }.onFailure { Logs.e("romm", "Spielstände von $sys", it) }.getOrNull() }
        }
        fun ofRom(saves: List<JSONObject>, romId: Int, rom: JSONObject): List<JSONObject> {
            val slot = Profiles.slot(ctx, Profiles.owner(ctx, rom.optString("path")))
            return saves.filter { it.optInt("rom_id") == romId && Romm.slotOf(it) == slot }
        }
        // 1) saves that exist on the Thor
        val haveRoms = mutableSetOf<String>()
        for (s in local) {
            haveRoms += s.rom.optString("path")
            val romId = romIdOf(s.system, s.rom) ?: continue
            try {
                val romBase = s.rom.getString("file").substringBeforeLast('.').lowercase()
                val all = platformSaves(s.system) ?: continue
                val newest = matching(ofRom(all, romId, s.rom), romBase, s.file.name.substringBeforeLast('.').lowercase())
                    .maxByOrNull { remoteTime(it) } ?: continue
                if (pullFile(ctx, s, newest)) n++
            } catch (e: Exception) { errors.put("${s.file.name}: ${e.message}"); Logs.e("romm", "Spielstand holen ${s.file.path}", e) }
        }
        // 2) saves RomM has for local ROMs that have none on the Thor yet
        val places = local.groupBy { it.system }.mapValues { (_, l) -> l.map { placeOf(it) }.distinct() }
        val systems = localSystems(ctx).toMap()
        for ((sys, pl) in places) {
            val place = pl.singleOrNull() ?: continue // several folders/extensions in use: unclear where it belongs
            val remoteSaves = platformSaves(sys) ?: continue
            if (remoteSaves.isEmpty()) continue
            for (rom in systems[sys].orEmpty()) {
                if (rom.optString("path") in haveRoms) continue
                val romFile = File(rom.optString("path"))
                if (!romFile.isFile) continue
                val romId = romIdOf(sys, rom) ?: continue
                val base = rom.getString("file").substringBeforeLast('.')
                val dir = place.first ?: romFile.parentFile ?: continue
                val target = File(dir, "$base.${place.second}")
                if (target.exists()) continue // e.g. other case/name variant: leave it to the next scan
                val s = SaveItem(target, sys, rom, false)
                val newest = ofRom(remoteSaves, romId, rom)
                    .let { matching(it, base.lowercase(), base.lowercase()) }.maxByOrNull { remoteTime(it) } ?: continue
                try { if (pullFile(ctx, s, newest)) n++ }
                catch (e: Exception) { errors.put("${target.name}: ${e.message}"); Logs.e("romm", "Spielstand anlegen ${target.path}", e) }
            }
        }
        return n
    }

    /** where a file save lives: (null = next to its ROM | fixed folder) + extension */
    private fun placeOf(s: SaveItem): Pair<File?, String> {
        val ext = s.file.name.substringAfterLast('.').lowercase()
        val romDir = File(s.rom.optString("path")).parentFile
        return (if (romDir != null && s.file.parentFile?.path.equals(romDir.path, true)) null else s.file.parentFile) to ext
    }

    /** downloads [remote] into the file save [s] if RomM is newer; true when the local file changed */
    /** [force]: write the RomM save even when it is the one seen last time or the local file is newer (the player asked for it) */
    internal fun pullFile(ctx: Context, s: SaveItem, remote: JSONObject, force: Boolean = false): Boolean {
        val st = state(ctx)
        val key = stateKey(ctx, s)
        val prev = st.optJSONObject(key)
        if (!force && sameRemote(prev?.optString("remote"), remote)) return false
        val exists = s.file.isFile
        val localHash = if (exists) md5(s.file) else null
        if (!force && exists && localHash != prev?.optString("local")) { // both changed (or first sync): the newer one wins
            if (remoteTime(remote) <= s.file.lastModified() + 30_000) return false // local is newer: syncSaves uploads it
        }
        val tmp = File(ctx.cacheDir, "romm-save-${remote.getInt("id")}.bin")
        try {
            Romm.downloadSave(ctx, remote.getInt("id"), tmp)
            val newHash = md5(tmp)
            if (newHash != localHash) {
                if (exists) {
                    runCatching { GameSave.backup(ctx, s.rom.optString("path"), GameSave.Reason.CLOUD, listOf(s)) }
                    runCatching { Saves.backup(ctx, null, label = "vor-romm") }
                    // the file may sit outside every backup location (e.g. .srm next to SNES ROMs): keep a copy of it too
                    runCatching {
                        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                        s.file.copyTo(File(Saves.backupRoot(), "romm-files/${s.system}/$ts-vor-romm-${s.file.name}"))
                    }
                }
                tmp.inputStream().use { SaveFs.write(s.file, it) }
                val from = SharedSaves.cleanName(remote.optString("file_name"))
                History.add(ctx, "save_down", s.rom.optString("name"), s.system,
                    if (from.equals(s.file.name, true)) s.file.name else "$from → ${s.file.name}")
            }
            val st2 = state(ctx)
            st2.put(key, JSONObject().put("local", newHash).put("remote", remoteKey(remote)))
            stateFile(ctx).writeText(st2.toString())
            return newHash != localHash
        } finally { tmp.delete() }
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
    fun setAuto(ctx: Context, on: Boolean) { Prefs.of(ctx).edit().putBoolean("rommSaveSync", on).apply(); DataSaver.onAutoChanged(ctx, on) }
}
