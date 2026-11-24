package wtf.lennard.thorbro

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Who this ThorBro is to other Thors: a stable short id and a readable name (two Thors share the same model name). */
object Me {
    fun id(ctx: Context): String {
        val p = Prefs.of(ctx)
        p.getString("me.id", null)?.let { return it }
        val id = (1..4).map { "0123456789abcdef".random() }.joinToString("")
        p.edit().putString("me.id", id).apply()
        return id
    }

    /** the device name from Android's settings ("Lennards Thor"), else "Thor-<id>" */
    fun name(ctx: Context): String {
        val dev = runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()
        return dev?.takeIf { it.isNotEmpty() && !it.equals(android.os.Build.MODEL, true) }?.take(24) ?: "Thor-${id(ctx)}"
    }
}

/**
 * Thors this one played with. The first invite is accepted in the Multiplayer screen; from then on both know each other:
 * invites from a friend arrive as a notification (also while another frontend is open), and a game a friend-paired Thor
 * starts — from ThorBro, ES-DE or any other frontend — invites the friends by itself ([AutoPlay]).
 */
object Friends {
    private fun read(ctx: Context): JSONArray = runCatching { JSONArray(Prefs.of(ctx).getString("friends", "[]")) }.getOrDefault(JSONArray())

    fun list(ctx: Context): List<JSONObject> = read(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    fun any(ctx: Context) = read(ctx).length() > 0

    fun isFriend(ctx: Context, id: String?) = !id.isNullOrEmpty() && list(ctx).any { it.optString("id") == id }

    @Synchronized
    fun add(ctx: Context, id: String?, name: String?) {
        if (id.isNullOrEmpty() || !Regex("^[0-9a-f]{4}$").matches(id) || id == Me.id(ctx)) return
        val first = !any(ctx)
        val list = list(ctx).filter { it.optString("id") != id } +
            JSONObject().put("id", id).put("name", (name ?: "Thor").take(24)).put("since", System.currentTimeMillis())
        Prefs.of(ctx).edit().putString("friends", JSONArray(list).toString()).apply()
        Logs.i("friends", "Freund gemerkt: ${name ?: id}")
        // first friend: get the emulators ready to host from any frontend
        if (first) kotlin.concurrent.thread(name = "netplay-prepare") { runCatching { Netplay.prepare(ctx.applicationContext) } }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        Prefs.of(ctx).edit().putString("friends", JSONArray(list(ctx).filter { it.optString("id") != id }).toString()).apply()
    }

    /** invite friends by itself when a multiplayer game starts (any frontend) */
    fun auto(ctx: Context) = Prefs.of(ctx).getBoolean("friends.auto", true)

    fun setAuto(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putBoolean("friends.auto", on).apply()
}
