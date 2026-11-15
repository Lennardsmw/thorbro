package wtf.lennard.thorbro

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Every path a client sends goes through [resolve]: only internal storage and SD cards are reachable. */
object Storage {
    val primary: File = Environment.getExternalStorageDirectory().canonicalFile
    private val sdCard = Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(/.*)?$")

    fun resolve(raw: String?): File? {
        if (raw.isNullOrBlank()) return null
        val f = File(raw).canonicalFile
        val p = f.path
        val ok = p == primary.path || p.startsWith(primary.path + "/") || sdCard.matches(p)
        return if (ok) f else null
    }

    fun isRoot(f: File): Boolean =
        f.path == primary.path || Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(f.path)

    fun free(f: File): Long {
        var dir: File? = f
        while (dir != null && !dir.exists()) dir = dir.parentFile
        return try { StatFs((dir ?: primary).path).availableBytes } catch (_: Exception) { -1 }
    }

    fun volumes(ctx: Context): JSONArray {
        val out = JSONArray()
        val sm = ctx.getSystemService(StorageManager::class.java)
        for (v in sm.storageVolumes) {
            val dir = v.directory ?: continue
            val st = try { StatFs(dir.path) } catch (_: Exception) { continue }
            out.put(JSONObject()
                .put("path", dir.path)
                .put("label", v.getDescription(ctx))
                .put("removable", v.isRemovable)
                .put("total", st.totalBytes)
                .put("free", st.availableBytes))
        }
        return out
    }

    fun list(dir: File): JSONArray {
        val out = JSONArray()
        val entries = dir.listFiles() ?: return out
        entries.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).forEach {
            out.put(JSONObject()
                .put("name", it.name)
                .put("dir", it.isDirectory)
                .put("size", if (it.isFile) it.length() else 0)
                .put("mtime", it.lastModified()))
        }
        return out
    }
}
