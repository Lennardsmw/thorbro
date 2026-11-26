package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recently played games for Start (native) and the web panel: games started from ThorBro
 * plus Cocoon's "lastplayed" (games started from the launcher itself).
 */
object Recent {
    private const val KEY = "recent.played"
    private const val MAX = 12

    /** a game was started from ThorBro */
    fun played(ctx: Context, system: String, path: String) = synchronized(this) {
        val p = Prefs.of(ctx)
        val old = runCatching { JSONArray(p.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray().put(JSONObject().put("path", path).put("system", system).put("t", System.currentTimeMillis()))
        for (i in 0 until old.length()) {
            val e = old.getJSONObject(i)
            if (e.optString("path") != path && out.length() < MAX) out.put(e)
        }
        p.edit().putString(KEY, out.toString()).apply()
    }

    private val esde = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)

    /** "20241126T153012" (ES-DE / Cocoon gamelist) → ms, 0 if unknown */
    fun parse(lastPlayed: String?): Long =
        if (lastPlayed.isNullOrBlank()) 0L else runCatching { synchronized(esde) { esde.parse(lastPlayed.take(15))!!.time } }.getOrDefault(0L)

    /** path → (system, time) of the most recently played games, newest first */
    fun list(ctx: Context, limit: Int = 8): List<Triple<String, String, Long>> {
        val best = HashMap<String, Pair<String, Long>>()
        fun add(path: String, sys: String, t: Long) { if (t > (best[path]?.second ?: 0L)) best[path] = sys to t }
        runCatching { JSONArray(Prefs.of(ctx).getString(KEY, "[]")) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { add(it.optString("path"), it.optString("system"), it.optLong("t")) }
        }
        val systems = Roms.scan(ctx, false).getJSONArray("systems")
        val local = HashSet<String>()
        for (i in 0 until systems.length()) {
            val s = systems.getJSONObject(i)
            val id = s.getString("id")
            if (id == "_unassigned") continue
            val roms = s.getJSONArray("roms")
            val cocoon = runCatching { Cocoon.games(ctx, id) }.getOrDefault(emptyMap())
            for (j in 0 until roms.length()) {
                val r = roms.getJSONObject(j)
                local += r.getString("path")
                val t = parse(cocoon[r.getString("file")]?.fields?.get("lastplayed"))
                if (t > 0) add(r.getString("path"), id, t)
            }
        }
        // only games that are still on the Thor
        return best.entries.filter { it.key in local && File(it.key).exists() }
            .sortedByDescending { it.value.second }.take(limit).map { Triple(it.key, it.value.first, it.value.second) }
    }
}
