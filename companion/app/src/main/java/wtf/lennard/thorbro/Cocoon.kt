package wtf.lennard.thorbro

import android.content.Context
import android.os.storage.StorageManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads what the Cocoon frontend already scraped: <volume>/Cocoon/gamelists/<system>/gamelist.xml
 * (ES-DE format plus cocoon:* fields) and <volume>/Cocoon/downloaded_media/<system>/<kind>/<rom name>.<ext>.
 * Read-only: ThorBro never writes into Cocoon's folders.
 */
object Cocoon {
    val mediaKinds = listOf("icon", "hero", "logo", "screenshot_title", "screenshot_gameplay")
    private val imageExts = listOf("png", "jpg", "jpeg", "webp")

    class Game(val fields: Map<String, String>)

    private class Cache(val stamp: Long, val games: Map<String, Game>)
    private val cache = ConcurrentHashMap<String, Cache>()

    fun roots(ctx: Context): List<File> =
        ctx.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { it.directory }
            .map { File(it, "Cocoon") }
            .filter { File(it, "gamelists").isDirectory }

    /** file name of the ROM (as in <path>) -> Cocoon's data for it */
    fun games(ctx: Context, systemId: String): Map<String, Game> {
        val files = roots(ctx).map { File(it, "gamelists/$systemId/gamelist.xml") }.filter { it.isFile }
        val stamp = files.sumOf { it.lastModified() + it.length() }
        cache[systemId]?.takeIf { it.stamp == stamp }?.let { return it.games }
        val out = HashMap<String, Game>()
        for (f in files) runCatching { parse(f, out) }
        cache[systemId] = Cache(stamp, out)
        return out
    }

    /** Local image Cocoon downloaded for a ROM, e.g. kind "icon" (box art) or "hero". */
    fun media(ctx: Context, systemId: String, romPath: String, kind: String): File? {
        if (kind !in mediaKinds) return null
        val base = File(romPath).let { if (it.isDirectory) it.name else it.name.substringBeforeLast('.') }
        for (root in roots(ctx)) {
            val dir = File(root, "downloaded_media/$systemId/$kind")
            for (ext in imageExts) {
                val f = File(dir, "$base.$ext")
                if (f.isFile) return f
            }
        }
        return null
    }

    fun mediaKindsFor(ctx: Context, systemId: String, romPath: String): List<String> =
        mediaKinds.filter { media(ctx, systemId, romPath, it) != null }

    private fun parse(f: File, out: MutableMap<String, Game>) {
        f.inputStream().use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")
            var fields: MutableMap<String, String>? = null
            var tag: String? = null
            val text = StringBuilder()
            while (true) {
                when (p.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> {
                        if (p.name == "game") fields = HashMap()
                        else if (fields != null) { tag = p.name; text.setLength(0) }
                    }
                    XmlPullParser.TEXT -> if (tag != null) text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if (p.name == "game") {
                            val fl = fields
                            val path = fl?.get("path")
                            if (fl != null && path != null) out[path.substringAfterLast('/')] = Game(fl)
                            fields = null
                        } else if (fields != null && tag == p.name) {
                            fields[p.name] = text.toString().trim()
                            tag = null
                        }
                    }
                }
            }
        }
    }
}
