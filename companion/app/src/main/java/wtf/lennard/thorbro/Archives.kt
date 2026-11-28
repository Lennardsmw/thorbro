package wtf.lennard.thorbro

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.util.zip.ZipFile

/** Unpacks .zip and .7z (commons-compress) and .rar / RAR5 (libarchive) on the device, e.g. right after an upload. */
object Archives {
    fun isArchive(f: File) = f.name.lowercase().let { it.endsWith(".zip") || it.endsWith(".7z") || it.endsWith(".rar") }

    /** the files an extract wrote at the top of dest (for sorting them into console folders) */
    private fun topLevel(dest: File, written: Collection<File>): List<File> {
        val base = dest.canonicalFile
        return written.mapNotNull { f -> generateSequence(f.canonicalFile) { it.parentFile }.firstOrNull { it.parentFile == base } }.distinct()
    }

    /** Extracts [archive] into [dest]; deletes the archive afterwards when [deleteAfter]. */
    /** [sort] = put games that end up loose in the ROM folder into their console folder ([Discs.place]) */
    fun extract(archive: File, dest: File, deleteAfter: Boolean, job: Jobs.Job, sort: android.content.Context? = null): JSONObject {
        dest.mkdirs()
        val base = dest.canonicalPath
        var files = 0
        var bytes = 0L
        val written = mutableListOf<File>()
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
                    job.check(); job.message = e.name
                    written += t
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
                    written += t
                    t.outputStream().use { o ->
                        while (true) { job.check(); val n = z.read(buf); if (n < 0) break; o.write(buf, 0, n); bytes += n }
                    }
                    files++; job.progress = bytes.toDouble() / total
                }
            }
        } else {
            val r = Rar.extract(archive, dest, base, job)
            files = r.first; bytes = r.second; written += r.third
        }
        if (deleteAfter) archive.delete()
        Roms.invalidate()
        val placed = sort?.let { Discs.place(it, topLevel(dest, written)) } ?: emptyList()
        return JSONObject().put("files", files).put("bytes", bytes).put("dest", dest.path)
            .put("paths", org.json.JSONArray(placed.map { it.path }))
    }

    /** RAR / RAR5 (and whatever else libarchive reads) */
    private object Rar {
        fun extract(archive: File, dest: File, base: String, job: Jobs.Job): Triple<Int, Long, List<File>> {
            val total = archive.length().coerceAtLeast(1)
            val a = Archive.readNew()
            val written = mutableListOf<File>()
            var files = 0; var bytes = 0L
            try {
                Archive.readSupportFilterAll(a); Archive.readSupportFormatAll(a)
                Archive.readOpenFileName(a, archive.path.toByteArray(), 1L shl 20)
                val buf = ByteBuffer.allocateDirect(4 shl 20)
                val arr = ByteArray(4 shl 20)
                while (true) {
                    job.check()
                    val e = Archive.readNextHeader(a)
                    if (e == 0L) break
                    val name = ArchiveEntry.pathnameUtf8(e) ?: String(ArchiveEntry.pathname(e) ?: break)
                    val t = File(dest, name).canonicalFile.takeIf { it.path.startsWith("$base/") } ?: continue // zip-slip guard
                    when (ArchiveEntry.filetype(e)) {
                        ArchiveEntry.AE_IFDIR -> { t.mkdirs(); continue }
                        ArchiveEntry.AE_IFREG -> {}
                        else -> continue // links, devices: skipped
                    }
                    t.parentFile?.mkdirs()
                    job.message = name
                    val part = File(t.path + ".thorbro-part")
                    written += t
                    FileOutputStream(part).use { o ->
                        while (true) {
                            job.check()
                            buf.clear()
                            Archive.readData(a, buf)
                            val n = buf.position()
                            if (n <= 0) break
                            buf.flip(); buf.get(arr, 0, n)
                            o.write(arr, 0, n); bytes += n
                            // progress by how far into the archive file libarchive has read
                            val read = runCatching { Archive.filterBytes(a, -1) }.getOrDefault(0L)
                            job.progress = (read.toDouble() / total).coerceIn(0.0, 0.999); job.bytes(read, total); job.gameBytes(read, total)
                        }
                        o.fd.sync()
                    }
                    if (t.exists()) t.delete()
                    if (!part.renameTo(t)) error("Umbenennen ging nicht: $name")
                    files++
                }
            } catch (s: Jobs.Stopped) {
                dest.walkBottomUp().filter { it.name.endsWith(".thorbro-part") }.forEach { it.delete() }
                throw s
            } catch (x: me.zhanghai.android.libarchive.ArchiveException) {
                error("Entpacken: ${x.message ?: "Archiv kaputt oder nicht unterstützt"}")
            } finally { runCatching { Archive.readFree(a) } }
            return Triple(files, bytes, written)
        }
    }
}
