package wtf.lennard.thorbro

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Unpacks .zip and .7z on the device, e.g. right after an upload. */
object Archives {
    fun isArchive(f: File) = f.name.lowercase().let { it.endsWith(".zip") || it.endsWith(".7z") }

    /** Extracts [archive] into [dest]; deletes the archive afterwards when [deleteAfter]. */
    fun extract(archive: File, dest: File, deleteAfter: Boolean, job: Jobs.Job): JSONObject {
        dest.mkdirs()
        val base = dest.canonicalPath
        var files = 0
        var bytes = 0L
        fun target(name: String): File? = File(dest, name).canonicalFile.takeIf { it.path.startsWith("$base/") } // zip-slip guard
        val name = archive.name.lowercase()
        if (name.endsWith(".zip")) {
            ZipFile(archive).use { z ->
                val entries = z.entries().toList()
                val total = entries.sumOf { maxOf(it.size, 0) }.coerceAtLeast(1)
                for (e in entries) {
                    val t = target(e.name) ?: continue
                    if (e.isDirectory) { t.mkdirs(); continue }
                    t.parentFile?.mkdirs()
                    job.message = e.name
                    z.getInputStream(e).use { i -> t.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    files++; bytes += t.length(); job.progress = bytes.toDouble() / total
                }
            }
        } else if (name.endsWith(".7z")) {
            SevenZFile.builder().setFile(archive).get().use { z ->
                val total = z.entries.sumOf { maxOf(it.size, 0) }.coerceAtLeast(1)
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val e = z.nextEntry ?: break
                    val t = target(e.name) ?: continue
                    if (e.isDirectory) { t.mkdirs(); continue }
                    t.parentFile?.mkdirs()
                    job.message = e.name
                    t.outputStream().use { o ->
                        while (true) { val n = z.read(buf); if (n < 0) break; o.write(buf, 0, n); bytes += n }
                    }
                    files++; job.progress = bytes.toDouble() / total
                }
            }
        } else error("Nur .zip und .7z werden unterstützt")
        if (deleteAfter) archive.delete()
        Roms.invalidate()
        return JSONObject().put("files", files).put("bytes", bytes).put("dest", dest.path)
    }
}
