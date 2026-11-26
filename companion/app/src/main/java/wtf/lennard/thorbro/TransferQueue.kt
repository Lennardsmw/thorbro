package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * One queue for everything that goes over the network in big pieces: store downloads (RomM → Thor) and uploads (Thor → RomM).
 * Jobs run one after another (downloads up to [parallel] at once, 1–2; uploads always one), can be paused (the part on disk /
 * the upload session stays, resuming goes on from there) and cancelled, and survive a restart of the app: what is waiting or
 * paused is written to the preferences and picked up again by [restore].
 */
object TransferQueue {
    class Item(val id: String, val kind: String, var title: String, val system: String, val romId: Int,
               val only: Set<String>?, val systems: Set<String>?, val withSaves: Boolean, val size: Long, val added: Long) {
        /** queued, running, paused, done, error */
        @Volatile var state = "queued"
        @Volatile var error: String? = null
        @Volatile var finishedAt = 0L
        @Volatile var job: Jobs.Job? = null
        val download get() = kind == "download"
        fun json(): JSONObject {
            val j = job
            val o = JSONObject().put("id", id).put("kind", kind).put("title", j?.game?.takeIf { it.isNotEmpty() } ?: title).put("system", system)
                .put("romId", romId).put("size", size).put("state", state).put("error", error).put("added", added)
                .put("progress", j?.gameProgress() ?: 0.0).put("jobId", j?.id ?: 0)
            if (j != null && state == "running") {
                o.put("transfer", j.transfer()).put("eta", j.eta()).put("speed", j.speed()).put("step", j.message)
                    .put("index", j.index).put("total", j.total)
            }
            return o
        }
    }

    private val items = java.util.Collections.synchronizedList(mutableListOf<Item>())
    private var seq = 0
    @Volatile private var restored = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var ticker: Thread? = null

    /** counts of the batch in progress per kind, reset when the kind runs dry (for "2/5" in the activity card) */
    class Batch { @Volatile var total = 0; @Volatile var done = 0; @Volatile var failed = 0; @Volatile var endedAt = 0L; @Volatile var lastError: String? = null }
    private val batches = mapOf("download" to Batch(), "upload" to Batch())
    fun batch(kind: String) = batches.getValue(kind)

    fun parallel(ctx: Context) = Prefs.of(ctx).getInt("transfers.parallel", 1).coerceIn(1, 2)
    fun setParallel(ctx: Context, n: Int) { Prefs.of(ctx).edit().putInt("transfers.parallel", n.coerceIn(1, 2)).apply(); pump(ctx.applicationContext) }
    private fun limit(ctx: Context, kind: String) = if (kind == "download") parallel(ctx) else 1

    fun all(): List<Item> = synchronized(items) { items.toList() }
    fun running(kind: String? = null) = all().filter { it.state == "running" && (kind == null || it.kind == kind) }
    fun waiting(kind: String? = null) = all().filter { it.state == "queued" && (kind == null || it.kind == kind) }
    fun paused(kind: String? = null) = all().filter { it.state == "paused" && (kind == null || it.kind == kind) }
    fun find(id: String) = all().firstOrNull { it.id == id }
    val busy get() = all().any { it.state == "running" || it.state == "queued" }

    // ---------- adding ----------

    /** a game from RomM onto the Thor; the job is there at once (progress 0, state queued) so every screen can show it */
    fun enqueueDownload(ctx: Context, romId: Int, title: String = "", size: Long = 0): Jobs.Job = synchronized(this) {
        val app = ctx.applicationContext; appCtx = app
        all().firstOrNull { it.download && it.romId == romId && it.state != "done" && it.state != "error" }?.job?.let { return it }
        val it = Item("d${++seq}-${System.currentTimeMillis()}", "download", title.ifEmpty { "Spiel $romId" }, "", romId, null, null, false, size, System.currentTimeMillis())
        attach(it)
        Library.downloads[romId] = it.job!!
        Background.downloadStarted(romId)
        add(app, it)
        it.job!!
    }

    /** games from the Thor to RomM: [only] = these local paths (one game), else a sync of [systems] (null = all) */
    fun enqueueUpload(ctx: Context, title: String, only: Set<String>?, systems: Set<String>?, withSaves: Boolean, size: Long = 0): Jobs.Job = synchronized(this) {
        val app = ctx.applicationContext; appCtx = app
        all().firstOrNull { it.kind == "upload" && it.state != "done" && it.state != "error" && it.only == only && (only != null || it.systems == systems) }?.job?.let { return it }
        val it = Item("u${++seq}-${System.currentTimeMillis()}", "upload", title, "", 0, only, systems, withSaves, size, System.currentTimeMillis())
        attach(it)
        RommSync.current = it.job
        add(app, it)
        it.job!!
    }

    private fun attach(it: Item) {
        val j = Jobs.create(if (it.download) "RomM-Download" else if (it.only != null) "Zu RomM hochladen" else "RomM-Abgleich")
        j.state = it.state; j.message = it.title
        if (it.download) j.romId = it.romId
        it.job = j
    }

    private fun add(app: Context, it: Item) {
        val b = batch(it.kind)
        if (waiting(it.kind).isEmpty() && running(it.kind).isEmpty()) { b.total = 0; b.done = 0; b.failed = 0; b.endedAt = 0; b.lastError = null }
        b.total++
        items.add(it)
        save(app)
        pump(app)
    }

    // ---------- control ----------

    fun pause(id: String) {
        val it = find(id) ?: return
        when (it.state) {
            "running" -> it.job?.stop = 1 // the worker notices and parks it
            "queued" -> { it.state = "paused"; it.job?.state = "paused"; appCtx?.let { save(it); pump(it) } }
        }
    }

    fun resume(id: String) {
        val it = find(id) ?: return
        if (it.state == "paused" || it.state == "error") {
            it.error = null; it.state = "queued"
            it.job?.let { j -> j.state = "queued"; j.stop = 0; j.error = null; j.done = false }
            appCtx?.let { save(it); pump(it) }
        }
    }

    fun cancel(id: String) {
        val it = find(id) ?: return
        val app = appCtx
        if (it.state == "running") { it.job?.stop = 2; return } // the worker cleans up and removes it
        remove(it, app, "Abgebrochen")
        if (it.download) removePart(app, it)
    }

    private fun removePart(app: Context?, it: Item) {
        // a paused download has its part on disk; delete it so a cancel really frees the space
        if (app == null || it.romId == 0) return
        runCatching {
            val r = Romm.romInfo(app, it.romId)
            val dir = java.io.File(Library.romsRoot(app), Romm.systemFor(r.optString("platform_fs_slug")))
            val name = r.getString("fs_name") + if (r.optBoolean("has_multiple_files")) ".zip" else ""
            java.io.File(dir, "$name.thorbro-part").delete()
        }
    }

    private fun remove(it: Item, app: Context?, why: String) {
        items.remove(it)
        it.job?.let { j -> j.error = why; j.done = true; j.finishedAt = System.currentTimeMillis(); j.state = "error" }
        if (it.download) Library.downloads.remove(it.romId)
        val b = batch(it.kind); if (b.total > 0) b.total--
        if (app != null) { save(app); pump(app) }
    }

    /** drop finished and failed items from the list */
    fun clearFinished() { items.removeAll { it.state == "done" || it.state == "error" } }

    // ---------- running ----------

    @Synchronized
    private fun pump(app: Context) {
        val old = System.currentTimeMillis() - 10 * 60_000
        items.removeAll { it.state == "done" && it.finishedAt in 1 until old }
        for (kind in listOf("download", "upload")) {
            var free = limit(app, kind) - running(kind).size
            for (it in waiting(kind)) { if (free <= 0) break; free--; launch(app, it) }
        }
        if (ticker?.isAlive != true && busy) ticker = thread(name = "transfer-ticker", isDaemon = true) { tick(app) }
    }

    private fun launch(app: Context, it: Item) {
        val j = it.job ?: Jobs.create(it.title).also { n -> it.job = n }
        j.stop = 0; j.done = false; j.error = null; j.state = "running"; j.resetRate()
        it.state = "running"
        if (!it.download) RommSync.current = j
        save(app)
        thread(name = "transfer-${it.id}") {
            try {
                val res = if (it.download) download(app, it, j) else upload(app, it, j)
                j.result = res; j.progress = 1.0
                val errs = res.optJSONArray("errors")
                it.error = errs?.takeIf { e -> e.length() > 0 }?.getString(0)
                it.state = if (it.error != null) "error" else "done"
                finish(it, j, it.error)
            } catch (s: Jobs.Stopped) {
                if (s.cancel) remove(it, app, "Abgebrochen")
                else { it.state = "paused"; j.state = "paused"; j.stop = 0; j.resetRate(); save(app) }
            } catch (e: Throwable) {
                Logs.e("transfer", "${it.kind} ${it.title}", e)
                it.error = e.message ?: e.toString(); it.state = "error"
                finish(it, j, it.error)
            } finally {
                if (it.state == "done" || it.state == "error") save(app)
                pump(app)
            }
        }
    }

    private fun finish(it: Item, j: Jobs.Job, err: String?) {
        j.error = err; j.finishedAt = System.currentTimeMillis(); j.done = true; j.state = if (err != null) "error" else "done"
        it.finishedAt = j.finishedAt
        val b = batch(it.kind)
        b.done++; if (err != null) { b.failed++; b.lastError = err }
        b.endedAt = j.finishedAt
    }

    private fun download(app: Context, it: Item, job: Jobs.Job): JSONObject {
        val t0 = System.currentTimeMillis()
        job.romId = it.romId
        return Romm.download(app, it.romId, Library.romsRoot(app), job).also { r ->
            Library.invalidate()
            History.add(app, "download", r.optString("name").ifEmpty { job.message }, r.optString("system"),
                ms = System.currentTimeMillis() - t0, romId = it.romId, size = job.bytesTotal)
        }
    }

    private fun upload(app: Context, it: Item, job: Jobs.Job): JSONObject {
        job.message = "Verbinde mit RomM …"
        Transfers.begin(app)
        try { return RommSync.run(app, it.systems, it.withSaves, job, it.only) } finally { Transfers.end() }
    }

    /** the progress notification while anything runs; lives as long as the queue is busy */
    private fun tick(app: Context) {
        var any = false
        while (busy) {
            any = true
            val run = running()
            run.firstOrNull()?.let { it ->
                val j = it.job
                val left = waiting().size
                Progress.show(app, (if (it.download) "Aus RomM laden" else "Zu RomM hochladen") + (if (left > 0) " (+$left)" else ""),
                    j?.gameProgress() ?: 0.0, listOfNotNull(j?.game?.takeIf { g -> g.isNotEmpty() } ?: it.title, j?.transfer()).joinToString(" · "))
            }
            runCatching { Thread.sleep(1500) }
        }
        if (any) Progress.done(app, "Übertragungen fertig", summary())
    }

    fun summary(): String {
        val d = batch("download"); val u = batch("upload")
        return listOfNotNull(
            d.total.takeIf { it > 0 }?.let { "${d.done - d.failed}/$it geladen" },
            u.total.takeIf { it > 0 }?.let { "${u.done - u.failed}/$it hochgeladen" },
        ).joinToString(" · ") + (listOf(d, u).firstNotNullOfOrNull { it.lastError }?.let { " · Fehler: $it" } ?: "")
    }

    // ---------- persistence ----------

    private fun save(app: Context) {
        val a = JSONArray()
        all().filter { it.state == "queued" || it.state == "running" || it.state == "paused" }.forEach {
            a.put(JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.job?.game?.takeIf { g -> g.isNotEmpty() } ?: it.title)
                .put("romId", it.romId).put("only", it.only?.let { s -> JSONArray(s.toList()) }).put("systems", it.systems?.let { s -> JSONArray(s.toList()) })
                .put("withSaves", it.withSaves).put("size", it.size).put("added", it.added).put("paused", it.state == "paused"))
        }
        Prefs.of(app).edit().putString("transfers.queue", a.toString()).apply()
    }

    private fun strs(a: JSONArray?): Set<String>? = a?.let { x -> (0 until x.length()).map { x.getString(it) }.toSet() }

    /** at app start: what was waiting, running or paused before the restart comes back (running goes to the queue's front) */
    @Synchronized
    fun restore(ctx: Context) {
        if (restored) return
        restored = true
        val app = ctx.applicationContext; appCtx = app
        val a = runCatching { JSONArray(Prefs.of(app).getString("transfers.queue", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until a.length()) runCatching {
            val o = a.getJSONObject(i)
            val it = Item(o.getString("id"), o.getString("kind"), o.optString("title"), "", o.optInt("romId"), strs(o.optJSONArray("only")),
                strs(o.optJSONArray("systems")), o.optBoolean("withSaves"), o.optLong("size"), o.optLong("added"))
            it.state = if (o.optBoolean("paused")) "paused" else "queued"
            attach(it)
            if (it.download) Library.downloads[it.romId] = it.job!!
            else if (RommSync.current == null) RommSync.current = it.job
            items.add(it)
            batch(it.kind).total++
        }
        Logs.i("transfer", "Warteschlange wiederhergestellt: ${items.size} Aufträge")
        pump(app)
    }

    // ---------- for the screens ----------

    fun json(ctx: Context): JSONObject {
        val list = JSONArray(); all().forEach { list.put(it.json()) }
        return JSONObject().put("items", list).put("parallel", parallel(ctx)).put("summary", summary())
    }
}
