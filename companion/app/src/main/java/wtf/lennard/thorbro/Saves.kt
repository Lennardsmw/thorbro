package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backs up emulator save data as zip snapshots in <storage>/ThorBro/save-backups/<location>/<time>.zip.
 * A snapshot is only written when something changed; the newest [KEEP] per location are kept.
 * Restoring first snapshots the current state, then extracts over it (extra files are not deleted).
 */
object Saves {
    private const val KEEP = 30

    /** where a location's files are; [filter] limits to matching file names (e.g. .sav next to ROMs) */
    class Location(val id: String, val name: String, val emulator: String, val dir: File, val filter: Regex? = null, val maxDepth: Int = 12)

    private val dataDir = "/storage/emulated/0/Android/data"

    fun locations(ctx: Context): List<Location> {
        val out = mutableListOf<Location>()
        // SD cards (FAT/exFAT) ignore case: "Saves" and "saves" are the same folder there
        fun add(l: Location) { if (l.dir.isDirectory && l.dir.canRead() && out.none { it.dir.path.equals(l.dir.path, true) && it.filter == l.filter }) out += l }
        val vols = Cocoon.roots(ctx).map { it.parentFile!! }.toMutableSet().apply { add(Storage.primary) }
            .plus(ctx.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { it.directory })
            .toSet()
        val romRoots = vols.mapNotNull { v -> v.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("ROMs", true) } }

        add(Location("retroarch-saves", "RetroArch Spielstände", "RetroArch", File(Storage.primary, "RetroArch/saves")))
        add(Location("retroarch-states", "RetroArch Savestates", "RetroArch", File(Storage.primary, "RetroArch/states")))
        for (r in romRoots) {
            val tag = if (r.path.startsWith(Storage.primary.path)) "intern" else "sd"
            add(Location("ppsspp-$tag", "PPSSPP Spielstände", "PPSSPP", File(r, "psp/PSP/SAVEDATA")))
            add(Location("ppsspp-states-$tag", "PPSSPP Savestates", "PPSSPP", File(r, "psp/PSP/PPSSPP_STATE")))
            add(Location("melonds-$tag", "melonDS (.sav neben den ROMs)", "melonDS", File(r, "nds"), Regex("(?i).*\\.(sav|dsv)$"), 2))
            add(Location("azahar-$tag", "Azahar Spielstände", "Azahar", File(r, "n3ds/sdmc/Nintendo 3DS"), Regex("(?i)^(?!.*/content/).*"), 12))
            add(Location("gba-sav-$tag", "GBA (.sav neben den ROMs)", "mGBA/RetroArch", File(r, "gba"), Regex("(?i).*\\.(sav|srm)$"), 2))
        }
        add(Location("ppsspp-intern-root", "PPSSPP Spielstände", "PPSSPP", File(Storage.primary, "PSP/SAVEDATA")))
        for (v in vols) {
            val tag = if (v.path == Storage.primary.path) "intern" else "sd"
            add(Location("saves-$tag", "Ordner „${File(v, "Saves").name}“ (${if (tag == "sd") "SD" else "intern"})", "Switch/Eden-Export", File(v, "Saves")))
            add(Location("saves2-$tag", "Ordner „saves“ (${if (tag == "sd") "SD" else "intern"})", "Switch/Syncthing", File(v, "saves"), Regex("^(?!\\.st).*")))
        }
        for ((p, dir) in Ports.saveDirs(ctx)) add(Location("port-${p.id}", p.name, "Port/Recomp", dir))
        add(Location("dolphin-gc", "Dolphin GameCube", "Dolphin", File(dataDir, "org.dolphinemu.dolphinemu/files/GC")))
        add(Location("dolphin-wii", "Dolphin Wii", "Dolphin", File(dataDir, "org.dolphinemu.dolphinemu/files/Wii/title")))
        add(Location("nethersx2", "NetherSX2 Memory Cards", "NetherSX2", File(dataDir, "xyz.aethersx2.android/files/memcards")))
        add(Location("duckstation", "DuckStation Memory Cards", "DuckStation", File(dataDir, "com.github.stenzek.duckstation/files/memcards")))
        add(Location("cemu", "Cemu Spielstände", "Cemu", File(dataDir, "info.cemu.cemu/files/mlc01/usr/save")))
        return out
    }

    private fun backupRoot(): File {
        // prefer the SD card: more space, and it survives an app reinstall
        val sd = File("/storage").listFiles()?.firstOrNull { Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(it.name) && it.canWrite() }
        return File(sd ?: Storage.primary, "ThorBro/save-backups").apply { mkdirs() }
    }

    private fun files(l: Location): List<File> = l.dir.walkTopDown().maxDepth(l.maxDepth)
        .onEnter { !it.name.startsWith(".st") && it.name != "save-backups" }
        .filter { it.isFile && !it.name.startsWith("._") }
        .filter { f -> l.filter?.matches(f.relativeTo(l.dir).path) ?: true }
        .toList()

    private fun fingerprint(fs: List<File>, base: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        fs.sortedBy { it.path }.forEach { md.update("${it.relativeTo(base).path}|${it.length()}|${it.lastModified()}\n".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun overview(ctx: Context): JSONObject {
        val arr = JSONArray()
        for (l in locations(ctx)) {
            val fs = files(l)
            val snaps = snapshots(l.id)
            arr.put(JSONObject()
                .put("id", l.id).put("name", l.name).put("emulator", l.emulator).put("path", l.dir.path)
                .put("files", fs.size).put("size", fs.sumOf { it.length() })
                .put("lastChange", fs.maxOfOrNull { it.lastModified() } ?: 0)
                .put("snapshots", JSONArray(snaps.map { JSONObject().put("name", it.name).put("path", it.path).put("size", it.length()).put("time", it.lastModified()) })))
        }
        return JSONObject().put("root", backupRoot().path).put("auto", auto(ctx)).put("locations", arr)
    }

    private fun snapshots(id: String) =
        (File(backupRoot(), id).listFiles { f -> f.name.endsWith(".zip") }?.toList() ?: emptyList()).sortedByDescending { it.name }

    /** snapshot one location (or all with id == null); returns what was written */
    @Synchronized
    fun backup(ctx: Context, id: String?, force: Boolean = false, label: String = ""): JSONArray {
        val out = JSONArray()
        for (l in locations(ctx).filter { id == null || it.id == id }) {
            val fs = files(l)
            if (fs.isEmpty()) continue
            val fp = fingerprint(fs, l.dir)
            val dir = File(backupRoot(), l.id).apply { mkdirs() }
            val last = File(dir, "last.fingerprint")
            if (!force && last.exists() && last.readText() == fp) { out.put(JSONObject().put("id", l.id).put("skipped", "unverändert")); continue }
            val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + (if (label.isNotEmpty()) "-$label" else "") + ".zip"
            val zip = File(dir, name)
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                z.setComment(l.dir.path)
                for (f in fs) {
                    z.putNextEntry(ZipEntry(f.relativeTo(l.dir).path).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            last.writeText(fp)
            snapshots(l.id).drop(KEEP).forEach { it.delete() }
            out.put(JSONObject().put("id", l.id).put("snapshot", zip.path).put("files", fs.size))
        }
        return out
    }

    /** restore a snapshot zip into its location; snapshots the current state first */
    @Synchronized
    fun restore(ctx: Context, zip: File): JSONObject {
        val locId = zip.parentFile?.name ?: return JSONObject().put("error", "unbekannte Sicherung")
        val l = locations(ctx).firstOrNull { it.id == locId } ?: return JSONObject().put("error", "Speicherort $locId nicht gefunden")
        if (!zip.path.startsWith(backupRoot().path)) return JSONObject().put("error", "keine ThorBro-Sicherung")
        backup(ctx, l.id, force = true, label = "vor-wiederherstellung")
        var n = 0
        val base = l.dir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val target = File(l.dir, e.name).canonicalFile
                if (!target.path.startsWith("$base/")) continue // zip-slip guard
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { z.copyTo(it) }
                if (e.time > 0) target.setLastModified(e.time)
                n++
            }
        }
        File(backupRoot(), "${l.id}/last.fingerprint").delete()
        return JSONObject().put("ok", true).put("restored", n).put("into", l.dir.path)
    }

    fun auto(ctx: Context) = Prefs.of(ctx).getBoolean("saveAuto", true)
    fun setAuto(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putBoolean("saveAuto", on).apply()
}
