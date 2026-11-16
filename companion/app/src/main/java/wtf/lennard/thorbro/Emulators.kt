package wtf.lennard.thorbro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Starts a ROM in an emulator on the device. Most emulators want a SAF document URI and permission
 * for it; ThorBro hands out the folder grant the user gave once ("ROM-Ordner freigeben"), the same
 * way Android frontends like Cocoon or ES-DE do. RetroArch takes a plain path.
 */
object Emulators {
    enum class Pass { DATA, EXTRA, RETROARCH }

    class Emu(
        val id: String, val name: String, val pkg: String, val activity: String,
        val pass: Pass, val action: String = Intent.ACTION_VIEW, val extra: String? = null,
        val systems: Set<String>,
    )

    private const val RA = "com.retroarch.aarch64"
    val all = listOf(
        Emu("eden", "Eden", "dev.eden.eden_emulator", "org.yuzu.yuzu_emu.activities.EmulationActivity", Pass.DATA, systems = setOf("switch")),
        Emu("azahar", "Azahar", "org.azahar_emu.azahar", "org.citra.citra_emu.activities.EmulationActivity", Pass.DATA, systems = setOf("n3ds")),
        Emu("melonds", "melonDS", "me.magnum.melonds", "me.magnum.melonds.ui.emulator.EmulatorActivity", Pass.EXTRA, "me.magnum.melonds.LAUNCH_ROM", "uri", setOf("nds")),
        Emu("dolphin", "Dolphin", "org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinemu.ui.main.MainActivity", Pass.EXTRA, Intent.ACTION_VIEW, "AutoStartFile", setOf("gc", "wii")),
        Emu("cemu", "Cemu", "info.cemu.cemu", "info.cemu.cemu.emulation.EmulationActivity", Pass.DATA, systems = setOf("wiiu")),
        Emu("ppsspp", "PPSSPP", "org.ppsspp.ppsspp", "org.ppsspp.ppsspp.PpssppActivity", Pass.DATA, systems = setOf("psp")),
        Emu("nethersx2", "NetherSX2", "xyz.aethersx2.android", "xyz.aethersx2.android.EmulationActivity", Pass.EXTRA, Intent.ACTION_MAIN, "bootPath", setOf("ps2")),
        Emu("duckstation", "DuckStation", "com.github.stenzek.duckstation", "com.github.stenzek.duckstation.EmulationActivity", Pass.EXTRA, Intent.ACTION_MAIN, "bootPath", setOf("psx")),
        Emu("retroarch", "RetroArch", RA, "com.retroarch.browser.retroactivity.RetroActivityFuture", Pass.RETROARCH,
            systems = setOf("nes", "snes", "n64", "gb", "gbc", "gba", "nds", "virtualboy", "psx", "megadrive", "segacd", "mastersystem",
                "gamegear", "pcengine", "atari2600", "lynx", "ngp", "wonderswan", "arcade", "saturn", "dreamcast", "psp")),
    )

    /** libretro core per console when RetroArch runs it */
    private val cores = mapOf(
        "nes" to "fceumm", "snes" to "snes9x", "n64" to "mupen64plus_next_gles3", "gb" to "gambatte", "gbc" to "gambatte",
        "gba" to "mgba", "nds" to "melondsds", "virtualboy" to "mednafen_vb", "psx" to "swanstation", "megadrive" to "genesis_plus_gx",
        "segacd" to "genesis_plus_gx", "mastersystem" to "genesis_plus_gx", "gamegear" to "genesis_plus_gx",
        "pcengine" to "mednafen_pce_fast", "atari2600" to "stella", "lynx" to "handy", "ngp" to "mednafen_ngp",
        "wonderswan" to "mednafen_wswan", "arcade" to "fbneo", "saturn" to "yabasanshiro", "dreamcast" to "flycast", "psp" to "ppsspp",
    )

    fun installed(ctx: Context) = all.filter { runCatching { ctx.packageManager.getPackageInfo(it.pkg, 0) }.isSuccess }

    /** the user's pick, else the first installed emulator that handles the console */
    /** ports are apps of their own */
    private val portApp = Emu("app", "App", "", "", Pass.DATA, systems = setOf("ports"))

    fun forSystem(ctx: Context, system: String): Emu? {
        if (system == "ports") return portApp
        val inst = installed(ctx)
        val chosen = Prefs.of(ctx).getString("emu.$system", null)
        return inst.firstOrNull { it.id == chosen && system in it.systems } ?: inst.firstOrNull { system in it.systems }
    }

    fun choose(ctx: Context, system: String, emuId: String) = Prefs.of(ctx).edit().putString("emu.$system", emuId).apply()

    fun overview(ctx: Context): JSONObject {
        val inst = installed(ctx)
        val per = JSONObject()
        Roms.systems.forEach { s ->
            val options = inst.filter { s.id in it.systems }
            if (options.isNotEmpty()) per.put(s.id, JSONObject()
                .put("current", forSystem(ctx, s.id)?.id)
                .put("options", JSONArray(options.map { JSONObject().put("id", it.id).put("name", it.name) })))
        }
        if (Ports.installed(ctx).isNotEmpty()) per.put("ports", JSONObject().put("current", "app")
            .put("options", JSONArray(listOf(JSONObject().put("id", "app").put("name", "App")))))
        return JSONObject()
            .put("systems", per)
            .put("folderGrants", JSONArray(trees(ctx).map { DocumentsContract.getTreeDocumentId(it) }))
    }

    // ---- folder grants (SAF) ----

    fun trees(ctx: Context): List<Uri> = ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    private fun docId(f: File): String? {
        val p = f.path
        return when {
            p.startsWith(Storage.primary.path + "/") -> "primary:" + p.removePrefix(Storage.primary.path + "/")
            Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/(.*)$").matches(p) ->
                Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/(.*)$").find(p)!!.let { it.groupValues[1] + ":" + it.groupValues[2] }
            else -> null
        }
    }

    /** SAF URI for a file inside a folder the user granted, plus that folder's tree URI */
    private fun safUri(ctx: Context, f: File): Pair<Uri, Uri>? {
        val id = docId(f) ?: return null
        for (tree in trees(ctx)) {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            if (id.startsWith("$treeId/") || id == treeId) return DocumentsContract.buildDocumentUriUsingTree(tree, id) to tree
        }
        return null
    }

    /** Cemu wants the .rpx inside a Wii U game folder */
    private fun target(system: String, rom: File): File? =
        if (system == "wiiu" && rom.isDirectory) rom.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.endsWith(".rpx", true) }
        else rom

    /** the display ThorBro is shown on (the Thor has two); games start there, not on the other screen */
    @Volatile var display: Int = android.view.Display.DEFAULT_DISPLAY

    fun startOnDisplay(ctx: Context, i: Intent) {
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display)
        runCatching { ctx.startActivity(i, opts.toBundle()) }.getOrElse { ctx.startActivity(i) }
    }

    /** {ok} or {error, code}; code "need_folder_grant" means: open ThorBro on the device and grant the ROM folder */
    fun launch(ctx: Context, system: String, rom: File, emuId: String? = null): JSONObject {
        if (system == "ports") {
            val p = Ports.known.firstOrNull { rom.name == "${it.name}.port" } ?: return JSONObject().put("error", "Unbekannter Port")
            return if (Apps.launch(ctx, p.pkg)) JSONObject().put("ok", true).put("emulator", p.name)
                else JSONObject().put("error", "${p.name} ist nicht installiert")
        }
        val emu = (emuId?.let { id -> installed(ctx).firstOrNull { it.id == id } }) ?: forSystem(ctx, system)
            ?: return JSONObject().put("error", "Kein passender Emulator installiert").put("code", "no_emulator")
        val file = target(system, rom) ?: return JSONObject().put("error", "Keine startbare Datei im Spielordner").put("code", "no_file")
        val i = Intent(emu.action).setComponent(ComponentName(emu.pkg, emu.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (emu.pass == Pass.RETROARCH) {
            val core = cores[system] ?: return JSONObject().put("error", "Kein RetroArch-Core für $system hinterlegt")
            i.putExtra("ROM", file.path)
                .putExtra("LIBRETRO", "/data/data/$RA/cores/${core}_libretro_android.so")
                .putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$RA/files/retroarch.cfg")
                .putExtra("QUITFOCUS", "")
        } else {
            val (uri, tree) = safUri(ctx, file)
                ?: return JSONObject().put("code", "need_folder_grant")
                    .put("error", "ROM-Ordner ist noch nicht freigegeben. Auf dem Thor in ThorBro „ROM-Ordner freigeben“ tippen.")
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            runCatching { ctx.grantUriPermission(emu.pkg, tree, flags) }
            runCatching { ctx.grantUriPermission(emu.pkg, uri, flags) }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (emu.pass == Pass.DATA) i.data = uri else i.putExtra(emu.extra, uri.toString())
            if (emu.id == "duckstation") i.putExtra("resumeState", false)
        }
        return try {
            startOnDisplay(ctx, i)
            JSONObject().put("ok", true).put("emulator", emu.name)
        } catch (e: Exception) {
            JSONObject().put("error", "${emu.name} ließ sich nicht starten: ${e.message}")
        }
    }
}
