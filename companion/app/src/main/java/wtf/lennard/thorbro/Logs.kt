package wtf.lennard.thorbro

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ThorBro's own log (what happened, errors) in files/thorbro.log (rotates at 512 KB),
 * plus the app's own logcat and the last crashes – all readable via GET /api/debug/logs.
 */
object Logs {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun init(ctx: Context) { if (file == null) file = File(ctx.applicationContext.filesDir, "thorbro.log") }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "ThorBro/$tag", msg)
        val f = file ?: return
        runCatching {
            if (f.length() > 512 * 1024) { f.copyTo(File(f.path + ".1"), overwrite = true); f.writeText("") }
            f.appendText("${fmt.format(Date())} $level $tag: $msg\n")
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write("E", tag, msg + (t?.let { " – ${it.javaClass.simpleName}: ${it.message}\n" + it.stackTraceToString().lines().take(12).joinToString("\n") } ?: ""))

    fun tail(lines: Int): String {
        val f = file ?: return ""
        val prev = File(f.path + ".1").takeIf { it.exists() }?.readLines() ?: emptyList()
        val all = prev + (if (f.exists()) f.readLines() else emptyList())
        return all.takeLast(lines).joinToString("\n")
    }

    /** the app's own logcat (Android lets an app read its own entries) */
    fun logcat(lines: Int): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", "$lines"))
        p.inputStream.bufferedReader().readText()
    }.getOrElse { "logcat nicht lesbar: ${it.message}" }
}
