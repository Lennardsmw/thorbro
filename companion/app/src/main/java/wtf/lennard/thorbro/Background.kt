package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Everything that runs in the background and the user should see wherever they are:
 * the offload queue (games to RomM, one after another), store downloads and the big RomM upload.
 * The Thor app shows it top right, the web panel too (GET /api/activity).
 */
object Background {
    // ---------- offload queue ----------
    class Offload(val paths: List<String>, val name: String, val size: Long)

    private val queue = LinkedBlockingQueue<Offload>()
    @Volatile private var worker: Thread? = null
    @Volatile var current: Offload? = null; private set
    @Volatile var currentJob: Jobs.Job? = null; private set
    /** counts of the batch in progress (reset when the queue runs empty and the result was shown) */
    @Volatile var batchTotal = 0; private set
    @Volatile var batchDone = 0; private set
    @Volatile var batchFreed = 0L; private set
    val batchErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var batchCancelled = 0; private set
    @Volatile var batchEndedAt = 0L; private set
    /** bumps whenever a game left the device; screens reload their list on change */
    @Volatile var changes = 0; private set

    fun queued(): List<Offload> = queue.toList()
    fun isQueued(path: String) = current?.paths?.contains(path) == true || queue.any { path in it.paths }

    @Synchronized
    fun offload(ctx: Context, paths: List<String>, name: String, size: Long) {
        if (paths.any { isQueued(it) }) return
        if (current == null && queue.isEmpty()) { batchTotal = 0; batchDone = 0; batchFreed = 0; batchErrors.clear(); batchEndedAt = 0; batchCancelled = 0; stopped = false }
        queue.add(Offload(paths, name, size)); batchTotal++
        if (worker?.isAlive != true) worker = thread(name = "thorbro-offload") { run(ctx.applicationContext) }
    }

    /** stop the running offload and drop the waiting ones; the local copy is only deleted after RomM confirmed, so a stop leaves everything as it was */
    @Volatile var stopped = false; private set
    @Synchronized
    fun cancel() {
        stopped = true
        batchTotal -= queue.size; queue.clear()
        currentJob?.stop = 2
    }

    private fun run(app: Context) {
        while (true) {
            val item = queue.poll() ?: break
            current = item
            val j = Jobs.start("In RomM auslagern") { job -> Library.offload(app, item.paths, job) }
            currentJob = j
            while (!j.done) {
                Progress.show(app, "In RomM auslagern ${batchDone + 1}/$batchTotal", j.progress, item.name)
                Thread.sleep(1000)
            }
            if (j.stop == 2 && j.error != null) { batchCancelled++ }
            else if (j.error != null) batchErrors += "${item.name}: ${j.error}"
            else { batchFreed += j.result?.optLong("freed") ?: 0; changes++ }
            batchDone++
        }
        current = null; currentJob = null; batchEndedAt = System.currentTimeMillis()
        Progress.done(app, if (stopped) "Auslagern gestoppt" else "Auslagern fertig", summary())
    }

    private fun summary(): String {
        val ok = batchDone - batchErrors.size - batchCancelled
        if (stopped && ok == 0 && batchErrors.isEmpty()) return "Abgebrochen – nichts gelöscht"
        return "$ok/$batchTotal ausgelagert, ${fmt(batchFreed)} frei" + (if (batchErrors.isNotEmpty()) " · ${batchErrors.size} Fehler: ${batchErrors.first()}" else "")
    }

    private fun fmt(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        else -> "${n shr 20} MB"
    }

    // ---------- store download batch ----------
    @Volatile private var dlBatch = setOf<Int>()

    fun downloadStarted(id: Int) {
        val running = Library.downloads.filterValues { !it.done }.keys
        dlBatch = if (running.isEmpty() || dlBatch.none { it in running }) setOf(id) else dlBatch + id
    }

    // ---------- what to show ----------
    private const val SHOW_DONE_MS = 8000L

    /** [{kind, title, name, progress, index, total, done, error}] */
    fun list(): JSONArray {
        val out = JSONArray()
        val now = System.currentTimeMillis()
        val job = currentJob
        val cur = current
        if (cur != null && job != null) out.put(JSONObject().put("kind", "offload").put("title", "In RomM auslagern")
            .put("name", cur.name).put("step", job.message).put("progress", job.progress).also { job.gameJson(it) }
            .put("index", batchDone + 1).put("total", batchTotal).put("done", false).put("stoppable", true).put("stopping", stopped))
        else if (batchEndedAt > 0 && now - batchEndedAt < SHOW_DONE_MS) out.put(JSONObject().put("kind", "offload").put("title", "Auslagern fertig")
            .put("name", summary()).put("progress", 1.0).put("index", batchDone).put("total", batchTotal).put("done", true)
            .put("error", batchErrors.firstOrNull()))

        val dl = dlBatch.mapNotNull { id -> Library.downloads[id]?.let { id to it } }
        if (dl.isNotEmpty()) {
            val active = dl.firstOrNull { !it.second.done }
            val finished = dl.count { it.second.done }
            val lastEnd = dl.maxOf { it.second.finishedAt }
            if (active != null) out.put(JSONObject().put("kind", "download").put("title", "Aus RomM laden")
                .put("name", active.second.message.ifEmpty { "…" }).put("progress", active.second.progress).also { active.second.gameJson(it) }
                .put("index", finished + 1).put("total", dl.size).put("done", false))
            else if (now - lastEnd < SHOW_DONE_MS) out.put(JSONObject().put("kind", "download").put("title", "Download fertig")
                .put("name", "${dl.count { it.second.error == null }}/${dl.size} auf dem Thor").put("progress", 1.0)
                .put("index", dl.size).put("total", dl.size).put("done", true).put("error", dl.firstNotNullOfOrNull { it.second.error }))
        }

        RommSync.current?.let { j ->
            if (!j.done || now - j.finishedAt < SHOW_DONE_MS) out.put(JSONObject().put("kind", "upload")
                .put("title", if (j.done) "Upload zu RomM fertig" else "Spiele zu RomM").put("name", j.message).put("progress", j.progress).also { if (!j.done) j.gameJson(it) }
                .put("index", j.index).put("total", j.total).put("done", j.done).put("error", j.error))
        }
        return out
    }
}
