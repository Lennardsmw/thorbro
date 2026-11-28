package wtf.lennard.thorbro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * Starts PC (Windows) games in GameHub Lite. Its launch intent wants GameHub's own library id
 * (`local_<uuid>`), not an exe path. GameHub keeps its library in private data, but it writes the id
 * together with the exe path into its logs in Android/data whenever a game is opened or started there;
 * ThorBro reads those through Shizuku and remembers each id, so a game only has to be set up once in GameHub.
 */
object GameHub {
    const val PKG = "gamehub.lite"
    const val ACTIVITY = "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    const val ACTION = "gamehub.lite.LAUNCH_GAME"
    private const val LOGS = "/storage/emulated/0/Android/data/$PKG/files/log"
    private const val PREF = "gamehub.ids"

    private val launchLine = Regex("""exePath = (/\S.*?) , (local_[0-9a-fA-F-]+)""")
    private val startLine = Regex("""(local_[0-9a-fA-F-]+) true \d+ (/storage/.+)""")
    private val idLine = Regex("""localGameId = (local_[0-9a-fA-F-]+)""")
    private val pathLine = Regex("""filePath = (/storage/.+)""")

    /** exe path -> GameHub id, from GameHub's log lines (later lines win) */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pending: String? = null
        text.lineSequence().forEach { line ->
            launchLine.find(line)?.let { out[it.groupValues[1].trim()] = it.groupValues[2]; pending = null; return@forEach }
            startLine.find(line)?.let { out[it.groupValues[2].trim()] = it.groupValues[1]; pending = null; return@forEach }
            idLine.find(line)?.let { pending = it.groupValues[1]; return@forEach }
            val p = pending
            if (p != null) pathLine.find(line)?.let { out[it.groupValues[1].trim()] = p; pending = null }
        }
        return out
    }

    private fun cached(ctx: Context): MutableMap<String, String> {
        val o = runCatching { JSONObject(Prefs.of(ctx).getString(PREF, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }

    /** known ids, refreshed from GameHub's current logs when Shizuku is available */
    fun ids(ctx: Context): Map<String, String> {
        val all = cached(ctx)
        val text = Shell.out("sh", "-c",
            "cat $LOGS/util_*_$PKG.txt 2>/dev/null | grep -E 'exePath = /|localGameId = local_|filePath = /|local_[0-9a-f-]* true [0-9]* /'; true")
        if (!text.isNullOrBlank()) {
            val fresh = parse(text)
            if (fresh.any { all[it.key] != it.value }) {
                all.putAll(fresh)
                Prefs.of(ctx).edit().putString(PREF, JSONObject(all as Map<*, *>).toString()).apply()
            }
        }
        return all
    }

    /** GameHub id for a game folder in ROMs/pc (or a single exe) */
    fun idFor(ctx: Context, game: File): String? {
        val base = game.path.trimEnd('/')
        return ids(ctx).entries.lastOrNull { it.key == base || it.key.startsWith("$base/") }?.value
    }

    fun launch(ctx: Context, game: File): JSONObject {
        val id = idFor(ctx, game)
        if (id == null) {
            Apps.launch(ctx, PKG)
            val hint = if (Shell.ready()) "" else " (ThorBro braucht dafür Shizuku.)"
            return JSONObject().put("code", "gamehub_setup")
                .put("error", "„${game.name}“ ist in GameHub Lite noch nicht eingerichtet: dort einmal die .exe in ${game.path} " +
                    "hinzufügen und starten. Danach startet ThorBro es direkt.$hint")
        }
        val i = Intent(ACTION).setComponent(ComponentName(PKG, ACTIVITY))
            .putExtra("localGameId", id)
            .putExtra("autoStartGame", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        Logs.i("play", "GameHub Lite: ${game.name} ($id, Display ${Emulators.display})")
        return try {
            Emulators.startOnDisplay(ctx, i)
            JSONObject().put("ok", true).put("emulator", "GameHub Lite")
        } catch (e: Exception) {
            Logs.e("play", "GameHub Lite startet nicht", e)
            JSONObject().put("error", "GameHub Lite ließ sich nicht starten: ${e.message}")
        }
    }
}
