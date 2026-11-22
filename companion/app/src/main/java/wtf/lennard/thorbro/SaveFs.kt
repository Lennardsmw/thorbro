package wtf.lennard.thorbro

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes into save folders. Android 13 lets ThorBro read other apps' Android/data folders (all-files access)
 * but not write there (EACCES), e.g. Banana Native's saves. Then the file is written to a staging folder on
 * shared storage first and copied into place with shell rights through Shizuku.
 */
object SaveFs {
    private val staging: File get() = File(Storage.primary, "ThorBro/.staging").apply { if (!isDirectory) { mkdirs(); File(this, ".nomedia").createNewFile() } }

    private fun sh(script: String, what: String) {
        val r = Shell.exec("sh", "-c", script)
            ?: throw IOException("$what: kein Schreibzugriff (Android sperrt den Ordner) – Shizuku starten und ThorBro erlauben (Shizuku: ${Shell.state()})")
        if (r.first != 0) throw IOException("$what: ${r.second.ifEmpty { "Shell-Fehler ${r.first}" }}")
    }

    /** stream into [f]; directly when possible, else via staging + shell copy when the stream is closed */
    fun out(f: File): OutputStream {
        f.parentFile?.mkdirs()
        try {
            return FileOutputStream(f)
        } catch (e: FileNotFoundException) {
            if (f.isDirectory) throw e
        }
        val tmp = File.createTempFile("save", ".tmp", staging)
        return object : FilterOutputStream(FileOutputStream(tmp)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() {
                if (closed) return
                closed = true
                super.close()
                try {
                    val p = f.parentFile?.path ?: "/"
                    sh("mkdir -p ${Shell.q(p)} && cat ${Shell.q(tmp.path)} > ${Shell.q(f.path)}", f.name)
                } finally { tmp.delete() }
            }
        }
    }

    fun write(f: File, input: InputStream) = out(f).use { input.copyTo(it) }

    fun copy(src: File, dst: File) = src.inputStream().use { write(dst, it) }

    fun mkdirs(d: File) { if (!d.mkdirs() && !d.isDirectory) sh("mkdir -p ${Shell.q(d.path)}", d.name) }

    fun delete(f: File) {
        if (!f.exists()) return
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (f.exists()) sh("rm -rf ${Shell.q(f.path)}", f.name)
    }

    /** removes the files below [dir], keeps the folders (games expect their layout) */
    fun clearFiles(dir: File) = dir.walkBottomUp().filter { it.isFile }.forEach { delete(it) }

    /** removes everything inside [dir] */
    fun clearDir(dir: File) = dir.listFiles()?.forEach { delete(it) }

    private val touchFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** keeps the original change time; best effort */
    fun touch(f: File, time: Long) {
        if (time <= 0 || f.setLastModified(time)) return
        runCatching { Shell.exec("touch", "-m", "-d", touchFmt.format(Instant.ofEpochMilli(time)) + "Z", f.path) }
    }
}
