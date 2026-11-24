package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What happened between the Thor and RomM, newest first: saves up/down, games uploaded, offloaded,
 * downloaded, saves restored. Kept in the app's files (last 200 entries) for the Sync screen.
 */
object History {
    private const val MAX = 200
    private var file: File? = null
    private val lock = Any()

    fun init(ctx: Context) { if (file == null) file = File(ctx.applicationContext.filesDir, "history.json") }

    /** kind: save_up, save_down, rom_up, offload, download, restore */
    fun add(ctx: Context, kind: String, name: String, system: String = "", detail: String = "", ms: Long = 0, romId: Int = 0,
            size: Long = 0, path: String = "") = synchronized(lock) {
        init(ctx)
        val arr = read()
        val e = JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("name", name).put("system", system).put("detail", detail)
        if (ms > 0) e.put("ms", ms)
        if (romId > 0) e.put("romId", romId)
        if (size > 0) e.put("size", size)
        if (path.isNotEmpty()) e.put("file", path)
        val out = JSONArray().put(e)
        for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
        runCatching { file!!.writeText(out.toString()) }
    }

    fun list(ctx: Context, limit: Int = 50): JSONArray = synchronized(lock) {
        init(ctx)
        val a = read()
        JSONArray((0 until minOf(limit, a.length())).map { a.get(it) })
    }

    private fun read(): JSONArray = runCatching { JSONArray(file!!.readText()) }.getOrDefault(JSONArray())
}
