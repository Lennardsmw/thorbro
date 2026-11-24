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
        @Volatile var bytesDone = 0L
        @Volatile var bytesTotal = 0L
        @Volatile private var rateFrom: Pair<Long, Long>? = null
        fun bytes(done: Long, total: Long) {
            if (rateFrom == null) rateFrom = System.currentTimeMillis() to done
            bytesDone = done; bytesTotal = total
        }
        fun transfer(): String? {
            val total = bytesTotal.takeIf { it > 0 } ?: return null
            val done = bytesDone.coerceAtMost(total)
            val (at, from) = rateFrom ?: return null
            val ms = System.currentTimeMillis() - at
            val bps = if (ms > 1500) (done - from) * 1000.0 / ms else 0.0
            val eta = if (bps > 0) ((total - done) / bps).toLong() else -1L
            return "${mb(done)} / ${mb(total)}" + (if (bps > 0) " · ${"%.1f".format(bps / 1e6)} MB/s" else "") +
                (if (eta >= 0) " · noch ${dur(eta)}" else "")
        }
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("progress", progress)
            .put("message", message).put("done", done).put("error", error).put("result", result).put("index", index).put("total", total)
            .put("bytesDone", bytesDone).put("bytesTotal", bytesTotal).put("transfer", transfer())
    }

    private fun mb(n: Long) = if (n >= 1_000_000_000) "%.2f GB".format(n / 1e9) else "%.0f MB".format(n / 1e6)
    /** "45 s", "3 min", "1 h 05 min" */
    fun dur(s: Long) = when { s >= 3600 -> "${s / 3600} h ${"%02d".format(s % 3600 / 60)} min"; s >= 60 -> "${s / 60} min ${"%02d".format(s % 60)} s"; else -> "$s s" }

    private val next = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun start(name: String, work: (Job) -> JSONObject): Job {
        val j = Job(next.getAndIncrement(), name)
        jobs[j.id] = j
        if (jobs.size > 50) jobs.keys.sorted().take(jobs.size - 50).forEach { jobs.remove(it) }
        thread(name = "job-${j.id}") {
            try { j.result = work(j); j.progress = 1.0 } catch (e: Throwable) { j.error = e.message ?: e.toString() }
            j.finishedAt = System.currentTimeMillis(); j.done = true
        }
        return j
    }

    fun get(id: Int) = jobs[id]
}
