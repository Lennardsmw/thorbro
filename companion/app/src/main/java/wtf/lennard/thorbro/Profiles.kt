package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Player profiles like on the Switch: each profile has its own save per game.
 *
 * Only one profile's save can sit in the emulator's save location at a time ("live"). When a game is
 * started for another profile, the live save is packed into the old profile's stash
 * (<storage>/ThorBro/profiles/<profile>/<game>.zip) and the new profile's stash is unpacked in its place.
 * In RomM a profile is a save slot in the signed-in account; the first profile uses the default slot,
 * so nothing changes for people with one profile.
 *
 * Only saves that belong to exactly one game are swapped (RetroArch/GBA/NDS .sav, ports). Memory cards
 * hold many games, and Switch saves live inside Eden, which has its own user profiles.
 */
object Profiles {
    const val MAIN = "main"
    private val colors = listOf(0x4F8EF7, 0xF5A524, 0x3CCB7F, 0xE5484D, 0xA855F7, 0x14B8A6, 0xEC4899, 0x84CC16)

    class Profile(val id: String, val name: String, val slot: String?, val color: Int) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("slot", slot ?: JSONObject.NULL)
            .put("color", "#%06X".format(color and 0xFFFFFF))
    }

    fun supports(system: String) = system != "switch"

    // ---------- the profile list ----------

    @Synchronized
    fun list(ctx: Context): List<Profile> {
        val arr = runCatching { JSONArray(Prefs.of(ctx).getString("profiles", "[]")) }.getOrDefault(JSONArray())
        val out = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Profile(it.getString("id"), it.getString("name"), if (it.isNull("slot")) null else it.optString("slot").ifEmpty { null }, it.optInt("color", colors[0]))
        }
        return if (out.any { it.id == MAIN }) out else listOf(Profile(MAIN, "Spieler 1", null, colors[0])) + out
    }

    private fun store(ctx: Context, l: List<Profile>) = Prefs.of(ctx).edit()
        .putString("profiles", JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("slot", it.slot ?: JSONObject.NULL).put("color", it.color) }).toString())
        .apply()

    fun get(ctx: Context, id: String) = list(ctx).firstOrNull { it.id == id }

    /** the profile picker shows up from the second profile on */
    fun needsPicker(ctx: Context) = list(ctx).size >= 2

    fun json(ctx: Context): JSONObject =
        JSONObject().put("profiles", JSONArray(list(ctx).map { it.json() })).put("needsPicker", needsPicker(ctx))

    private fun cleanName(name: String) = name.trim().replace(Regex("\\s+"), " ").take(24)

    @Synchronized
    fun add(ctx: Context, name: String): JSONObject {
        val n = cleanName(name)
        val l = list(ctx)
        if (n.isEmpty()) return JSONObject().put("error", "Name fehlt")
        if (l.any { it.name.equals(n, true) || it.slot.equals(n, true) }) return JSONObject().put("error", "„$n“ gibt es schon")
        if (l.size >= 8) return JSONObject().put("error", "Höchstens 8 Profile")
        // the RomM slot is fixed at creation, so renaming later does not orphan the saves already in RomM
        val p = Profile("p" + System.currentTimeMillis().toString(36), n, n, colors.firstOrNull { c -> l.none { it.color == c } } ?: colors[l.size % colors.size])
        store(ctx, l + p)
        return JSONObject().put("ok", true).put("profile", p.json())
    }

    @Synchronized
    fun rename(ctx: Context, id: String, name: String): JSONObject {
        val n = cleanName(name)
        val l = list(ctx)
        if (n.isEmpty()) return JSONObject().put("error", "Name fehlt")
        if (l.any { it.id != id && it.name.equals(n, true) }) return JSONObject().put("error", "„$n“ gibt es schon")
        if (l.none { it.id == id }) return JSONObject().put("error", "Profil nicht gefunden")
        store(ctx, l.map { if (it.id == id) Profile(it.id, n, it.slot, it.color) else it })
        return JSONObject().put("ok", true)
    }

    /** removes a profile; games it was playing get the first profile's save back. Its stash is kept in save-backups. */
    fun remove(ctx: Context, id: String): JSONObject { synchronized(RommSync) {
        if (id == MAIN) return JSONObject().put("error", "Das erste Profil bleibt")
        val p = get(ctx, id) ?: return JSONObject().put("error", "Profil nicht gefunden")
        for ((rom, owner) in live(ctx)) if (owner == id) activate(ctx, rom, MAIN).optString("error").takeIf { it.isNotEmpty() }?.let {
            return JSONObject().put("error", "Spielstand von ${File(rom).name} ließ sich nicht zurücktauschen: $it")
        }
        val dir = stashDir(ctx, id)
        if (dir.isDirectory) dir.renameTo(File(Saves.backupRoot(), "_profil-${p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${System.currentTimeMillis()}"))
        synchronized(this) { store(ctx, list(ctx).filter { it.id != id }) }
        return JSONObject().put("ok", true)
    } }

    // ---------- whose save is live, per game ----------

    private fun liveFile(ctx: Context) = File(ctx.filesDir, "profiles-live.json")

    @Synchronized
    private fun live(ctx: Context): Map<String, String> = runCatching {
        val o = JSONObject(liveFile(ctx).readText()); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun setLive(ctx: Context, romPath: String, id: String) {
        val o = runCatching { JSONObject(liveFile(ctx).readText()) }.getOrDefault(JSONObject())
        if (id == MAIN) o.remove(romPath) else o.put(romPath, id)
        liveFile(ctx).writeText(o.toString())
    }

    /** the profile whose save is in the emulator's folder for this game right now */
    fun owner(ctx: Context, romPath: String): String = live(ctx)[romPath]?.takeIf { id -> list(ctx).any { it.id == id } } ?: MAIN

    /** RomM save slot of a profile (null = default slot) */
    fun slot(ctx: Context, id: String): String? = get(ctx, id)?.slot

    // ---------- swapping saves ----------

    /** the live save files/folders of one game */
    fun items(ctx: Context, romPath: String): List<RommSync.SaveItem> =
        RommSync.localSaves(ctx).filter { it.rom.optString("path") == romPath && supports(it.system) }

    private fun stashDir(ctx: Context, id: String) = File(Saves.backupRoot().parentFile, "profiles/$id")
    private fun key(romPath: String) = MessageDigest.getInstance("SHA-1").digest(romPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun stash(ctx: Context, id: String, romPath: String) = File(stashDir(ctx, id), key(romPath) + ".zip")

    /** does this profile have a (stashed or live) save for the game? */
    fun hasSave(ctx: Context, id: String, romPath: String) =
        if (owner(ctx, romPath) == id) items(ctx, romPath).isNotEmpty() else stash(ctx, id, romPath).isFile

    /**
     * Makes [id]'s save the live one for this game. The current owner's save is packed first and only removed
     * once the pack is written; a local snapshot (save-backups) is taken before anything is touched.
     */
    fun activate(ctx: Context, romPath: String, id: String): JSONObject { synchronized(RommSync) {
        val p = get(ctx, id) ?: return JSONObject().put("error", "Profil nicht gefunden")
        val cur = owner(ctx, romPath)
        if (cur == id) return JSONObject().put("ok", true).put("profile", p.name).put("switched", false)
        runCatching { Saves.backup(ctx, null, label = "vor-profilwechsel") }
        val items = items(ctx, romPath)
        runCatching { GameSave.backup(ctx, romPath, GameSave.Reason.PROFILE, items) }
        try {
            pack(ctx, stash(ctx, cur, romPath), romPath, items)
        } catch (e: Exception) {
            Logs.e("profiles", "Spielstand von $cur nicht gepackt", e)
            return JSONObject().put("error", "Spielstand konnte nicht weggepackt werden: ${e.message}")
        }
        items.forEach { clear(it.file, it.isFolder) }
        val n = runCatching { unpack(stash(ctx, id, romPath)) }.getOrElse { e ->
            Logs.e("profiles", "Spielstand von $id nicht entpackt", e)
            // put the old owner's save back, so nothing is lost
            runCatching { unpack(stash(ctx, cur, romPath)) }
            return JSONObject().put("error", "Spielstand von ${p.name} ließ sich nicht auspacken: ${e.message}")
        }
        setLive(ctx, romPath, id)
        Logs.i("profiles", "${File(romPath).name}: ${get(ctx, cur)?.name ?: cur} → ${p.name} ($n Dateien)")
        return JSONObject().put("ok", true).put("profile", p.name).put("switched", true).put("files", n)
    } }

    /** starts a game for a profile: swap the save, then hand off to the emulator */
    fun launch(ctx: Context, system: String, rom: File, emuId: String?, profile: String?): JSONObject {
        if (profile != null && supports(system)) {
            val a = activate(ctx, rom.path, profile)
            if (a.has("error")) return a
        }
        return Emulators.launch(ctx, system, rom, emuId)
    }

    /** removes the save files; folders stay, since games and emulators expect their layout (3DS: data/00000001/save_data/) */
    private fun clear(f: File, folder: Boolean) {
        if (folder) SaveFs.clearFiles(f) else SaveFs.delete(f)
    }

    /** zip: "manifest.json" = [{i, path, folder}], files as "<i>" or "<i>/<relative path>" */
    internal fun pack(ctx: Context, zip: File, romPath: String, items: List<RommSync.SaveItem>) {
        if (items.isEmpty()) { zip.delete(); return }
        zip.parentFile?.mkdirs()
        val tmp = File(zip.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            val man = JSONArray()
            items.forEachIndexed { i, it -> man.put(JSONObject().put("i", i).put("path", it.file.path).put("folder", it.isFolder)) }
            z.putNextEntry(ZipEntry("manifest.json")); z.write(JSONObject().put("rom", romPath).put("items", man).toString().toByteArray()); z.closeEntry()
            items.forEachIndexed { i, it ->
                if (it.isFolder) it.file.walkTopDown().filter { f -> f.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("$i/" + f.relativeTo(it.file).path).apply { time = f.lastModified() }); f.inputStream().use { s -> s.copyTo(z) }; z.closeEntry()
                } else {
                    z.putNextEntry(ZipEntry("$i").apply { time = it.file.lastModified() }); it.file.inputStream().use { s -> s.copyTo(z) }; z.closeEntry()
                }
            }
        }
        if (!tmp.renameTo(zip)) { zip.delete(); if (!tmp.renameTo(zip)) error("${zip.name} nicht schreibbar") }
    }

    /** writes a stash back to the paths it came from; returns the number of files */
    internal fun unpack(zip: File): Int {
        if (!zip.isFile) return 0
        val items = ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null && e.name != "manifest.json") e = z.nextEntry
            if (e == null) error("Profil-Paket ohne Inhaltsverzeichnis")
            JSONObject(z.readBytes().decodeToString()).getJSONArray("items")
        }
        val targets = (0 until items.length()).map { items.getJSONObject(it) }.associate { it.getInt("i").toString() to (File(it.getString("path")) to it.getBoolean("folder")) }
        var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || e.name == "manifest.json") continue
                val (dir, folder) = targets[e.name.substringBefore('/')] ?: continue
                val t = if (folder) File(dir, e.name.substringAfter('/')).canonicalFile else dir
                if (folder && !t.path.startsWith(dir.canonicalPath + "/")) continue // zip-slip guard
                SaveFs.write(t, z)
                SaveFs.touch(t, e.time)
                n++
            }
        }
        return n
    }

    /** profiles with the state of one game: whose save is live, who has one */
    fun forGame(ctx: Context, romPath: String): JSONArray {
        val own = owner(ctx, romPath)
        return JSONArray(list(ctx).map { it.json().put("active", it.id == own).put("hasSave", hasSave(ctx, it.id, romPath)) })
    }
}
