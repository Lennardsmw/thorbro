package wtf.lennard.thorbro

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Long-running work (extracting, exporting) runs in the background; clients poll GET /api/jobs/{id}. */
object Jobs {
    class Job(val id: Int, val name: String) {
        @Volatile var progress = 0.0
        @Volatile var message = ""
        @Volatile var done = false
        @Volatile var error: String? = null
        @Volatile var result: JSONObject? = null
        /** n/m for jobs that work through several games */
        @Volatile var index = 0
        @Volatile var total = 0
        @Volatile var finishedAt = 0L
        /** bytes moved so far / to move, for transfers; [transfer] turns them into "x / y · speed · time left" */
        /** the game being worked on, for the progress bubble (name, console, RomM id or local file for its cover) */
        @Volatile var game = ""
        @Volatile var system = ""
        @Volatile var romId = 0
        @Volatile var file = ""
        fun game(name: String, system: String, romId: Int = 0, file: String = "") {
            game = name; this.system = system; this.romId = romId; this.file = file; gameDone = 0; gameTotal = 0
        }
        /** game fields for Background.list */
        fun gameJson(o: JSONObject): JSONObject = o.put("game", game).put("system", system).put("romId", romId).put("file", file).put("transfer", transfer())
            .put("batch", batch()).put("gameProgress", gameProgress())
        @Volatile var bytesDone = 0L
        @Volatile var bytesTotal = 0L
        /** queue state for transfers: queued, running, paused (done/error are [done]/[error]) */
        @Volatile var state = "running"
        /** 0 = go on, 1 = pause (keep what is there), 2 = cancel (throw it away); read by [check] inside the work */
        @Volatile var stop = 0
        /** the work calls this regularly: throws [Stopped] when the user paused or cancelled */
        fun check() { if (stop != 0) throw Stopped(stop == 2) }
        // speed: smoothed over the last seconds, so the time left follows the real rate instead of the average since the start
        @Volatile private var lastT = 0L
        @Volatile private var lastB = 0L
        @Volatile private var ema = 0.0
        fun bytes(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (lastT == 0L) { lastT = now; lastB = done }
            else if (now - lastT >= 1000) {
                val inst = ((done - lastB) * 1000.0 / (now - lastT)).coerceAtLeast(0.0)
                ema = if (ema == 0.0) inst else ema * 0.6 + inst * 0.4
                lastT = now; lastB = done
            }
            bytesDone = done; bytesTotal = total
        }
        /** forget the speed (after a pause): the next samples start fresh */
        fun resetRate() { lastT = 0L; ema = 0.0 }
        /** the current game's own bytes; total -1 = all sent, the server is still busy with it (a batch's [bytesDone]/[bytesTotal] cover all its games) */
        @Volatile var gameDone = 0L
        @Volatile var gameTotal = 0L
        fun gameBytes(done: Long, total: Long) { gameDone = done; gameTotal = total }
        private fun bps(): Double = ema
        /** seconds left for the current game (or the batch when it has no own size), -1 = unknown yet */
        fun eta(): Long {
            val bps = bps(); if (bps <= 0) return -1
            val (done, total) = if (gameTotal > 0) gameDone.coerceAtMost(gameTotal) to gameTotal else bytesDone.coerceAtMost(bytesTotal) to bytesTotal
            return if (total > 0) ((total - done) / bps).toLong() else -1
        }
        fun speed(): Double = bps()
        /** this game: "12.0 MB/s · 545 MB / 1.14 GB · noch 50 s" */
        fun transfer(): String? {
            if (gameTotal < 0) return null // waiting for the server, nothing on the wire
            val (done, total) = if (gameTotal > 0) gameDone.coerceAtMost(gameTotal) to gameTotal else bytesDone.coerceAtMost(bytesTotal) to bytesTotal
            if (total <= 0) return null
            val bps = bps()
            val eta = if (bps > 0) ((total - done) / bps).toLong() else -1L
            return (if (bps > 0) "${"%.1f".format(bps / 1e6)} MB/s · " else "") + "${mb(done)} / ${mb(total)}" +
                (if (eta >= 0) " · noch ${dur(eta)}" else "")
        }
        /** the whole batch, only when it is more than this one game: "noch 163.3 GB · ca. 3 h 46 min" */
        fun batch(): String? {
            if (gameTotal == 0L || bytesTotal <= gameTotal) return null
            val left = (bytesTotal - bytesDone).coerceAtLeast(0)
            val bps = bps()
            return "noch ${mb(left)} gesamt" + (if (bps > 0) " · ca. ${dur((left / bps).toLong())}" else "")
        }
        fun gameProgress(): Double = if (gameTotal > 0) gameDone.toDouble() / gameTotal else if (gameTotal < 0) 1.0 else progress
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("progress", progress)
            .put("message", message).put("done", done).put("error", error).put("result", result).put("index", index).put("total", total)
            .put("bytesDone", bytesDone).put("bytesTotal", bytesTotal).put("transfer", transfer())
            .put("state", if (error != null) "error" else if (done) "done" else state).put("eta", eta()).put("speed", speed()).put("game", game).put("gameProgress", gameProgress())
    }

    private fun mb(n: Long) = if (n >= 1_000_000_000) "%.2f GB".format(n / 1e9) else "%.0f MB".format(n / 1e6)
    /** "45 s", "3 min", "1 h 05 min" */
    fun dur(s: Long) = when { s >= 3600 -> "${s / 3600} h ${"%02d".format(s % 3600 / 60)} min"; s >= 60 -> "${s / 60} min ${"%02d".format(s % 60)} s"; else -> "$s s" }

    /** thrown inside a transfer when the user paused ([cancel] false) or cancelled it */
    class Stopped(val cancel: Boolean) : RuntimeException(if (cancel) "Abgebrochen" else "Pausiert")

    private val next = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, Job>()

    /** a job that does not run yet (a queued transfer); the queue gives it its work later */
    fun create(name: String): Job {
        val j = Job(next.getAndIncrement(), name)
        jobs[j.id] = j
        if (jobs.size > 50) jobs.keys.sorted().take(jobs.size - 50).forEach { k -> jobs[k]?.takeIf { it.done }?.let { jobs.remove(k) } }
        return j
    }

    fun start(name: String, work: (Job) -> JSONObject): Job {
        val j = create(name)
        thread(name = "job-${j.id}") {
            try { j.result = work(j); j.progress = 1.0 } catch (e: Throwable) { j.error = e.message ?: e.toString() }
            j.finishedAt = System.currentTimeMillis(); j.done = true
        }
        return j
    }

    fun get(id: Int) = jobs[id]
}
