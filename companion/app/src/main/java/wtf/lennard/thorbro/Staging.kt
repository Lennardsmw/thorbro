package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Uploads to a slow SD card in two steps: the Mac sends at full speed into a staging folder on internal storage,
 * then the [TransferQueue] item "move" puts it into place (copy to the SD, or unpack straight from the staging copy)
 * and deletes the staging copy. The Mac is done right away; the slow part runs on the Thor and shows on the
 * Downloads page (native + web) with progress, cancel and retry. A restart goes on with the files not moved yet.
 * The same item also unpacks an archive in place (upload without staging), so every "einspielen" shows up there.
 */
object Staging {
    val dir: File get() = File(Storage.primary, "Download/ThorBro/.staging")
    private const val MARGIN = 1_000_000_000L // leave internal storage at least this much

    /** where an upload of [size] bytes into [target] should go first: staging when the target is on another volume */
    fun plan(target: File, size: Long): JSONObject {
        val onPrimary = target.path == Storage.primary.path || target.path.startsWith(Storage.primary.path + "/")
        val free = Storage.free(Storage.primary)
        val stage = !onPrimary && free > size + MARGIN
        return JSONObject().put("stage", stage).put("staging", dir.path)
            .put("reason", when { onPrimary -> "intern"; !stage -> "intern zu voll"; else -> "SD langsam" })
    }

    private fun walk(f: File): List<File> = if (f.isDirectory) f.listFiles().orEmpty().sortedBy { it.name }.flatMap(::walk) else listOf(f)

    /**
     * Runs item [e]: src = staged file/folder (or an archive in place), dest = target folder, extract, overwrite.
     * Moving keeps the folder layout below src's parent: src = …/.staging/<id>/Game → dest/Game.
     */
    fun start(ctx: Context, e: JSONObject): Jobs.Job {
        val app = ctx.applicationContext
        return Jobs.start(if (e.optBoolean("extract")) "Entpacken" else "Einspielen") { job ->
            val src = File(e.getString("src"))
            val dest = File(e.getString("dest")).apply { mkdirs() }
            val overwrite = e.optBoolean("overwrite")
            job.game(e.optString("name"), e.optString("system"))
            if (!src.exists()) {
                // gone: done earlier (restart right after the last file) or removed by hand
                return@start JSONObject().put("path", dest.path)
            }
            val staged = src.path.startsWith(dir.path + "/")
            val stageRoot = if (staged) src.parentFile!! else null
            if (e.optBoolean("extract") && src.isFile && Archives.isArchive(src)) {
                job.message = "entpacke ${src.name}"
                Archives.extract(src, dest, deleteAfter = true, job = job, sort = app)
            } else if (staged) {
                val files = walk(src)
                val total = files.sumOf { it.length() }.coerceAtLeast(1)
                var done = 0L
                Transfers.begin(app)
                try {
                    for (f in files) {
                        val rel = f.relativeTo(stageRoot!!).path
                        val t = File(dest, rel)
                        if (t.exists()) {
                            if (t.length() == f.length() && !overwrite) { done += f.length(); f.delete(); continue }
                            if (!overwrite) error("$rel gibt es schon (andere Größe). \"Überschreiben\" aktivieren.")
                        }
                        t.parentFile?.mkdirs()
                        val part = File(t.path + ".thorbro-part")
                        val from = if (part.exists() && part.length() <= f.length()) part.length() else 0L
                        job.message = rel
                        FileInputStream(f).use { inp ->
                            inp.channel.position(from)
                            FileOutputStream(part, from > 0).use { out ->
                                val buf = ByteArray(4 shl 20)
                                var n0 = from
                                while (true) {
                                    job.check()
                                    val n = inp.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); n0 += n
                                    val now = done + n0
                                    job.progress = now.toDouble() / total; job.bytes(now, total); job.gameBytes(now, total)
                                }
                                out.fd.sync()
                            }
                        }
                        if (t.exists()) t.delete()
                        if (!part.renameTo(t)) error("Umbenennen ging nicht: $rel")
                        done += f.length()
                        f.delete() // a restart goes on with what is left
                    }
                } catch (s: Jobs.Stopped) {
                    throw s // the .thorbro-part stays: "Neu starten" goes on from there; "Entfernen" leaves the staging copy
                } finally { Transfers.end() }
            }
            stageRoot?.deleteRecursively()
            // loose in the ROM folder (no console chosen): into the console folder the file says
            Discs.place(app, dest.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && it.lastModified() >= job.startedAt })
            Roms.invalidate()
            History.add(app, "upload", e.optString("name"), e.optString("system"), size = e.optLong("size"))
            Progress.done(app, "Eingespielt", e.optString("name"))
            JSONObject().put("path", dest.path)
        }
    }

    /** removing an item from the list also drops its staging copy (cancelled for good) */
    fun discard(e: JSONObject) {
        val src = File(e.optString("src"))
        if (src.path.startsWith(dir.path + "/")) src.parentFile?.deleteRecursively()
    }
}
