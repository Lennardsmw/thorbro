package wtf.lennard.thorbro

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** commands with shell rights through Shizuku (granted once in the Shizuku app) */
object Shell {
    /** "ready", "needs_permission" or "not_running" */
    fun state(): String = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "not_running"
        Shizuku.isPreV11() -> "not_running"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "needs_permission"
        else -> "ready"
    }

    fun ready() = state() == "ready"

    fun request() { runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(77) } }

    /** runs [cmd]; exit code and stderr, or null when Shizuku is not usable */
    fun exec(vararg cmd: String): Pair<Int, String>? {
        if (!ready()) return null
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
            val err = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor() to err.trim()
        }.getOrNull()
    }

    fun run(vararg cmd: String): Boolean = exec(*cmd)?.first == 0

    /** runs [cmd] and returns stdout (null when Shizuku is not ready or the command failed); [stdin] is fed to the command */
    fun out(vararg cmd: String, stdin: ByteArray? = null): String? {
        if (!ready()) return null
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
            if (stdin != null) p.outputStream.use { it.write(stdin) } else p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) out else null
        }.getOrNull()
    }

    /** runs [cmd] and returns raw stdout bytes (e.g. a PNG from screencap); null when Shizuku is not ready or it failed */
    fun bytes(vararg cmd: String): ByteArray? {
        if (!ready()) return null
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
            p.outputStream.close()
            val out = p.inputStream.readBytes()
            if (p.waitFor() == 0 && out.isNotEmpty()) out else null
        }.getOrNull()
    }

    /** single-quotes a path for sh -c */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
