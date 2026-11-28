package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device library vs. RomM: which games and saves RomM already has, moving a game to RomM to free space
 * ("auslagern"), and the store of games that are only in RomM.
 */
object Library {
    private const val TTL = 120_000L
    @Volatile private var cache: Pair<Long, Remote>? = null

    /** RomM's library: platform ids by slug, and per platform fs_name -> rom json */
    class Remote(val bySlug: Map<String, Int>, val roms: Map<Int, List<JSONObject>>) {
        /** by file name, the same rule the upload uses (extension and case may differ) */
        fun find(system: String, file: String): JSONObject? {
            val list = roms[bySlug[RommSync.slugFor(system)] ?: return null] ?: return null
            val name = RommSync.rommName(file, list.map { it.optString("fs_name") }) ?: return null
            return list.firstOrNull { it.optString("fs_name") == name }
        }
    }

    fun remote(ctx: Context, refresh: Boolean = false): Remote {
        val c = cache
        if (!refresh && c != null && System.currentTimeMillis() - c.first < TTL) return c.second
        val plats = Romm.rawPlatforms(ctx)
        val r = Remote(plats.associate { it.optString("fs_slug") to it.getInt("id") },
            plats.filter { it.optInt("rom_count") > 0 }.associate { it.getInt("id") to Romm.rawRoms(ctx, it.getInt("id")) })
        cache = System.currentTimeMillis() to r
        return r
    }

    fun invalidate() { cache = null }

    private fun connected(ctx: Context) = Romm.status(ctx).optBoolean("connected")

    /**
     * Per local game path: {inRomm, romId, uploaded, save} with uploaded = sent from this Thor, RomM has not scanned it yet; save = "none" (no save found), "synced" (RomM has the
     * current one), "pending" (changed since the last upload) or "local" (never uploaded).
     */
    fun status(ctx: Context, refresh: Boolean = false): JSONObject {
        val out = JSONObject().put("connected", connected(ctx))
        if (!out.getBoolean("connected")) return out.put("games", JSONObject())
        val rem = try { remote(ctx, refresh) } catch (e: Exception) { return out.put("error", e.message).put("games", JSONObject()) }
        val st = RommSync.state(ctx)
        val led = RommSync.ledger(ctx)
        val saves = runCatching { RommSync.localSaves(ctx) }.getOrDefault(emptyList()).groupBy { it.rom.optString("path") }
        val games = JSONObject()
        val sys = Roms.scan(ctx, false).getJSONArray("systems")
        for (i in 0 until sys.length()) {
            val s = sys.getJSONObject(i); val id = s.getString("id")
            if (id == "_unassigned") continue
            val roms = s.getJSONArray("roms")
            for (j in 0 until roms.length()) {
                val r = roms.getJSONObject(j)
                val hit = rem.find(id, r.getString("file"))
                val mine = saves[r.getString("path")].orEmpty()
                val save = when {
                    mine.isEmpty() -> "none"
                    mine.all { st.optJSONObject(RommSync.stateKey(ctx, it))?.optString("local") == runCatching { RommSync.md5(it.file) }.getOrNull() } -> "synced"
                    mine.any { st.has(RommSync.stateKey(ctx, it)) } -> "pending"
                    else -> "local"
                }
                games.put(r.getString("path"), JSONObject().put("inRomm", hit != null).put("romId", hit?.optInt("id")).put("save", save)
                    .put("uploaded", hit == null && RommSync.uploadedByUs(id, r, led)))
            }
        }
        return out.put("games", games)
    }

    /**
     * Moves games to RomM and deletes them on the device. Steps per game: upload when RomM lacks it,
     * upload changed saves, check that RomM has the game (same size for single files) and, when the game has
     * a save, that it is synced. Only then the local files are deleted. Saves themselves stay on the device.
     */
    fun offload(ctx: Context, paths: List<String>, job: Jobs.Job): JSONObject {
        if (!connected(ctx)) throw IllegalStateException("RomM ist nicht verbunden")
        if (!Romm.hasScope(ctx, "roms.write")) throw IllegalStateException("ThorBro darf in RomM nichts hochladen (neu koppeln)")
        val scan = Roms.scan(ctx, true).getJSONArray("systems")
        val all = (0 until scan.length()).map { scan.getJSONObject(it) }.flatMap { s ->
            s.getJSONArray("roms").let { a -> (0 until a.length()).map { s.getString("id") to a.getJSONObject(it) } }
        }
        val items = paths.mapNotNull { p -> all.firstOrNull { it.second.getString("path") == p } }
        if (items.isEmpty()) throw IllegalArgumentException("Spiel nicht gefunden")
        if (items.any { it.first == "ports" }) throw IllegalArgumentException("Ports sind Apps und lassen sich nicht auslagern")
        val total = items.sumOf { it.second.getLong("size") }.coerceAtLeast(1)
        var done = 0L
        var rem = remote(ctx, true)
        for ((sys, rom) in items) {
            job.check()
            val f = File(rom.getString("path"))
            job.game(rom.optString("name").ifEmpty { f.nameWithoutExtension }, sys, file = f.path)
            if (rem.find(sys, f.name) != null) { done += rom.getLong("size"); continue }
            val slug = RommSync.slugFor(sys)
            val pid = rem.bySlug[slug] ?: Romm.createPlatform(ctx, slug)
            job.message = "Lade ${f.name} hoch"
            if (f.isDirectory) {
                val zip = File(ctx.cacheDir, "offload.zip")
                RommSync.zipFolder(f, zip)
                try { RommSync.upload(ctx, pid, f.name + ".zip", zip) { n -> job.check(); job.progress = 0.9 * (done + n) / total; job.bytes(done + n, total); job.gameBytes(n, zip.length()) } } finally { zip.delete() }
            } else RommSync.upload(ctx, pid, f.name, f) { n -> job.check(); job.progress = 0.9 * (done + n) / total; job.bytes(done + n, total); job.gameBytes(n, f.length()) }
            done += rom.getLong("size")
        }
        job.check()
        job.message = "Spielstände hochladen"; job.progress = 0.92
        val sync = runCatching { RommSync.syncSaves(ctx, job) }.getOrElse { JSONObject().put("errors", JSONArray().put(it.message)) }
        // RomM scans uploads in the background; give it a moment before checking
        job.message = "Prüfe RomM"
        var missing = items
        for (attempt in 0 until 10) {
            rem = remote(ctx, true)
            missing = items.filter { (sys, rom) ->
                val hit = rem.find(sys, rom.getString("file")) ?: return@filter true
                !rom.optBoolean("dir") && hit.optLong("fs_size_bytes") > 0 && hit.optLong("fs_size_bytes") != rom.getLong("size")
            }
            if (missing.isEmpty()) break
            Thread.sleep(3000); job.check()
        }
        if (missing.isNotEmpty()) throw IllegalStateException("RomM hat ${missing.joinToString { it.second.getString("file") }} noch nicht vollständig – nichts gelöscht")
        val st = status(ctx, false).getJSONObject("games")
        val unsaved = items.filter { st.optJSONObject(it.second.getString("path"))?.optString("save") in setOf("pending", "local") }
        if (unsaved.isNotEmpty()) throw IllegalStateException("Spielstand von ${unsaved.first().second.getString("name")} ist nicht in RomM " +
            "(${sync.optJSONArray("errors")?.optString(0) ?: "Spiel in RomM noch nicht erkannt"}) – nichts gelöscht")
        job.check() // last chance to stop: after this the local copies go
        var freed = 0L
        for ((_, rom) in items) {
            val f = File(rom.getString("path"))
            freed += rom.getLong("size")
            if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (wtf.lennard.thorbro.ui.Favorites.isFav(ctx, f.path)) wtf.lennard.thorbro.ui.Favorites.toggle(ctx, f.path)
        }
        Roms.invalidate(); invalidate()
        items.forEach { (sys, rom) -> History.add(ctx, "offload", rom.optString("name"), sys, rom.optLong("size").toString()) }
        Logs.i("library", "ausgelagert: ${items.joinToString { it.second.getString("file") }} (${freed shr 20} MB)")
        return JSONObject().put("freed", freed).put("games", items.size)
    }

    /** games RomM has that are not on the device, grouped by console */
    fun store(ctx: Context, refresh: Boolean): JSONObject {
        if (!connected(ctx)) return JSONObject().put("connected", false).put("systems", JSONArray())
        val rem = remote(ctx, refresh)
        val plats = Romm.rawPlatforms(ctx).associateBy { it.getInt("id") }
        val local = Roms.scan(ctx, false).getJSONArray("systems").let { s ->
            (0 until s.length()).flatMap { i -> s.getJSONObject(i).getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("file") } } }.toSet()
        }
        val systems = JSONArray()
        for ((pid, roms) in rem.roms) {
            val p = plats[pid] ?: continue
            if (p.optString("fs_slug") == "ports") continue
            val missing = roms.filter { r -> val n = r.optString("fs_name"); n !in local && n.removeSuffix(".zip") !in local }
            if (missing.isEmpty()) continue
            systems.put(JSONObject().put("system", Romm.systemFor(p.optString("fs_slug")))
                .put("name", p.optString("display_name").ifEmpty { p.optString("name") })
                .put("games", JSONArray(missing.sortedBy { it.optString("name").lowercase() }.map { r ->
                    JSONObject().put("id", r.getInt("id")).put("name", r.optString("name").ifEmpty { r.optString("fs_name_no_tags") })
                        .put("file", r.optString("fs_name")).put("size", r.optLong("fs_size_bytes"))
                        .put("hasCover", r.optString("path_cover_small").isNotEmpty())
                })))
        }
        return JSONObject().put("connected", true).put("systems", systems)
    }

    /** cover of a RomM game as a cached file (for the native UI) */
    fun coverFile(ctx: Context, romId: Int): File? {
        val f = File(ctx.cacheDir, "romm-covers/$romId.img")
        if (f.exists() && f.length() > 0) return f
        val b = runCatching { Romm.cover(ctx, romId) }.getOrNull() ?: return null
        f.parentFile?.mkdirs(); f.writeBytes(b)
        return f
    }

    /** where downloaded/received games go: the folder picked in Settings, else the ROMs folder the scan found (SD preferred), else internal ROMs */
    fun romsRoot(ctx: Context): File =
        Prefs.of(ctx).getString("roms.root", null)?.let(::File)?.takeIf { it.isDirectory }
            ?: (Roms.knownRoots() ?: Roms.scan(ctx, false).optJSONArray("romRoots").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } })
                .map(::File).lastOrNull()
            ?: File(Storage.primary, "ROMs")

    /** the ROM folders to pick from: per storage volume the one the scan found, else <volume>/ROMs (created when picked) */
    fun romsRootChoices(ctx: Context): List<Pair<String, File>> {
        val found = Roms.scan(ctx, false).optJSONArray("romRoots")?.let { a -> (0 until a.length()).map { File(a.getString(it)) } }.orEmpty()
        val vols = Storage.volumes(ctx)
        val out = (0 until vols.length()).map { i ->
            val v = vols.getJSONObject(i); val dir = File(v.getString("path"))
            v.optString("label", dir.name) to (found.firstOrNull { it.path.startsWith(dir.path + "/") } ?: File(dir, "ROMs"))
        }
        val cur = romsRoot(ctx)
        return if (out.any { it.second.path == cur.path }) out else out + ("Eigener Ordner" to cur)
    }

    /** Settings: new ROM folder for downloads and received games; null = ok */
    fun setRomsRoot(ctx: Context, path: String): String? {
        val f = Storage.resolve(path) ?: return "Ungültiger Pfad"
        if (!f.isDirectory && !f.mkdirs()) return "Ordner lässt sich nicht anlegen"
        Prefs.of(ctx).edit().putString("roms.root", f.path).apply()
        Roms.invalidate()
        return null
    }

    /** running store downloads by RomM id, so every screen shows the same progress */
    val downloads = java.util.concurrent.ConcurrentHashMap<Int, Jobs.Job>()

    fun download(ctx: Context, romId: Int): Jobs.Job {
        downloads[romId]?.takeIf { !it.done && it.stop == 0 }?.let { return it }
        val app = ctx.applicationContext
        val j = Jobs.start("RomM-Download") { job ->
            val t0 = System.currentTimeMillis()
            job.romId = romId
            try { Romm.download(app, romId, romsRoot(app), job).also { invalidate()
                History.add(app, "download", it.optString("name").ifEmpty { job.message }, it.optString("system"),
                    ms = System.currentTimeMillis() - t0, romId = romId, size = job.bytesTotal) } }
            finally { Progress.done(app, "Download fertig", job.message) }
        }
        downloads[romId] = j
        Background.downloadStarted(romId)
        return j
    }
}
