package wtf.lennard.thorbro

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** Settings and the pairing code that clients send as `Authorization: Bearer <code>`. */
object Prefs {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun of(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("thorbro", Context.MODE_PRIVATE)

    fun token(ctx: Context): String = of(ctx).getString("token", null) ?: newToken(ctx)

    fun newToken(ctx: Context): String {
        val rnd = SecureRandom()
        val t = (1..8).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        of(ctx).edit().putString("token", t).apply()
        return t
    }

    fun tokenMatches(ctx: Context, given: String?): Boolean {
        if (given.isNullOrEmpty()) return false
        return MessageDigest.isEqual(token(ctx).toByteArray(), given.trim().uppercase().toByteArray())
    }

    fun autostart(ctx: Context): Boolean = of(ctx).getBoolean("autostart", true)
    fun setAutostart(ctx: Context, on: Boolean) = of(ctx).edit().putBoolean("autostart", on).apply()

    // ---- Downloads (Store › Downloads, settings native + web) ----
    /** new finds in the Download folder install by themselves (off = "bereit zum Installieren", one click) */
    fun autoInstall(ctx: Context): Boolean = of(ctx).getBoolean("downloads.autoInstall", false)
    /** after installing: delete the archive / source file and leftovers */
    fun autoClean(ctx: Context): Boolean = of(ctx).getBoolean("downloads.autoClean", true)
    /** after installing: send the game to RomM (when connected) */
    fun autoUpload(ctx: Context): Boolean = of(ctx).getBoolean("downloads.autoUpload", false)
    fun setDownloads(ctx: Context, autoInstall: Boolean?, autoClean: Boolean?, autoUpload: Boolean?) = of(ctx).edit().apply {
        autoInstall?.let { putBoolean("downloads.autoInstall", it) }
        autoClean?.let { putBoolean("downloads.autoClean", it) }
        autoUpload?.let { putBoolean("downloads.autoUpload", it) }
    }.apply()
    fun downloadsJson(ctx: Context): org.json.JSONObject = org.json.JSONObject()
        .put("autoInstall", autoInstall(ctx)).put("autoClean", autoClean(ctx)).put("autoUpload", autoUpload(ctx))
}
