package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Identifies ROMs with RetroAchievements: computes the RA hash per console (the same rules RA uses,
 * cartridge systems and DS), looks the hash up in RA's game list and adds the user's progress.
 * Needs the RA username and web API key (retroachievements.org/settings).
 */
object RetroAchievements {
    private const val API = "https://retroachievements.org/API"

    val consoles = mapOf(
        "megadrive" to 1, "n64" to 2, "snes" to 3, "gb" to 4, "gba" to 5, "gbc" to 6, "nes" to 7, "pcengine" to 8,
        "mastersystem" to 11, "lynx" to 13, "ngp" to 14, "gamegear" to 15, "nds" to 18, "atari2600" to 25,
        "virtualboy" to 28, "wonderswan" to 53,
    )

    fun user(ctx: Context) = Prefs.of(ctx).getString("raUser", "") ?: ""
    private fun key(ctx: Context) = Prefs.of(ctx).getString("raKey", "") ?: ""
    fun configured(ctx: Context) = user(ctx).isNotEmpty() && key(ctx).isNotEmpty()

    fun configure(ctx: Context, user: String?, key: String?) {
        val e = Prefs.of(ctx).edit()
        user?.let { e.putString("raUser", it.trim()) }
        key?.let { if (it.isNotBlank()) e.putString("raKey", it.trim()) }
        e.apply()
        progressCache = null
    }

    fun status(ctx: Context) = JSONObject().put("configured", configured(ctx)).put("user", user(ctx))
        .put("systems", JSONArray(consoles.keys.toList()))

    // ---------- hashing ----------

    private val hashCache = ConcurrentHashMap<String, String>()

    private fun md5(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String =
        MessageDigest.getInstance("MD5").apply { update(bytes, off, len) }.digest().joinToString("") { "%02x".format(it) }

    /** RA hash for one ROM file (or the ROM inside a single-ROM zip); null when the console is not supported */
    fun hash(system: String, f: File): String? {
        if (system !in consoles) return null
        val cacheKey = "${f.path}|${f.length()}|${f.lastModified()}"
        hashCache[cacheKey]?.let { return it }
        val h = if (f.name.endsWith(".zip", true)) ZipFile(f).use { z ->
            val e = z.entries().toList().firstOrNull { !it.isDirectory && !it.name.endsWith(".txt", true) } ?: return null
            if (e.size > 64L * 1024 * 1024) return null
            hashBytes(system, z.getInputStream(e).use(InputStream::readBytes))
        } else if (system == "nds") hashNds(f)
        else if (f.length() > 128L * 1024 * 1024) null
        else hashBytes(system, f.readBytes())
        if (h != null) hashCache[cacheKey] = h
        return h
    }

    private fun hashBytes(system: String, b: ByteArray): String = when (system) {
        "nes" -> if (b.size > 16 && b[0] == 'N'.code.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'S'.code.toByte() && b[3] == 0x1A.toByte()) md5(b, 16) else md5(b)
        "snes" -> if (b.size % 1024 == 512) md5(b, 512) else md5(b)
        "pcengine" -> if (b.size % 131072 == 512) md5(b, 512) else md5(b)
        "lynx" -> if (b.size > 64 && String(b, 0, 4, Charsets.US_ASCII) == "LYNX") md5(b, 64) else md5(b)
        "n64" -> md5(n64BigEndian(b))
        else -> md5(b)
    }

    /** RA hashes N64 ROMs in big-endian (.z64) byte order */
    private fun n64BigEndian(b: ByteArray): ByteArray {
        if (b.size < 4) return b
        val first = b[0].toInt() and 0xFF
        val out = b.copyOf()
        when (first) {
            0x37 -> for (i in 0 until out.size - 1 step 2) { out[i] = b[i + 1]; out[i + 1] = b[i] }                 // .v64
            0x40 -> for (i in 0 until out.size - 3 step 4) { out[i] = b[i + 3]; out[i + 1] = b[i + 2]; out[i + 2] = b[i + 1]; out[i + 3] = b[i] } // .n64
        }
        return out
    }

    /** DS: header + ARM9 + ARM7 + icon/title, like RA's rc_hash */
    private fun hashNds(f: File): String? = RandomAccessFile(f, "r").use { r ->
        val header = ByteArray(0x160)
        r.readFully(header)
        fun le(o: Int) = (header[o].toLong() and 0xFF) or ((header[o + 1].toLong() and 0xFF) shl 8) or
            ((header[o + 2].toLong() and 0xFF) shl 16) or ((header[o + 3].toLong() and 0xFF) shl 24)
        val md = MessageDigest.getInstance("MD5")
        md.update(header)
        fun part(off: Long, len: Long) {
            if (off <= 0 || len <= 0 || off + len > f.length() || len > 16L * 1024 * 1024) return
            val buf = ByteArray(len.toInt()); r.seek(off); r.readFully(buf); md.update(buf)
        }
        part(le(0x20), le(0x2C))
        part(le(0x30), le(0x3C))
        part(le(0x68), 0xA00)
        md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- RA web API ----------

    private fun get(ctx: Context, endpoint: String, params: Map<String, String>): String {
        val q = (params + mapOf("y" to key(ctx), "z" to user(ctx))).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URL("$API/$endpoint?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "ThorBro/1.0")
        if (c.responseCode != 200) throw IllegalStateException("RetroAchievements antwortet mit HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** hash -> game, per console, cached for a week */
    private fun gameIndex(ctx: Context, consoleId: Int): Map<String, JSONObject> {
        val f = File(ctx.filesDir, "ra/games-$consoleId.json").apply { parentFile?.mkdirs() }
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) {
            f.writeText(get(ctx, "API_GetGameList.php", mapOf("i" to "$consoleId", "h" to "1", "f" to "1")))
        }
        val arr = JSONArray(f.readText())
        val out = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val hs = g.optJSONArray("Hashes") ?: continue
            for (k in 0 until hs.length()) out[hs.getString(k).lowercase()] = g
        }
        return out
    }

    @Volatile private var progressCache: Pair<Long, Map<Int, JSONObject>>? = null

    private fun progress(ctx: Context): Map<Int, JSONObject> {
        progressCache?.let { (t, m) -> if (System.currentTimeMillis() - t < 10 * 60_000) return m }
        val j = JSONObject(get(ctx, "API_GetUserCompletionProgress.php", mapOf("u" to user(ctx), "c" to "500")))
        val res = j.optJSONArray("Results") ?: JSONArray()
        val m = (0 until res.length()).map { res.getJSONObject(it) }.associateBy { it.getInt("GameID") }
        progressCache = System.currentTimeMillis() to m
        return m
    }

    /** {romPath: {hash, gameId?, title?, achievements?, points?, icon?, awarded?, awardedHardcore?}} */
    fun forSystem(ctx: Context, system: String, roms: List<JSONObject>): JSONObject {
        val out = JSONObject()
        val consoleId = consoles[system] ?: return out.put("_error", "Konsole wird von ThorBro für RetroAchievements noch nicht unterstützt")
        if (!configured(ctx)) return out.put("_error", "RetroAchievements ist nicht eingerichtet")
        val index = gameIndex(ctx, consoleId)
        val prog = runCatching { progress(ctx) }.getOrDefault(emptyMap())
        for (r in roms) {
            if (r.optBoolean("dir")) continue
            val h = runCatching { hash(system, File(r.getString("path"))) }.getOrNull() ?: continue
            val o = JSONObject().put("hash", h)
            index[h]?.let { g ->
                val id = g.getInt("ID")
                o.put("gameId", id).put("title", g.optString("Title")).put("achievements", g.optInt("NumAchievements"))
                    .put("points", g.optInt("Points")).put("icon", "https://media.retroachievements.org" + g.optString("ImageIcon"))
                prog[id]?.let { p -> o.put("awarded", p.optInt("NumAwarded")).put("awardedHardcore", p.optInt("NumAwardedHardcore")) }
            }
            out.put(r.getString("path"), o)
        }
        return out
    }
}
