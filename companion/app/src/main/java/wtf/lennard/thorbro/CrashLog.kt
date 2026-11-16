package wtf.lennard.thorbro

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** keeps the last crash (stack trace) in a file, readable via /api/debug/crash */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        Logs.init(ctx)
        val dir = File(ctx.applicationContext.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                Logs.e("crash", "Absturz in ${t.name}", e)
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText("${java.util.Date()} · Thread ${t.name} · ThorBro ${BuildConfigVersion.name(ctx)}\n\n$sw")
                dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String = File(ctx.filesDir, "crashes").listFiles()?.sortedByDescending { it.name }
        ?.joinToString("\n\n==========\n\n") { it.readText() } ?: ""
}

object BuildConfigVersion {
    fun name(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
