package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes ThorBro's metadata and images in the ES-DE layout:
 *   <dest>/gamelists/<system>/gamelist.xml
 *   <dest>/downloaded_media/<system>/{covers,marquees,screenshots,titlescreens,fanart}/<rom name>.<ext>
 * ES-DE reads it directly; iiSU imports it via "link ES-DE metadata".
 * Existing gamelists are kept: only games that are missing get added (a .thorbro-bak copy is made once).
 */
object EsDe {
    val defaultDest: String get() = File(Storage.primary, "ES-DE").path

    private val mediaMap = mapOf("icon" to "covers", "logo" to "marquees", "screenshot_gameplay" to "screenshots",
        "screenshot_title" to "titlescreens", "hero" to "fanart")

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun export(ctx: Context, dest: File, job: Jobs.Job): JSONObject {
        val scan = Roms.scan(ctx, false).getJSONArray("systems")
        val systems = (0 until scan.length()).map { scan.getJSONObject(it) }.filter { it.getString("id") != "_unassigned" }
        val total = systems.sumOf { it.getInt("count") }.coerceAtLeast(1)
        var doneN = 0; var games = 0; var images = 0
        val summary = JSONArray()
        for (s in systems) {
            val id = s.getString("id")
            job.message = s.getString("name")
            val roms = s.getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val meta = Media.metaFor(ctx, id, roms)
            val listFile = File(dest, "gamelists/$id/gamelist.xml").apply { parentFile?.mkdirs() }
            val existing = if (listFile.exists()) listFile.readText() else ""
            if (existing.isNotEmpty()) File(listFile.path + ".thorbro-bak").let { if (!it.exists()) listFile.copyTo(it) }
            val known = Regex("<path>(.*?)</path>").findAll(existing).map { it.groupValues[1] }.toSet()
            val sb = StringBuilder()
            var added = 0
            for (r in roms) {
                doneN++; job.progress = doneN.toDouble() / total
                val file = r.getString("file")
                val rel = "./" + file
                val m = meta.optJSONObject(r.getString("path")) ?: JSONObject()
                val base = if (r.getBoolean("dir")) file else file.substringBeforeLast('.')
                // images: Cocoon media first, libretro cover as fallback
                for ((kind, folder) in mediaMap) {
                    val src = Cocoon.media(ctx, id, r.getString("path"), kind) ?: continue
                    val t = File(dest, "downloaded_media/$id/$folder/$base.${src.extension}")
                    if (!t.exists()) { t.parentFile?.mkdirs(); src.copyTo(t); images++ }
                }
                if (Cocoon.media(ctx, id, r.getString("path"), "icon") == null) m.optString("cover").takeIf { it.isNotEmpty() }?.let { cover ->
                    val t = File(dest, "downloaded_media/$id/covers/$base.png")
                    if (!t.exists()) Media.cover(ctx, id, cover)?.let { c -> t.parentFile?.mkdirs(); c.copyTo(t); images++ }
                }
                if (rel in known) continue
                sb.append("\t<game>\n\t\t<path>").append(x(rel)).append("</path>\n")
                sb.append("\t\t<name>").append(x(m.optString("title").ifEmpty { base.substringBefore(" (") })).append("</name>\n")
                m.optString("desc").takeIf { it.isNotEmpty() }?.let { sb.append("\t\t<desc>").append(x(it)).append("</desc>\n") }
                m.optString("year").takeIf { it.length == 4 }?.let { sb.append("\t\t<releasedate>").append(it).append("0101T000000</releasedate>\n") }
                for ((k, tag) in listOf("developer" to "developer", "publisher" to "publisher", "genre" to "genre", "players" to "players"))
                    m.optString(k).takeIf { it.isNotEmpty() }?.let { sb.append("\t\t<$tag>").append(x(it)).append("</$tag>\n") }
                if (m.has("rating")) sb.append("\t\t<rating>").append(m.getDouble("rating") / 10).append("</rating>\n")
                if (m.optBoolean("favorite")) sb.append("\t\t<favorite>true</favorite>\n")
                sb.append("\t</game>\n")
                added++; games++
            }
            if (added > 0 || existing.isEmpty()) {
                val text = if (existing.contains("</gameList>")) existing.replace("</gameList>", sb.toString() + "</gameList>")
                    else "<?xml version=\"1.0\"?>\n<gameList>\n$sb</gameList>\n"
                listFile.writeText(text)
            }
            summary.put(JSONObject().put("system", id).put("added", added).put("kept", known.size))
        }
        return JSONObject().put("dest", dest.path).put("games", games).put("images", images).put("systems", summary)
    }
}
