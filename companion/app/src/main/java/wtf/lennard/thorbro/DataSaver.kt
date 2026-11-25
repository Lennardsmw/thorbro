package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Daten sparen": when ThorBro may move saves over the network on its own.
 * The mode and the Auto-Sync switch (RommSync.auto) are kept consistent: Manual/Local turn Auto-Sync off,
 * switching Auto-Sync on again leaves them for "Mobil sparen".
 */
object DataSaver {
    enum class Mode(val id: String, val label: String, val sub: String) {
        ALL("all", "Alles", "auch über Mobilfunk"),
        MOBILE("mobile", "Mobil sparen", "automatisch nur im WLAN"),
        LOCAL("local", "Nur lokal", "kein RomM, nur auf dem Thor"),
        MANUAL("manual", "Manuell", "nur per Knopf"),
    }

    val KEEP_CHOICES = listOf(5, 10, 20, 30)

    fun mode(ctx: Context): Mode = Prefs.of(ctx).getString("dataMode", null)?.let { id -> Mode.entries.firstOrNull { it.id == id } } ?: Mode.MOBILE

    fun setMode(ctx: Context, m: Mode) {
        Prefs.of(ctx).edit().putString("dataMode", m.id).putBoolean("rommSaveSync", m == Mode.ALL || m == Mode.MOBILE).apply()
    }

    /** called when Auto-Sync is switched by hand */
    internal fun onAutoChanged(ctx: Context, on: Boolean) {
        val m = mode(ctx)
        if (on && (m == Mode.LOCAL || m == Mode.MANUAL)) Prefs.of(ctx).edit().putString("dataMode", Mode.MOBILE.id).apply()
        if (!on && (m == Mode.ALL || m == Mode.MOBILE)) Prefs.of(ctx).edit().putString("dataMode", Mode.MANUAL.id).apply()
    }

    /** RomM may be used at all (also by button) */
    fun rommAllowed(ctx: Context) = mode(ctx) != Mode.LOCAL

    /** saves go to / come from RomM without a button press */
    fun autoSync(ctx: Context) = RommSync.auto(ctx) && mode(ctx).let { it == Mode.ALL || it == Mode.MOBILE }

    /** the current network may be used for automatic transfers */
    fun netOk(ctx: Context) = mode(ctx) == Mode.ALL || SaveWatcher.onWifi(ctx)

    /** per-game backups kept */
    fun keep(ctx: Context) = Prefs.of(ctx).getInt("saveKeep", 10)
    fun setKeep(ctx: Context, n: Int) = Prefs.of(ctx).edit().putInt("saveKeep", n.coerceIn(1, 100)).apply()

    /** saves from friends are kept as a backup on arrival, without asking (the own save stays live) */
    fun giftAuto(ctx: Context) = Prefs.of(ctx).getBoolean("giftAuto", false)
    fun setGiftAuto(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putBoolean("giftAuto", on).apply()

    /** biggest game loaded from a friend over mobile data ("Mobil sparen"); bigger ones wait for WLAN */
    val GIFT_MB_CHOICES = listOf(100, 500, 2000, 10000)
    fun giftMaxMobile(ctx: Context): Long = Prefs.of(ctx).getInt("giftMaxMobileMb", 500).toLong() shl 20
    fun setGiftMaxMobile(ctx: Context, mb: Int) = Prefs.of(ctx).edit().putInt("giftMaxMobileMb", mb.coerceIn(10, 100_000)).apply()

    fun json(ctx: Context): JSONObject = JSONObject().put("mode", mode(ctx).id).put("keep", keep(ctx)).put("auto", RommSync.auto(ctx))
        .put("giftAuto", giftAuto(ctx)).put("giftMaxMobileMb", giftMaxMobile(ctx) shr 20).put("giftMbChoices", JSONArray(GIFT_MB_CHOICES))
        .put("wifi", SaveWatcher.onWifi(ctx))
        .put("modes", JSONArray(Mode.entries.map { JSONObject().put("id", it.id).put("label", it.label).put("sub", it.sub) }))
        .put("keepChoices", JSONArray(KEEP_CHOICES))
}
