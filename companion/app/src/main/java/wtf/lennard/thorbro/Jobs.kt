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
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("progress", progress)
            .put("message", message).put("done", done).put("error", error).put("result", result).put("index", index).put("total", total)
    }

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
