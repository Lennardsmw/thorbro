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
}
