package wtf.lennard.thorbro

import android.content.Context
import android.os.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds ROMs on all storage volumes and groups them by console.
 *
 * A file belongs to a console when a folder above it is named after that console
 * (`ROMs/gba`, `Gameboy Advance`, `PS2` ...) and its extension fits, or, outside such folders,
 * when its extension belongs to exactly one console (`.nds`, `.3ds`, `.nsp` ...).
 * Ambiguous files outside console folders (`.iso`, `.chd`, `.cue` ...) are listed as unassigned.
 */
object Roms {
    class Console(val id: String, val name: String, val exts: Set<String>, aliases: List<String>, val folderGames: Boolean = false) {
        val keys = (aliases + id).map(::key).toSet()
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    val systems = listOf(
        Console("nes", "NES / Famicom", setOf("nes", "fds", "unf", "unif"), listOf("famicom", "fds", "Nintendo Entertainment System")),
        Console("snes", "SNES / Super Famicom", setOf("sfc", "smc", "bs", "st"), listOf("sfc", "superfamicom", "Super Nintendo", "Super Nintendo Entertainment System")),
        Console("n64", "Nintendo 64", setOf("n64", "z64", "v64"), listOf("Nintendo 64")),
        Console("gb", "Game Boy", setOf("gb"), listOf("gameboy", "Game Boy")),
        Console("gbc", "Game Boy Color", setOf("gbc"), listOf("Gameboy Color", "Game Boy Color")),
        Console("gba", "Game Boy Advance", setOf("gba"), listOf("Gameboy Advance", "Game Boy Advance")),
        Console("nds", "Nintendo DS", setOf("nds", "dsi", "srl"), listOf("Nintendo DS")),
        Console("n3ds", "Nintendo 3DS", setOf("3ds", "cia", "cci", "cxi", "3dsx", "z3dsx"), listOf("3ds", "Nintendo 3DS")),
        Console("virtualboy", "Virtual Boy", setOf("vb", "vboy"), listOf("Virtual Boy")),
        Console("gc", "GameCube", setOf("rvz", "gcz", "iso", "ciso", "gcm", "wia"), listOf("gamecube", "ngc", "Nintendo GameCube")),
        Console("wii", "Wii", setOf("rvz", "wbfs", "iso", "wia", "ciso", "wad"), listOf("Nintendo Wii")),
        Console("wiiu", "Wii U", setOf("wua", "wud", "wux", "rpx"), listOf("Wii U", "Nintendo Wii U"), folderGames = true),
        Console("switch", "Switch", setOf("nsp", "xci", "nsz", "xcz"), listOf("Nintendo Switch")),
        Console("psx", "PlayStation", setOf("chd", "cue", "pbp", "m3u", "iso", "ecm", "img", "ccd", "mds"), listOf("ps1", "playstation", "PlayStation 1")),
        Console("ps2", "PlayStation 2", setOf("iso", "chd", "cso", "zso", "gz", "cue"), listOf("PlayStation 2")),
        Console("ps3", "PlayStation 3", setOf("iso", "pkg"), listOf("PlayStation 3"), folderGames = true),
        Console("psp", "PSP", setOf("iso", "cso", "pbp", "chd"), listOf("PlayStation Portable")),
        Console("psvita", "PS Vita", setOf("vpk"), listOf("vita", "PlayStation Vita"), folderGames = true),
        Console("xbox", "Xbox", setOf("iso", "xiso"), listOf("Microsoft Xbox")),
        Console("xbox360", "Xbox 360", setOf("iso", "xex", "god"), listOf("Xbox 360")),
        Console("pc", "PC (Windows)", setOf("exe"), listOf("windows", "pcgames", "Windows Games", "PC Games"), folderGames = true),
        Console("dreamcast", "Dreamcast", setOf("gdi", "cdi", "chd", "cue"), listOf("Sega Dreamcast")),
        Console("saturn", "Saturn", setOf("cue", "chd", "iso"), listOf("Sega Saturn")),
        Console("megadrive", "Mega Drive / Genesis", setOf("md", "gen", "smd", "bin"), listOf("genesis", "Sega Genesis", "Mega Drive", "Sega Mega Drive")),
        Console("segacd", "Mega-CD / Sega CD", setOf("cue", "chd", "iso"), listOf("megacd", "Sega CD", "Mega CD")),
        Console("mastersystem", "Master System", setOf("sms"), listOf("Sega Master System")),
        Console("gamegear", "Game Gear", setOf("gg"), listOf("Sega Game Gear")),
        Console("pcengine", "PC Engine / TurboGrafx", setOf("pce", "sgx", "cue", "chd"), listOf("tg16", "turbografx", "TurboGrafx 16")),
        Console("atari2600", "Atari 2600", setOf("a26"), listOf("Atari 2600")),
        Console("lynx", "Atari Lynx", setOf("lnx"), listOf("Atari Lynx")),
        Console("ngp", "Neo Geo Pocket", setOf("ngp", "ngc"), listOf("Neo Geo Pocket", "ngpc")),
        Console("wonderswan", "WonderSwan", setOf("ws", "wsc"), listOf("WonderSwan Color", "wswan", "wswanc")),
        Console("arcade", "Arcade", setOf(), listOf("mame", "fbneo", "fba", "neogeo")),
    )

    private val byKey = systems.flatMap { s -> s.keys.map { it to s } }.toMap()
    private val archives = setOf("zip", "7z")
    /** Too generic to mean "ROM" outside a console folder (.bin data, .md markdown ...). */
    private val notUniqueEnough = setOf("bin", "md", "st", "bs", "img", "gz", "pkg", "wad", "god", "ccd", "mds", "ecm", "m3u", "srl", "gen", "ws", "app", "exe")
    /** Extensions that belong to exactly one console. */
    private val unique: Map<String, Console> = systems.flatMap { s -> s.exts.map { it to s } }
        .groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value[0] }
        .filterKeys { it !in notUniqueEnough }
    /** ROM-like extensions that need a console folder to be placed (".iso" could be five consoles). */
    private val ambiguous = systems.flatMap { it.exts }.toSet() - unique.keys - setOf("bin", "img", "gz", "pkg", "wad", "god", "exe")

    private val skipDirs = setOf(
        "android", "dcim", "pictures", "movies", "music", "podcasts", "ringtones", "alarms", "notifications",
        "audiobooks", "recordings", "lost.dir", "system volume information", "bios", "saves", "save-backups",
        "savestates", "states", "screenshots", "cheats", "shaders", "cores", "thumbnails", "media", "downloaded_media",
        "savedata", "sdmc", "nand", "backups", "artwork", "covers", "cache", "logs", "config", "memcards", "sstates",
    )
    private const val MAX_DEPTH = 10

    @Volatile private var cache: JSONObject? = null
    fun invalidate() { cache = null }
    /** the console an extension belongs to when only one has it (.nds → nds; .iso → null) */
    fun uniqueSystem(ext: String): String? = unique[ext.lowercase()]?.id
    /** the console a folder name stands for ("PS2", "Game Boy Advance" …), null = none; skipped folders count too */
    fun consoleFor(name: String): String? = byKey[key(name)]?.id ?: name.lowercase().takeIf { it in skipDirs }

    @Synchronized
    fun scan(ctx: Context, refresh: Boolean): JSONObject {
        if (!refresh) cache?.let { return it }
        val found = LinkedHashMap<String, MutableList<JSONObject>>()
        val roots = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        for (vol in volumes(ctx)) {
            vol.listFiles()?.firstOrNull { it.isDirectory && key(it.name) in setOf("roms", "rom", "games", "emulation") }
                ?.let { roots += it.path }
            walk(vol, null, 0, vol, found)
        }
        Ports.entries(ctx).takeIf { it.isNotEmpty() }?.let { found["ports"] = it.toMutableList() }
        val list = JSONArray()
        val order = systems.map { it.id } + "ports" + "_unassigned"
        for (id in order) {
            val roms = found[id] ?: continue
            val sys = systems.firstOrNull { it.id == id }
            roms.sortBy { it.getString("name").lowercase() }
            list.put(JSONObject()
                .put("id", id)
                .put("name", sys?.name ?: if (id == "ports") "Ports & Recomps" else "Nicht zugeordnet")
                .put("count", roms.size)
                .put("size", roms.sumOf { it.getLong("size") })
                .put("roms", JSONArray(roms)))
        }
        return JSONObject()
            .put("scannedAt", System.currentTimeMillis())
            .put("tookMs", System.currentTimeMillis() - t0)
            .put("romRoots", JSONArray(roots))
            .put("systemIds", JSONArray(systems.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("systems", list)
            .also { cache = it }
            .also { if (refresh) kotlin.concurrent.thread(isDaemon = true) { runCatching { Discs.sweep(ctx.applicationContext) } } }
    }

    private fun volumes(ctx: Context): List<File> =
        ctx.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }

    private fun walk(dir: File, system: Console?, depth: Int, volume: File, out: MutableMap<String, MutableList<JSONObject>>) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        val names = entries.mapTo(HashSet()) { it.name.lowercase() }
        for (f in entries) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                val lower = name.lowercase()
                if (lower in skipDirs || lower.endsWith("-work")) continue
                val sub = byKey[key(name)] ?: system
                // PC (Windows): every folder in ROMs/pc is one game, whatever it holds
                if (system?.id == "pc") { add(out, "pc", f, volume, isDir = true); continue }
                // Folder games (Wii U, PS3, Vita): a folder without ROM files of its own is one game.
                if (system != null && system.folderGames && sub === system && !hasRomFiles(f, system)) {
                    add(out, system.id, f, volume, isDir = true)
                    continue
                }
                walk(f, sub, depth + 1, volume, out)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) continue
            // .bin tracks next to a .cue belong to that game (only Mega Drive uses .bin as a ROM)
            if (ext == "bin" && names.any { it.endsWith(".cue") }) continue
            when {
                system != null && (ext in system.exts || ext in archives) -> add(out, system.id, f, volume)
                system == null && unique[ext] != null -> add(out, unique[ext]!!.id, f, volume)
                system == null && ext in ambiguous -> add(out, "_unassigned", f, volume)
                system != null && unique[ext] != null -> add(out, unique[ext]!!.id, f, volume) // e.g. a .gba in "drop/gba-ish" folder
            }
        }
    }

    private fun hasRomFiles(dir: File, system: Console): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.substringAfterLast('.', "").lowercase() in system.exts } ?: false

    private fun add(out: MutableMap<String, MutableList<JSONObject>>, id: String, f: File, volume: File, isDir: Boolean = false) {
        val display = if (isDir) f.name else f.name.substringBeforeLast('.')
        val o = JSONObject()
        if (id == "switch") Cleanup.switchId(f.name)?.let { s ->
            o.put("titleId", s.titleId).put("switchKind", s.kind).put("baseId", s.baseId).put("version", s.version)
            val up = Regex("\\[Up (v[^\\]]+)]").find(f.name)?.groupValues?.get(1)
            o.put("versionLabel", up ?: if (s.version > 0) "v${s.version / 65536}" else "v0")
        }
        out.getOrPut(id) { mutableListOf() }.add(o
            .put("name", display)
            .put("file", f.name)
            .put("path", f.path)
            .put("dir", isDir)
            .put("ext", if (isDir) "ordner" else f.name.substringAfterLast('.', "").lowercase())
            .put("size", if (isDir) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length())
            .put("mtime", f.lastModified())
            .put("volume", volume.path))
    }
}
