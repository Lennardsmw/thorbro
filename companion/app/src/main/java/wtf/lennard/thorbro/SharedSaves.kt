package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The shared save folder: saves that RomM users marked as public (in RomM or here) can be seen by
 * everyone and installed into a profile with one click, e.g. a 100 % save with all characters.
 *
 * A save can be several files (3DS: system_data.bin + account_data.bin). RomM stores each file on its own and
 * tags the names with the upload time ("system_data [2026-11-16_16-56-36].bin"), so files of one user, slot
 * and upload time are shown and installed as one save.
 */
object SharedSaves {
    private val tag = Regex("\\s*\\[(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})]")

    private fun romId(ctx: Context, system: String, romPath: String): Int? =
        Library.remote(ctx).find(system, File(romPath).name)?.optInt("id")?.takeIf { it > 0 }

    private fun time(s: JSONObject) = s.optString("updated_at").ifEmpty { s.optString("created_at") }

    /** file name without RomM's upload-time tag: what the emulator expects */
    fun cleanName(name: String) = tag.replace(name, "")

    /** one entry per save set: {ids, files, size, updated, …} */
    private fun groups(list: List<JSONObject>): List<List<JSONObject>> =
        list.groupBy { s ->
            val t = tag.find(s.optString("file_name"))?.groupValues?.get(1)
            if (t == null) "id:${s.getInt("id")}" else "${s.optInt("user_id")}|${Romm.slotOf(s)}|$t"
        }.values.sortedByDescending { g -> g.maxOf { time(it) } }

    private val deviceTag = Regex("\\(([^()]+)\\)\\.zip$", RegexOption.IGNORE_CASE)

    /** the device a folder save was uploaded from: "saves (AYN Thor).zip" -> "AYN Thor"; null for older uploads */
    fun deviceOf(fileName: String): String? = deviceTag.find(cleanName(fileName))?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private fun groupJson(g: List<JSONObject>) = JSONObject()
        .put("ids", JSONArray(g.map { it.getInt("id") })).put("id", g.joinToString(",") { it.getInt("id").toString() })
        .put("name", g.joinToString(" + ") { cleanName(it.optString("file_name")) })
        .put("updated", g.maxOf { time(it) }).put("size", g.sumOf { it.optLong("file_size_bytes") })
        .put("slot", Romm.slotOf(g[0]) ?: JSONObject.NULL)
        .put("device", deviceOf(g[0].optString("file_name")) ?: JSONObject.NULL)

    /** everything about one game's saves: profiles, own saves in RomM, other users' public ones */
    fun forGame(ctx: Context, system: String, romPath: String): JSONObject {
        val out = JSONObject().put("supported", Profiles.supports(system)).put("profiles", Profiles.forGame(ctx, romPath))
            .put("connected", Romm.status(ctx).optBoolean("connected"))
        if (!out.getBoolean("connected")) return out
        val id = runCatching { romId(ctx, system, romPath) }.getOrElse { return out.put("error", it.message) }
            ?: return out.put("inRomm", false)
        out.put("inRomm", true).put("romId", id)
        val profiles = Profiles.list(ctx)
        out.put("thisDevice", Device.label(ctx))
        val own = runCatching { Romm.listSaves(ctx, id) }.getOrElse { return out.put("error", it.message) }
        val ownIds = own.map { it.getInt("id") }.toSet()
        out.put("own", JSONArray(groups(own).map { g ->
            val slot = Romm.slotOf(g[0])
            groupJson(g).put("public", g.all { it.optBoolean("is_public") })
                .put("profile", profiles.firstOrNull { it.slot == slot }?.name ?: slot ?: "Standard")
        }))
        val all = runCatching { Romm.romDetail(ctx, id).optJSONArray("all_user_saves") }.getOrNull() ?: JSONArray()
        val shared = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getInt("id") !in ownIds && it.optBoolean("is_public") }
        out.put("shared", JSONArray(groups(shared).map { g -> groupJson(g).put("user", g[0].optString("username")) }))
        return out
    }

    /** share or unshare one of your own saves (all files of the set) */
    fun share(ctx: Context, saveIds: List<Int>, on: Boolean): JSONObject =
        runCatching { saveIds.forEach { Romm.setSavePublic(ctx, it, on) }; JSONObject().put("ok", true).put("public", on) }
            .getOrElse { JSONObject().put("error", it.message) }

    /**
     * Installs a RomM save set (own or shared) as [profileId]'s save for this game: the profile becomes the live
     * one, the current files are snapshotted, then the files are written where the emulator expects them.
     */
    fun install(ctx: Context, system: String, romPath: String, saveIds: List<Int>, profileId: String): JSONObject {
        if (!Profiles.supports(system)) return JSONObject().put("error", "Switch-Spielstände verwaltet Eden selbst (eigene Profile in Eden)")
        if (saveIds.isEmpty()) return JSONObject().put("error", "Kein Spielstand gewählt")
        val rom = File(romPath)
        val id = runCatching { romId(ctx, system, romPath) }.getOrElse { return JSONObject().put("error", it.message) }
            ?: return JSONObject().put("error", "Spiel ist nicht in RomM")
        val all = runCatching { Romm.romDetail(ctx, id).optJSONArray("all_user_saves") }.getOrElse { return JSONObject().put("error", it.message) } ?: JSONArray()
        val metas = saveIds.map { sid -> (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getInt("id") == sid }
            ?: return JSONObject().put("error", "Spielstand $sid nicht (mehr) sichtbar") }
        synchronized(RommSync) {
            val a = Profiles.activate(ctx, romPath, profileId)
            if (a.has("error")) return a
            val files = mutableListOf<Pair<File, String>>()
            try {
                metas.forEach { m ->
                    val name = cleanName(m.optString("file_name"))
                    val f = File(ctx.cacheDir, "shared-save-${m.getInt("id")}")
                    Romm.downloadSave(ctx, m.getInt("id"), f); files += f to name
                }
                runCatching { Saves.backup(ctx, null, label = "vor-installation") }
                val items = Profiles.items(ctx, romPath)
                val zip = files.size == 1 && files[0].second.endsWith(".zip", true)
                // a folder save: the existing one, or for 3DS the title's folder in Azahar
                val folder = items.singleOrNull()?.takeIf { it.isFolder }?.file ?: if (items.isEmpty() && system == "n3ds") N3ds.saveDir(rom, create = true) else null
                val into: File = when {
                    folder != null && zip -> folder.also { d -> SaveFs.clearDir(d); RommSync.unzipStripped(files[0].first, d) }
                    folder != null -> folder.also { d ->
                        if (system == "n3ds" && !File(d.parentFile, "${d.name}.metadata").exists())
                            return JSONObject().put("error", "Das Spiel einmal in Azahar starten und speichern, dann nochmal installieren.")
                        SaveFs.mkdirs(d); files.forEach { (f, n) -> SaveFs.copy(f, placeIn(d, File(n).name)) }
                    }
                    files.size == 1 && !zip && items.singleOrNull()?.isFolder == false -> items[0].file.also { SaveFs.copy(files[0].first, it) }
                    files.size == 1 && !zip && items.isEmpty() -> (defaultTarget(system, rom, files[0].second) ?: return JSONObject().put("error",
                        "Weiß nicht, wohin der Spielstand gehört. Einmal im Spiel speichern, dann nochmal installieren."))
                        .also { SaveFs.copy(files[0].first, it) }
                    else -> return JSONObject().put("error", "Spielstand passt nicht zu den Dateien auf dem Thor (${items.size} Spielstände, ${files.size} Dateien)")
                }
                val who = metas[0].optString("username")
                History.add(ctx, "save_install", File(romPath).nameWithoutExtension + (if (who.isNotEmpty()) " (von $who)" else ""), system, detail = a.optString("profile"))
                return JSONObject().put("ok", true).put("into", into.path).put("files", files.size).put("profile", a.optString("profile"))
            } catch (e: Exception) {
                Logs.e("profiles", "Spielstand $saveIds nicht installiert", e)
                return JSONObject().put("error", e.message ?: "Fehler")
            } finally { files.forEach { it.first.delete() } }
        }
    }

    /**
     * where a loose save file goes inside a save folder: over the file with the same name, else into the only
     * subfolder (3DS: data/00000001/save_data/), else at the top
     */
    private fun placeIn(dir: File, name: String): File {
        dir.walkTopDown().firstOrNull { it.isFile && it.name.equals(name, true) }?.let { return it }
        var d = dir
        while (true) {
            val kids = d.listFiles().orEmpty()
            val sub = kids.singleOrNull()?.takeIf { it.isDirectory } ?: break
            d = sub
        }
        return File(d, name)
    }

    /** where an emulator looks for a game's save when there is none yet */
    private fun defaultTarget(system: String, rom: File, name: String): File? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = rom.nameWithoutExtension
        return when {
            ext == "srm" -> File(Storage.primary, "RetroArch/saves/$base.srm")
            system in setOf("nds", "gba") && ext in setOf("sav", "dsv") -> File(rom.parentFile, "$base.$ext")
            ext == "sav" -> File(Storage.primary, "RetroArch/saves/$base.sav")
            else -> null
        }
    }
}
