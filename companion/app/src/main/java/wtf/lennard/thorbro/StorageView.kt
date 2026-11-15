package wtf.lennard.thorbro

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How the space on each storage is used: per console (from the ROM scan) and per top-level folder. */
object StorageView {
    @Volatile private var cache: Pair<Long, JSONObject>? = null

    private fun size(f: File): Long = if (f.isFile) f.length()
        else f.walkTopDown().onEnter { it.canRead() }.filter { it.isFile }.sumOf { it.length() }

    fun overview(ctx: Context, refresh: Boolean): JSONObject {
        cache?.let { (t, j) -> if (!refresh && System.currentTimeMillis() - t < 10 * 60_000) return j }
        val scan = Roms.scan(ctx, refresh).getJSONArray("systems")
        val vols = JSONArray()
        for (v in ctx.getSystemService(StorageManager::class.java).storageVolumes) {
            val dir = v.directory ?: continue
            val st = StatFs(dir.path)
            val systems = mutableMapOf<String, Long>()
            for (i in 0 until scan.length()) {
                val s = scan.getJSONObject(i)
                val roms = s.getJSONArray("roms")
                var sum = 0L
                for (k in 0 until roms.length()) roms.getJSONObject(k).let { if (it.getString("path").startsWith(dir.path + "/")) sum += it.getLong("size") }
                if (sum > 0) systems[s.getString("name")] = sum
            }
            val folders = (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                .map { it.name to size(it) }.filter { it.second > 50L * 1024 * 1024 }.sortedByDescending { it.second }.take(15)
            vols.put(JSONObject()
                .put("path", dir.path).put("label", v.getDescription(ctx)).put("removable", v.isRemovable)
                .put("total", st.totalBytes).put("free", st.availableBytes)
                .put("systems", JSONArray(systems.entries.sortedByDescending { it.value }.map { JSONObject().put("name", it.key).put("size", it.value) }))
                .put("folders", JSONArray(folders.map { JSONObject().put("name", it.first).put("size", it.second) })))
        }
        val out = JSONObject().put("volumes", vols).put("at", System.currentTimeMillis())
        cache = System.currentTimeMillis() to out
        return out
    }
}
