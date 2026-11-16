package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers, metadata and console icons from libretro (no account or API key needed).
 * Everything is downloaded by the Thor itself and cached in the app's private storage.
 *
 * - Covers:   thumbnails.libretro.com/<System>/Named_Boxarts/<Game>.png
 * - Metadata: libretro-database metadat/{no-intro|redump} (name -> crc)
 *             + metadat/{developer,publisher,releaseyear,genre,maxusers} (crc -> value)
 * - Icons:    retroarch-assets xmb/systematic/png/<System>.png
 */
object Media {
    val libretro = mapOf(
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "n3ds" to "Nintendo - Nintendo 3DS",
        "virtualboy" to "Nintendo - Virtual Boy",
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "wiiu" to "Nintendo - Wii U",
        "switch" to "Nintendo - Switch",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "ps3" to "Sony - PlayStation 3",
        "psp" to "Sony - PlayStation Portable",
        "psvita" to "Sony - PlayStation Vita",
        "xbox" to "Microsoft - Xbox",
        "xbox360" to "Microsoft - Xbox 360",
        "dreamcast" to "Sega - Dreamcast",
        "saturn" to "Sega - Saturn",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "segacd" to "Sega - Mega-CD - Sega CD",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "atari2600" to "Atari - 2600",
        "lynx" to "Atari - Lynx",
        "ngp" to "SNK - Neo Geo Pocket Color",
        "wonderswan" to "Bandai - WonderSwan Color",
        "arcade" to "MAME",
    )

    private const val DB = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat"
    private const val THUMBS = "https://thumbnails.libretro.com"
    private const val ICONS = "https://raw.githubusercontent.com/libretro/retroarch-assets/master/xmb/systematic/png"
    private const val MAX_AGE = 30L * 24 * 3600 * 1000
    private val fields = listOf("developer", "publisher", "releaseyear", "genre", "maxusers")

    private class Entry(val name: String, val values: MutableMap<String, String> = HashMap())
    private class SysData(val byTitle: Map<String, List<Entry>>, val thumbs: Map<String, List<String>>, val thumbSet: Set<String>)

    private val loaded = ConcurrentHashMap<String, SysData>()

    // ---------- name matching ----------

    /** "Legend of Zelda, The - A Link Between Worlds (Europe)" and "THE LEGEND OF ZELDA A Link..." -> same key */
    fun titleKey(name: String): String {
        var t = name.substringBefore(" (").substringBefore(" [").substringBefore("[").lowercase()
        t = t.replace("&", "and").replace(", the", "").replace(Regex("^the "), "")
        return t.filter { it.isLetterOrDigit() }
    }

    fun tags(name: String): Set<String> =
        Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(name).flatMap { m -> m.groupValues[1].split(",").map { it.trim().lowercase() } }.toSet()

    private val preferred = listOf("germany", "europe", "de", "usa", "world", "en")

    private fun score(candidate: String, wanted: Set<String>): Int {
        val ct = tags(candidate)
        var s = ct.count { it in wanted } * 10
        preferred.forEachIndexed { i, p -> if (p in ct) s += preferred.size - i }
        if ("beta" in ct || "proto" in ct || "demo" in ct || "kiosk" in ct) s -= 20
        return s
    }

    private fun <T> best(map: Map<String, List<T>>, romName: String, name: (T) -> String): T? {
        val key = titleKey(romName)
        if (key.length < 2) return null
        val wanted = tags(romName)
        val hits = map[key] ?: map.entries
            .filter { key.length >= 6 && (it.key.startsWith(key) || key.startsWith(it.key)) && it.key.length >= 6 }
            .minByOrNull { Math.abs(it.key.length - key.length) }?.value
            ?: return null
        return hits.maxByOrNull { score(name(it), wanted) }
    }

    // ---------- public API ----------

    /** Metadata + cover name for every ROM of one console from the last scan. */
    fun metaFor(ctx: Context, systemId: String, roms: List<JSONObject>): JSONObject {
        val out = JSONObject()
        val data = load(ctx, systemId)
        val cocoon = Cocoon.games(ctx, systemId)
        for (r in roms) {
            val name = if (r.getBoolean("dir")) r.getString("file") else r.getString("file").substringBeforeLast('.')
            val m = JSONObject()
            val t = tags(name)
            m.put("region", t.firstOrNull { it in regionWords }?.replaceFirstChar { it.uppercase() } ?: "")
            Regex("\\[([0-9A-Fa-f]{16})]").find(name)?.let { m.put("titleId", it.groupValues[1].uppercase()) }
            fun fill(k: String, v: String?) { if (!v.isNullOrBlank() && !m.has(k)) m.put(k, v) }
            cocoon[r.getString("file")]?.fields?.let { g ->
                m.put("source", "cocoon")
                fill("title", g["cocoon:displayname"] ?: g["name"])
                fill("desc", g["desc"]?.replace("&quot;", "\"")?.replace("&amp;", "&"))
                fill("year", g["releasedate"]?.take(4))
                fill("developer", g["developer"])
                fill("publisher", g["publisher"])
                fill("genre", g["genre"])
                fill("players", g["players"])
                g["rating"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { m.put("rating", Math.round(it * 100) / 10.0) }
                g["playcount"]?.toIntOrNull()?.takeIf { it > 0 }?.let { m.put("playcount", it) }
                g["cocoon:playtime"]?.toLongOrNull()?.takeIf { it > 0 }?.let { m.put("playtime", it) }
                g["lastplayed"]?.takeIf { it.isNotBlank() }?.let { m.put("lastplayed", it) }
                if (g["favorite"] == "true") m.put("favorite", true)
            }
            val kinds = Cocoon.mediaKindsFor(ctx, systemId, r.getString("path"))
            if (kinds.isNotEmpty()) m.put("media", org.json.JSONArray(kinds))
            if (data != null) {
                best(data.byTitle, name) { it.name }?.let { e ->
                    fill("title", e.name.substringBefore(" ("))
                    m.put("matched", e.name)
                    fill("year", e.values["releaseyear"])
                    fill("developer", e.values["developer"])
                    fill("publisher", e.values["publisher"])
                    fill("genre", e.values["genre"])
                    fill("players", e.values["maxusers"])
                }
                if ("icon" !in kinds) {
                    val thumbExact = m.optString("matched").takeIf { it.isNotEmpty() }?.let(::thumbName)
                    val cover = thumbExact?.takeIf { it in data.thumbSet } ?: best(data.thumbs, name) { it }
                    if (cover != null) m.put("cover", cover)
                    if (cover != null) fill("title", cover.substringBefore(" ("))
                }
            }
            out.put(r.getString("path"), m)
        }
        return out
    }

    /** PNG bytes of a cover, from cache or downloaded once. Only names from the system's index are allowed. */
    fun cover(ctx: Context, systemId: String, name: String): File? {
        val data = load(ctx, systemId) ?: return null
        if (name !in data.thumbSet) return null
        val sys = libretro[systemId] ?: return null
        val f = File(dir(ctx, "covers/$systemId"), sha1(name) + ".png")
        if (f.length() > 0) return f
        return if (download("$THUMBS/${enc(sys)}/Named_Boxarts/${enc(name)}.png", f)) f else null
    }

    fun icon(ctx: Context, systemId: String): File? {
        // RomM's console icons first (crisp SVG, same look as the library), libretro's PNG as fallback
        val svg = File(dir(ctx, "icons"), "romm-$systemId.svg")
        if (svg.length() > 0 && System.currentTimeMillis() - svg.lastModified() < MAX_AGE) return svg
        val noSvg = File(dir(ctx, "icons"), "romm-$systemId.missing")
        if (!(noSvg.exists() && System.currentTimeMillis() - noSvg.lastModified() < 24 * 3600 * 1000L)) {
            if (Romm.platformIcon(ctx, systemId, svg)) return svg
            noSvg.writeText("")
        }
        if (svg.length() > 0) return svg
        val sys = libretro[systemId] ?: return null
        val f = File(dir(ctx, "icons"), "$systemId.png")
        val miss = File(dir(ctx, "icons"), "$systemId.missing")
        if (f.length() > 0) return f
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MAX_AGE) return null
        return if (download("$ICONS/${enc(sys)}.png", f)) f else { miss.writeText(""); null }
    }

    // ---------- loading ----------

    private val regionWords = setOf("europe", "usa", "japan", "germany", "world", "france", "spain", "italy", "korea", "asia", "australia", "china")

    /** libretro file names replace these characters with "_". */
    private fun thumbName(name: String) = name.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    @Synchronized
    private fun load(ctx: Context, systemId: String): SysData? {
        loaded[systemId]?.let { return it }
        val sys = libretro[systemId] ?: return null
        val base = dir(ctx, "meta/$systemId")
        // Cartridge sets link metadata by CRC, disc sets (redump) by serial; some entries only by name.
        val byCrc = HashMap<String, Entry>()
        val bySerial = HashMap<String, Entry>()
        val byName = HashMap<String, Entry>()
        for (kind in listOf("no-intro", "redump")) {
            val f = cached(File(base, "$kind.dat"), "$DB/$kind/${enc(sys)}.dat") ?: continue
            parseDat(f) { name, crc, serial, _ ->
                if (name == null) return@parseDat
                val e = byName.getOrPut(name) { Entry(name) }
                crc?.let { byCrc[it] = e }
                serial?.let { bySerial[it] = e }
            }
        }
        for (field in fields) {
            val f = cached(File(base, "$field.dat"), "$DB/$field/${enc(sys)}.dat") ?: continue
            parseDat(f) { name, crc, serial, values ->
                val v = values[field] ?: return@parseDat
                val e = crc?.let { byCrc[it] } ?: serial?.let { bySerial[it] } ?: name?.let { byName[it] }
                e?.values?.put(field, v)
            }
        }
        val entries = byName
        val thumbIdx = cached(File(base, "thumbs.txt"), null) { target ->
            val html = fetch("$THUMBS/${enc(sys)}/Named_Boxarts/") ?: return@cached false
            val names = Regex("href=\"([^\"]+)\\.png\"").findAll(html)
                .map { java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }
                .filter { !it.contains("/") }.toList()
            if (names.isEmpty()) return@cached false
            target.writeText(names.joinToString("\n"))
            true
        }
        val thumbList = thumbIdx?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
        val data = SysData(
            byTitle = entries.values.groupBy { titleKey(it.name) },
            thumbs = thumbList.groupBy { titleKey(it) },
            thumbSet = thumbList.toHashSet(),
        )
        loaded[systemId] = data
        return data
    }

    /** Minimal clrmamepro DAT reader: calls back once per `game ( ... )` block. */
    private fun parseDat(f: File, onGame: (name: String?, crc: String?, serial: String?, values: Map<String, String>) -> Unit) {
        val kv = Regex("^\\s*(\\w+)\\s+\"(.*)\"\\s*$")
        val crcRe = Regex("\\bcrc\\s+([0-9A-Fa-f]{8})")
        val serialRe = Regex("\\bserial\\s+\"([^\"]+)\"")
        var inGame = false
        var name: String? = null
        var crc: String? = null
        var serial: String? = null
        val values = HashMap<String, String>()
        f.forEachLine { line ->
            val l = line.trim()
            if (!inGame) {
                if (l.startsWith("game (")) { inGame = true; name = null; crc = null; serial = null; values.clear() }
                return@forEachLine
            }
            if (l == ")") { onGame(name, crc, serial, values); inGame = false; return@forEachLine }
            if (l.startsWith("rom (")) {
                if (crc == null) crc = crcRe.find(l)?.groupValues?.get(1)?.uppercase()
                if (serial == null) serial = serialRe.find(l)?.groupValues?.get(1)
                return@forEachLine
            }
            kv.find(line)?.let { m ->
                val (k, v) = m.destructured
                when (k) {
                    "name", "comment" -> if (name == null) name = v
                    "serial" -> if (serial == null) serial = v
                    else -> values[k] = v
                }
            }
        }
    }

    // ---------- tiny HTTP + cache helpers ----------

    private fun dir(ctx: Context, sub: String) = File(ctx.filesDir, "media/$sub").apply { mkdirs() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Returns the cached file (refreshed after 30 days). A 404 is remembered as an empty file. */
    private fun cached(f: File, url: String?, fill: ((File) -> Boolean)? = null): File? {
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE
        if (!fresh) {
            val tmp = File(f.path + ".tmp")
            val ok = if (fill != null) fill(tmp) else download(url!!, tmp, remember404 = true)
            if (ok) tmp.renameTo(f) else tmp.delete()
        }
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "ThorBro/0.1 (Android)")
    }

    private fun fetch(url: String): String? = try {
        open(url).run { if (responseCode == 200) inputStream.bufferedReader().use { it.readText() } else null }
    } catch (_: Exception) { null }

    private fun download(url: String, target: File, remember404: Boolean = false): Boolean = try {
        val c = open(url)
        when (c.responseCode) {
            200 -> { target.parentFile?.mkdirs(); c.inputStream.use { i -> target.outputStream().use { i.copyTo(it) } }; target.length() > 0 }
            404 -> { if (remember404) { target.writeText(""); true } else false }
            else -> false
        }
    } catch (_: Exception) { false }
}
