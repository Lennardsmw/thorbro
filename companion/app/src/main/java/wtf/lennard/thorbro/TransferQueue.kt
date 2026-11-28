package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One queue for store downloads and RomM uploads: one transfer at a time (full bandwidth, no fighting),
 * kept in the prefs so it goes on after a restart. A download resumes where it stopped (.thorbro-part + Range).
 * "Für später" items wait until they are started (Downloads page, native and web).
 */
object TransferQueue {
    private const val KEY = "transfers.queue"
    private const val IGNORED = "transfers.sweepIgnored"
    private val lock = Any()
    @Volatile private var worker: Thread? = null
    /** the item running right now and its job (progress, speed, time left) */
    @Volatile var current: JSONObject? = null
        private set
    @Volatile var currentJob: Jobs.Job? = null
        private set
    @Volatile var app: Context? = null

    private fun load(ctx: Context): JSONArray = runCatching { JSONArray(Prefs.of(ctx).getString(KEY, "[]")) }.getOrDefault(JSONArray())
    private fun save(ctx: Context, a: JSONArray) = Prefs.of(ctx).edit().putString(KEY, a.toString()).apply()
    private fun items(ctx: Context): MutableList<JSONObject> = load(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    private fun store(ctx: Context, l: List<JSONObject>) = save(ctx, JSONArray(l))

    /** a store game → download queue; [later] = keep it on the list until started */
    fun addDownload(ctx: Context, romId: Int, name: String, size: Long, system: String, later: Boolean = false): JSONObject = synchronized(lock) {
        val l = items(ctx)
        l.firstOrNull { it.optString("kind") == "download" && it.optInt("romId") == romId }?.let { e ->
            if (!later) { e.put("later", false).remove("error") ; store(ctx, l); kick(ctx) }
            return e
        }
        val e = JSONObject().put("id", "d$romId").put("kind", "download").put("romId", romId).put("name", name).put("size", size)
            .put("system", system).put("later", later).put("added", System.currentTimeMillis())
        l += e; store(ctx, l); if (!later) kick(ctx); e
    }

    /** a local game (base + updates/DLC) → upload queue */
    fun addUpload(ctx: Context, paths: Set<String>, name: String, size: Long, system: String, later: Boolean = false): JSONObject = synchronized(lock) {
        val id = "u" + paths.sorted().joinToString("|").hashCode().toUInt().toString(16)
        val l = items(ctx)
        l.firstOrNull { it.optString("id") == id }?.let { e ->
            if (!later) { e.put("later", false).remove("error"); store(ctx, l); kick(ctx) }
            return e
        }
        val e = JSONObject().put("id", id).put("kind", "upload").put("paths", JSONArray(paths.toList())).put("name", name).put("size", size)
            .put("system", system).put("later", later).put("added", System.currentTimeMillis())
        l += e; store(ctx, l); if (!later) kick(ctx); e
    }

    /** "Upload per Link": the Thor loads [url] itself ([system] "" = guess from the file, "apk", a console id; [dir] = a folder instead) */
    fun addUrl(ctx: Context, url: String, system: String, dir: String, extract: Boolean?, later: Boolean = false): JSONObject = synchronized(lock) {
        val id = "l" + url.hashCode().toUInt().toString(16)
        val l = items(ctx)
        l.firstOrNull { it.optString("id") == id }?.let { e ->
            if (!later) { e.put("later", false).remove("error"); e.remove("cancelled"); store(ctx, l); kick(ctx) }
            return e
        }
        val e = JSONObject().put("id", id).put("kind", "url").put("url", url).put("name", UrlDownload.nameOf(url)).put("size", 0L)
            .put("system", system).put("dir", dir).put("extract", extract ?: JSONObject.NULL).put("later", later).put("added", System.currentTimeMillis())
        l += e; store(ctx, l); if (!later) kick(ctx); e
    }

    /** an upload that still has to be put into place (staged → SD card, or an archive to unpack): "Einspielen" */
    fun addMove(ctx: Context, src: String, dest: String, name: String, size: Long, system: String, extract: Boolean, overwrite: Boolean, quiet: Boolean = false): JSONObject? = synchronized(lock) {
        val id = "m" + src.hashCode().toUInt().toString(16)
        val l = items(ctx)
        // quiet = found by Discs.sweep: leave an existing (failed, cancelled) entry alone, skip what the user removed
        if (quiet && (Prefs.of(ctx).getStringSet(IGNORED, emptySet())!!.contains(src) || l.any { it.optString("id") == id })) return null
        l.firstOrNull { it.optString("id") == id }?.let { e -> e.put("later", false).remove("error"); e.remove("cancelled"); store(ctx, l); kick(ctx); return e }
        val e = JSONObject().put("id", id).put("kind", "move").put("src", src).put("dest", dest).put("name", name).put("size", size)
            .put("system", system).put("extract", extract).put("overwrite", overwrite).put("later", false).put("added", System.currentTimeMillis())
        l += e; store(ctx, l); kick(ctx); e
    }

    /** a game found in the Download folder: "bereit zum Installieren" (later) until clicked, or right away with [auto] */
    fun addFound(ctx: Context, f: java.io.File, auto: Boolean): JSONObject? = synchronized(lock) {
        val id = "f" + f.path.hashCode().toUInt().toString(16)
        val l = items(ctx)
        if (l.any { it.optString("id") == id }) return null
        val e = JSONObject().put("id", id).put("kind", "found").put("src", f.path).put("name", Discs.cleanName(f.name)).put("size", f.length())
            .put("system", "").put("later", !auto).put("added", System.currentTimeMillis())
        l += e; store(ctx, l); if (auto) kick(ctx); e
    }

    private fun update(ctx: Context, id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val l = items(ctx); l.firstOrNull { it.optString("id") == id }?.let(f); store(ctx, l)
    }

    /** auto-install switched on: everything waiting in the inbox goes */
    fun startFound(ctx: Context) = synchronized(lock) {
        val l = items(ctx); l.filter { it.optString("kind") == "found" && it.optBoolean("later") }.forEach { it.put("later", false) }
        store(ctx, l); kick(ctx)
    }

    /** start a "für später" or failed item (or all with [id] = null) */
    fun start(ctx: Context, id: String?) = synchronized(lock) {
        val l = items(ctx)
        l.filter { id == null || it.optString("id") == id }.forEach { it.put("later", false).remove("error"); it.remove("cancelled") }
        store(ctx, l); kick(ctx)
    }

    /**
     * Stops an item: a waiting one is just marked, the running one is stopped for real (the download stream ends and its
     * .thorbro-part is deleted, the upload ends). It stays in the list as "abgebrochen" until removed; the queue goes on.
     */
    fun cancel(ctx: Context, id: String) = synchronized(lock) {
        val l = items(ctx)
        l.firstOrNull { it.optString("id") == id }?.put("cancelled", true)?.remove("error")
        store(ctx, l)
        if (current?.optString("id") == id) { cancelRequested = id; currentJob?.stop = 2 }
    }
    @Volatile private var cancelRequested: String? = null

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        if (current?.optString("id") == id) return@synchronized // the running one finishes (a download keeps its .part for later)
        val l = items(ctx)
        l.filter { it.optString("id") == id && it.optString("kind") == "found" }.forEach { Inbox.ignore(ctx, it.optString("src")) }
        l.filter { it.optString("id") == id && it.optString("kind") == "move" }.forEach {
            Staging.discard(it)
            // removed by hand: the sweep should not bring it back
            val p = Prefs.of(ctx); p.edit().putStringSet(IGNORED, p.getStringSet(IGNORED, emptySet())!! + it.optString("src")).apply()
        }
        store(ctx, l.filter { it.optString("id") != id })
    }

    fun isQueued(ctx: Context, romId: Int) = items(ctx).any { it.optString("kind") == "download" && it.optInt("romId") == romId }
    fun isQueuedPath(ctx: Context, path: String) = items(ctx).any { e -> e.optJSONArray("paths")?.let { a -> (0 until a.length()).any { a.getString(it) == path } } == true }

    /** everything for the Downloads page / web: items with state (läuft, wartet, später, Fehler) and the running one's transfer line */
    fun list(ctx: Context): JSONArray {
        val cur = current?.optString("id")
        val j = currentJob
        return JSONArray(items(ctx).map { e ->
            val o = JSONObject(e.toString())
            o.put("state", when { e.optBoolean("cancelled") -> "cancelled"; e.optString("id") == cur -> "running"; e.has("error") -> "error"; e.optBoolean("later") -> "later"; else -> "waiting" })
            if (e.optString("id") == cur && j != null) o.put("progress", j.gameProgress()).put("transfer", j.transfer())
            o
        })
    }

    /** one short line for the bottom bar: "⬇ Name · 45 % · noch 2 min · 2 weitere" (null = nothing running) */
    fun line(ctx: Context): Pair<String, Float>? {
        val e = current ?: return null
        val j = currentJob ?: return null
        val waiting = items(ctx).count { it.optString("id") != e.optString("id") && !it.optBoolean("later") && !it.has("error") && !it.optBoolean("cancelled") }
        val eta = j.transfer()?.substringAfter("noch ", "")?.takeIf { it.isNotEmpty() }
        val p = j.gameProgress().toFloat().coerceIn(0f, 1f)
        val arrow = when (e.optString("kind")) { "upload" -> "⬆ RomM"; "move" -> "📦 Einspielen"; "found" -> "📥 Installieren"; else -> "⬇" }
        return listOfNotNull("$arrow ${e.optString("name")}", "${(p * 100).toInt()} %", eta?.let { "noch $it" },
            if (waiting > 0) "$waiting weitere" else null).joinToString(" · ") to p
    }

    /** after a restart: go on with what was queued */
    fun resume(ctx: Context) {
        kick(ctx)
        kotlin.concurrent.thread(isDaemon = true) { runCatching { Discs.sweep(ctx.applicationContext) }; runCatching { Inbox.cleanup(ctx.applicationContext) } }
        runCatching { Inbox.start(ctx) }
    }

    private fun kick(ctx: Context) {
        val a = ctx.applicationContext; app = a
        if (worker?.isAlive == true) return
        worker = kotlin.concurrent.thread(name = "transfers", isDaemon = true) { loop(a) }
    }

    private fun loop(ctx: Context) {
        while (true) {
            val next = synchronized(lock) { items(ctx).firstOrNull { !it.optBoolean("later") && !it.has("error") && !it.optBoolean("cancelled") } } ?: break
            current = next; cancelRequested = null
            val err = runCatching { run(ctx, next) }.fold({ it }, { it.message ?: "Fehler" })
            val wasCancelled = cancelRequested == next.optString("id")
            synchronized(lock) {
                val l = items(ctx)
                val e = l.firstOrNull { it.optString("id") == next.optString("id") }
                when {
                    wasCancelled || e?.optBoolean("cancelled") == true -> e?.put("cancelled", true)?.remove("error")
                    err == null -> l.removeAll { it.optString("id") == next.optString("id") }
                    else -> e?.put("error", err)
                }
                store(ctx, l)
            }
            current = null; currentJob = null; cancelRequested = null
        }
    }

    /** runs one item to the end; null = done, else the error */
    private fun run(ctx: Context, e: JSONObject): String? {
        val job = when (e.optString("kind")) {
            "download" -> Library.download(ctx, e.optInt("romId"))
            "move" -> Staging.start(ctx, e)
            "found" -> Inbox.install(ctx, e)
            "url" -> UrlDownload.start(ctx, e) { name -> e.put("name", name); update(ctx, e.optString("id")) { it.put("name", name).put("file", e.optString("file")) } }
            else -> {
                val a = e.optJSONArray("paths") ?: return "keine Dateien"
                val paths = (0 until a.length()).map { a.getString(it) }.filter { java.io.File(it).exists() }.toSet()
                if (paths.isEmpty()) return null // gone (offloaded / deleted): nothing left to send
                // a running RomM batch (Abgleich) first: wait for it, then send ours
                while (RommSync.current?.let { !it.done } == true && RommSync.current?.file !in paths) {
                    if (cancelRequested == e.optString("id")) return "Abgebrochen"
                    Thread.sleep(1000)
                }
                RommSync.uploadGame(ctx, paths)
            }
        }
        currentJob = job
        if (cancelRequested == e.optString("id")) job.stop = 2 // cancelled in the moment it started
        while (!job.done) Thread.sleep(500)
        val res = job.result
        return job.error ?: res?.optJSONArray("errors")?.takeIf { it.length() > 0 }?.optString(0)
    }
}
