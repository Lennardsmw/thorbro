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
    @Volatile var batchEndedAt = 0L; private set
    /** bumps whenever a game left the device; screens reload their list on change */
    @Volatile var changes = 0; private set

    fun queued(): List<Offload> = queue.toList()
    fun isQueued(path: String) = current?.paths?.contains(path) == true || queue.any { path in it.paths }

    @Synchronized
    fun offload(ctx: Context, paths: List<String>, name: String, size: Long) {
        if (paths.any { isQueued(it) }) return
        if (current == null && queue.isEmpty()) { batchTotal = 0; batchDone = 0; batchFreed = 0; batchErrors.clear(); batchEndedAt = 0 }
        queue.add(Offload(paths, name, size)); batchTotal++
        if (worker?.isAlive != true) worker = thread(name = "thorbro-offload") { run(ctx.applicationContext) }
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
            if (j.error != null) batchErrors += "${item.name}: ${j.error}"
            else { batchFreed += j.result?.optLong("freed") ?: 0; changes++ }
            batchDone++
        }
        current = null; currentJob = null; batchEndedAt = System.currentTimeMillis()
        Progress.done(app, "Auslagern fertig", summary())
    }

    private fun summary(): String {
        val ok = batchDone - batchErrors.size
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
            .put("index", batchDone + 1).put("total", batchTotal).put("done", false))
        else if (batchEndedAt > 0 && now - batchEndedAt < SHOW_DONE_MS) out.put(JSONObject().put("kind", "offload").put("title", "Auslagern fertig")
            .put("name", summary()).put("progress", 1.0).put("index", batchDone).put("total", batchTotal).put("done", true)
            .put("error", batchErrors.firstOrNull()))

        // downloads and uploads come from the transfer queue: every running one is a card, paused ones too, "+n wartend" on the first
        for (kind in listOf("download", "upload")) {
            val b = TransferQueue.batch(kind)
            val running = TransferQueue.running(kind)
            val paused = TransferQueue.paused(kind)
            val waiting = TransferQueue.waiting(kind).size
            val title = if (kind == "download") "Aus RomM laden" else "Spiele zu RomM"
            running.forEachIndexed { i, it ->
                val j = it.job ?: return@forEachIndexed
                out.put(JSONObject().put("kind", kind).put("title", title).put("name", j.message.ifEmpty { it.title }).put("step", j.message)
                    .put("progress", j.progress).also { o -> j.gameJson(o) }.put("eta", j.eta())
                    .put("index", b.done + 1 + i).put("total", b.total).put("waiting", if (i == 0) waiting else 0).put("done", false).put("id", it.id))
            }
            paused.forEach {
                val j = it.job ?: return@forEach
                out.put(JSONObject().put("kind", kind).put("title", "Pausiert").put("name", it.json().optString("title")).put("progress", j.gameProgress())
                    .put("game", it.json().optString("title")).put("romId", it.romId).put("index", 1).put("total", 1).put("done", false).put("state", "paused").put("id", it.id))
            }
            if (running.isEmpty() && paused.isEmpty() && waiting == 0 && b.total > 0 && b.endedAt > 0 && now - b.endedAt < SHOW_DONE_MS) {
                val ok = b.done - b.failed
                out.put(JSONObject().put("kind", kind).put("title", if (kind == "download") "Download fertig" else "Upload zu RomM fertig")
                    .put("name", if (kind == "download") "$ok/${b.total} auf dem Thor" else "$ok/${b.total} in RomM").put("progress", 1.0)
                    .put("index", b.total).put("total", b.total).put("done", true).put("error", b.lastError))
            }
        }
        return out
    }
}
