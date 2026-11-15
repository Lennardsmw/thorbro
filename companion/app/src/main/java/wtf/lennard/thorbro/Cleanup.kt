package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds things that only take space: macOS leftovers (._*, .DS_Store), stale upload parts,
 * duplicate ROMs and outdated Switch updates. Nothing is deleted without an explicit call.
 */
object Cleanup {
    private val skip = setOf("Android", "LOST.DIR", "System Volume Information", ".thumbnails")

    private fun volumes(ctx: Context) =
        ctx.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { it.directory }

    private fun junk(ctx: Context): List<File> {
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000
        return volumes(ctx).flatMap { v ->
            v.walkTopDown().maxDepth(12).onEnter { it == v || it.name !in skip }
                .filter { f ->
                    f.isFile && (f.name.startsWith("._") || f.name == ".DS_Store" ||
                        (f.name.endsWith(".thorbro-part") && f.lastModified() < dayAgo))
                }.toList()
        }
    }

    /** cheap content check: size + SHA-1 of the first and last 1 MB */
    private fun quickHash(f: File): String = RandomAccessFile(f, "r").use { r ->
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 20)
        md.update(f.length().toString().toByteArray())
        val n1 = r.read(buf); if (n1 > 0) md.update(buf, 0, n1)
        if (f.length() > buf.size * 2) { r.seek(f.length() - buf.size); val n2 = r.read(buf); if (n2 > 0) md.update(buf, 0, n2) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** a name without site tags or random suffixes is the one to keep */
    private fun keepScore(name: String): Int {
        var s = 0
        if (Regex("(?i)axekin|\\.com|\\.fr|\\.net").containsMatchIn(name)) s -= 10
        if (Regex("-[0-9a-f]{12}\\.").containsMatchIn(name)) s -= 10
        if (Regex("(?i)patched|_edited").containsMatchIn(name)) s -= 2
        return s - name.length / 50
    }

    fun scan(ctx: Context): JSONObject {
        val j = junk(ctx)
        val scan = Roms.scan(ctx, false).getJSONArray("systems")
        val dupes = JSONArray()
        val oldUpdates = JSONArray()
        for (i in 0 until scan.length()) {
            val sys = scan.getJSONObject(i)
            val id = sys.getString("id")
            if (id == "_unassigned") continue
            val roms = sys.getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { !it.getBoolean("dir") }
            if (id == "switch") {
                // several updates for one game: all but the newest are outdated
                roms.filter { it.optString("switchKind") == "update" }.groupBy { it.optString("baseId") }.values
                    .filter { it.size > 1 }
                    .forEach { ups -> ups.sortedByDescending { it.optLong("version") }.drop(1).forEach { oldUpdates.put(it) } }
                continue
            }
            roms.groupBy { Media.titleKey(it.getString("name")) + "|" + it.getString("ext") + "|" + Media.tags(it.getString("name")).filter { t -> t in regionish }.sorted() }
                .values.filter { it.size > 1 }
                .forEach { group ->
                    val withHash = group.map { r -> r to runCatching { quickHash(File(r.getString("path"))) }.getOrDefault("") }
                    val keep = group.maxByOrNull { keepScore(it.getString("file")) }!!
                    val keepHash = withHash.first { it.first === keep }.second
                    dupes.put(JSONObject()
                        .put("system", id).put("title", keep.getString("name").substringBefore(" (").substringBefore(" ["))
                        .put("keep", keep.getString("path"))
                        .put("files", JSONArray(withHash.map { (r, h) ->
                            JSONObject().put("path", r.getString("path")).put("file", r.getString("file")).put("size", r.getLong("size"))
                                .put("identical", h.isNotEmpty() && h == keepHash && r !== keep)
                                .put("keep", r === keep)
                        })))
                }
        }
        val jByKind = j.groupBy { if (it.name.endsWith(".thorbro-part")) "parts" else "macos" }
        return JSONObject()
            .put("macos", JSONObject().put("count", jByKind["macos"]?.size ?: 0).put("size", jByKind["macos"]?.sumOf { it.length() } ?: 0L)
                .put("examples", JSONArray(jByKind["macos"]?.take(5)?.map { it.path } ?: emptyList<String>())))
            .put("parts", JSONObject().put("count", jByKind["parts"]?.size ?: 0).put("size", jByKind["parts"]?.sumOf { it.length() } ?: 0L))
            .put("duplicates", dupes)
            .put("oldUpdates", oldUpdates)
    }

    private val regionish = setOf("europe", "usa", "japan", "germany", "world", "france", "spain", "italy", "korea", "rev 1", "rev 2", "v1.1")

    /** deletes macOS leftovers and stale upload parts */
    fun removeJunk(ctx: Context): JSONObject {
        val j = junk(ctx)
        var n = 0; var bytes = 0L
        for (f in j) { val s = f.length(); if (f.delete()) { n++; bytes += s } }
        Roms.invalidate()
        return JSONObject().put("deleted", n).put("freed", bytes)
    }

    // ---- Switch title IDs ----

    data class SwitchId(val titleId: String, val kind: String, val baseId: String, val version: Long)

    /** base ends in 000, update in 800, DLC is base+0x1000 with a running number */
    fun switchId(name: String): SwitchId? {
        val id = Regex("\\[([0-9A-Fa-f]{16})]").find(name)?.groupValues?.get(1)?.uppercase() ?: return null
        val n = id.toULong(16)
        val low = (n and 0xFFFu).toInt()
        val (kind, base) = when {
            low == 0 -> "base" to n
            low == 0x800 -> "update" to (n - 0x800u)
            else -> "dlc" to ((n and 0xFFFFFFFFFFFFF000u) - 0x1000u)
        }
        val ver = Regex("\\[v(\\d+)]").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return SwitchId(id, kind, base.toString(16).uppercase().padStart(16, '0'), ver)
    }
}
