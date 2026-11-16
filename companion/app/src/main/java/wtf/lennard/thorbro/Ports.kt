package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Native ports and recompilations (they are Android apps, not ROMs). They show up as console
 * "ports", start like apps, and their save folders take part in backups and the RomM sync.
 * RomM attaches saves to a game, so each port gets a tiny placeholder file in RomM's library.
 */
object Ports {
    class Port(val id: String, val name: String, val pkg: String, val saveDirs: List<String>)

    private const val DATA = "/storage/emulated/0/Android/data"
    val known = listOf(
        Port("oot-bananative", "Ocarina of Time (Banana Native)", "dev.bananative.android", listOf("$DATA/dev.bananative.android/files/saves")),
        Port("soh", "Ship of Harkinian", "com.dishii.soh", listOf("/storage/emulated/0/SOH/Save", "$DATA/com.dishii.soh/files/Save")),
        Port("2s2h", "2 Ship 2 Harkinian", "com.dishii.twoship", listOf("/storage/emulated/0/2S2H/Save")),
        Port("dusklight", "Dusklight", "dev.twilitrealm.dusk", listOf("$DATA/dev.twilitrealm.dusk/files/saves")),
        // saves live in app-private storage; files/ holds only the game data (6 GB), so nothing to back up
        Port("skate3", "Skate 3", "chat.buku.skate3", emptyList()),
    )

    fun installed(ctx: Context) = known.filter { runCatching { ctx.packageManager.getPackageInfo(it.pkg, 0) }.isSuccess }

    /** placeholder files live in ThorBro's own storage; RomM gets a copy so saves can attach to it */
    private fun placeholder(ctx: Context, p: Port): File = File(ctx.filesDir, "ports/${p.name}.port").apply {
        if (!exists()) { parentFile?.mkdirs(); writeText(JSONObject().put("thorbroPort", p.id).put("package", p.pkg).put("name", p.name).toString()) }
    }

    /** ROM-like entries for the library */
    fun entries(ctx: Context): List<JSONObject> = installed(ctx).map { p ->
        val f = placeholder(ctx, p)
        JSONObject().put("name", p.name).put("file", f.name).put("path", f.path).put("dir", false).put("ext", "app")
            .put("size", 0L).put("mtime", 0L).put("volume", ctx.filesDir.path).put("package", p.pkg).put("portId", p.id)
    }

    fun saveDirs(ctx: Context): List<Pair<Port, File>> = installed(ctx).flatMap { p ->
        p.saveDirs.map { File(it) }.filter { it.isDirectory && it.canRead() && (it.listFiles()?.isNotEmpty() == true) }.map { p to it }
    }

    fun icon(ctx: Context, pkg: String): File? = runCatching {
        File(ctx.cacheDir, "port-icons/$pkg.png").apply { if (!exists()) { parentFile?.mkdirs(); writeBytes(Apps.icon(ctx, pkg, 256)) } }
    }.getOrNull()
}
