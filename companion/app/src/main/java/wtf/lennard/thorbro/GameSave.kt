package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One game's save in one place: the save on the Thor, the newest one in RomM (for the live profile) and
 * the game's own backups (<backups>/games/<key>/<time>-<reason>~<profile>.zip, packed like a profile stash).
 *
 * Every time something overwrites a game's save (RomM download, restoring a backup, profile swap, installing
 * a shared save) the current save is packed first; after a game saved, the watcher packs it too. A pack is
 * only written when the files changed since the last one.
 */
object GameSave {
    enum class Reason(val id: String, val label: String) {
        AUTO("auto", "nach dem Speichern"),
        CLOUD("vor-cloud", "vor dem Cloud-Download gesichert"),
        RESTORE("vor-zurueck", "vor dem Zurückholen gesichert"),
        MANUAL("manuell", "von Hand gesichert"),
        PROFILE("vor-profil", "vor dem Profilwechsel gesichert"),
        INSTALL("vor-installation", "vor dem Installieren gesichert"),
        GIFT("geschenk", "Geschenk"),
    }

    /** a save bigger than this is not packed per game (the location-wide snapshots still cover it) */
    private const val MAX_BYTES = 256L shl 20
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)

    fun items(ctx: Context, romPath: String) = RommSync.localSaves(ctx).filter { it.rom.optString("path") == romPath }

    private fun key(romPath: String) = MessageDigest.getInstance("SHA-1").digest(romPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun dir(romPath: String) = File(Saves.backupRoot(), "games/${key(romPath)}")

    private fun files(items: List<RommSync.SaveItem>) = items.flatMap { s ->
        if (s.isFolder) s.file.walkTopDown().filter { it.isFile }.toList() else listOf(s.file).filter { it.isFile }
    }

    /** packs the game's current save; null when there is none, nothing changed since the last pack, or it is too big */
    @Synchronized
    fun backup(ctx: Context, romPath: String, reason: Reason, given: List<RommSync.SaveItem>? = null): File? {
        val items = given ?: items(ctx, romPath)
        val fs = files(items)
        if (fs.isEmpty()) return null
        if (fs.sumOf { it.length() } > MAX_BYTES) { Logs.i("saves", "${File(romPath).name}: Spielstand zu groß für ein Backup pro Spiel"); return null }
        val fp = MessageDigest.getInstance("SHA-1").let { md ->
            fs.sortedBy { it.path }.forEach { md.update("${it.path}|${it.length()}|${it.lastModified()}\n".toByteArray()) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        val d = dir(romPath).apply { mkdirs() }
        val last = File(d, "last.fingerprint")
        if (last.isFile && last.readText() == fp) return null
        val owner = Profiles.owner(ctx, romPath)
        val zip = File(d, "${stamp.format(Date())}-${reason.id}~$owner.zip")
        Profiles.pack(ctx, zip, romPath, items)
        last.writeText(fp)
        zips(romPath).drop(DataSaver.keep(ctx)).forEach { it.delete(); File(it.path + ".from").delete() }
        return zip
    }

    /** a save a friend sent (already pointing at this Thor's paths) → kept as a backup "Geschenk von …" */
    fun addGift(ctx: Context, romPath: String, mapped: File, fromName: String): File {
        val d = dir(romPath).apply { mkdirs() }
        val zip = File(d, "${stamp.format(Date())}-${Reason.GIFT.id}~${Profiles.owner(ctx, romPath)}.zip")
        mapped.copyTo(zip, overwrite = true)
        File(zip.path + ".from").writeText(fromName.take(24))
        zips(romPath).drop(DataSaver.keep(ctx)).forEach { it.delete(); File(it.path + ".from").delete() }
        return zip
    }

    /** after the watcher saw saves change: pack every game whose save differs from its last pack */
    fun backupChanged(ctx: Context) {
        RommSync.localSaves(ctx).groupBy { it.rom.optString("path") }.forEach { (path, l) ->
            if (path.isNotEmpty()) runCatching { backup(ctx, path, Reason.AUTO, l) }.onFailure { Logs.e("saves", "Backup $path", it) }
        }
    }

    private fun zips(romPath: String) = (dir(romPath).listFiles { f -> f.name.endsWith(".zip") }?.toList() ?: emptyList()).sortedByDescending { it.name }

    fun list(ctx: Context, romPath: String): JSONArray = JSONArray(zips(romPath).map { z ->
        val parts = z.name.removeSuffix(".zip")
        val reasonId = parts.drop(16).substringBefore('~')
        val profile = parts.substringAfter('~', Profiles.MAIN)
        val from = File(z.path + ".from").takeIf { it.isFile }?.readText()?.trim()
        JSONObject().put("path", z.path).put("name", z.name).put("size", z.length())
            .put("time", runCatching { stamp.parse(parts.take(15))!!.time }.getOrDefault(z.lastModified()))
            .put("reason", reasonId).put("from", from ?: JSONObject.NULL)
            .put("reasonLabel", if (from != null) "Geschenk von $from" else Reason.entries.firstOrNull { it.id == reasonId }?.label ?: "gesichert")
            .put("profile", Profiles.get(ctx, profile)?.name ?: JSONObject.NULL)
    })

    /** puts a backup back; the current save is packed first */
    fun restore(ctx: Context, romPath: String, zipPath: String): JSONObject { synchronized(RommSync) {
        val zip = File(zipPath).canonicalFile
        if (zip.parentFile?.path != dir(romPath).canonicalPath || !zip.isFile) return JSONObject().put("error", "Backup gehört nicht zu diesem Spiel")
        val items = items(ctx, romPath)
        runCatching { backup(ctx, romPath, Reason.RESTORE, items) }
        items.forEach { if (it.isFolder) SaveFs.clearFiles(it.file) else SaveFs.delete(it.file) }
        val n = runCatching { Profiles.unpack(zip) }.getOrElse { e ->
            Logs.e("saves", "Backup ${zip.name} nicht zurückgeholt", e)
            return JSONObject().put("error", "Zurückholen ging nicht: ${e.message}. Der vorige Stand liegt als neuestes Backup bereit.")
        }
        File(dir(romPath), "last.fingerprint").delete()
        History.add(ctx, "restore", File(romPath).nameWithoutExtension, detail = zip.name.take(15))
        return JSONObject().put("ok", true).put("files", n).put("done", "Backup zurückgeholt")
    } }

    private fun lastChange(items: List<RommSync.SaveItem>) = files(items).maxOfOrNull { it.lastModified() } ?: 0L

    /** everything the save sheet shows: Thor, RomM, how they compare, backups */
    fun state(ctx: Context, system: String, romPath: String): JSONObject {
        val items = items(ctx, romPath)
        val primary = items.maxByOrNull { lastChange(listOf(it)) }
        val owner = Profiles.owner(ctx, romPath)
        val out = JSONObject().put("profile", Profiles.get(ctx, owner)?.name ?: "Spieler 1").put("multiProfile", Profiles.needsPicker(ctx))
            .put("mode", DataSaver.mode(ctx).id).put("rommAllowed", DataSaver.rommAllowed(ctx))
        val devT = lastChange(items)
        if (primary != null) out.put("device", JSONObject().put("time", devT).put("size", files(items).sumOf { it.length() })
            .put("name", items.joinToString(" + ") { it.file.name }).put("folder", primary.isFolder))
        out.put("backups", list(ctx, romPath))

        var cloud: JSONObject? = null
        val connected = Romm.status(ctx).optBoolean("connected")
        out.put("connected", connected)
        if (connected && DataSaver.rommAllowed(ctx)) runCatching {
            val romId = SharedSaves.romId(ctx, system, romPath)
            out.put("inRomm", romId != null)
            if (romId != null) {
                val rom = primary?.rom ?: JSONObject().put("path", romPath).put("file", File(romPath).name)
                cloud = RommSync.cloudNewest(ctx, romId, rom, primary)
            }
        }.onFailure { out.put("cloudError", it.message) }
        cloud?.let { c ->
            out.put("cloud", JSONObject().put("id", c.getInt("id")).put("time", RommSync.remoteTime(c)).put("size", c.optLong("file_size_bytes"))
                .put("name", SharedSaves.cleanName(c.optString("file_name"))).put("device", SharedSaves.deviceOf(c.optString("file_name")) ?: JSONObject.NULL))
        }
        out.put("compare", when {
            primary == null && cloud == null -> "none"
            cloud == null -> "device-only"
            primary == null -> "cloud-only"
            else -> {
                val prev = RommSync.state(ctx).optJSONObject(RommSync.stateKey(ctx, primary))
                val remoteSame = RommSync.sameRemote(prev?.optString("remote"), cloud!!)
                val localSame = prev != null && RommSync.md5(primary.file) == prev.optString("local")
                val cloudT = RommSync.remoteTime(cloud!!)
                when {
                    remoteSame && localSame -> "same"
                    remoteSame -> "device-newer"
                    localSame -> "cloud-newer"
                    cloudT > devT + 30_000 -> "cloud-newer"
                    devT > cloudT + 30_000 -> "device-newer"
                    else -> "same"
                }
            }
        })
        return out
    }

    /** writes the newest RomM save over the one on the Thor (backup first) */
    fun pullCloud(ctx: Context, system: String, romPath: String): JSONObject {
        if (!DataSaver.rommAllowed(ctx)) return JSONObject().put("error", "„Nur lokal“ ist an – RomM unter Einstellungen → Daten sparen erlauben")
        return runCatching {
            val romId = SharedSaves.romId(ctx, system, romPath) ?: return JSONObject().put("error", "Das Spiel ist nicht in RomM")
            synchronized(RommSync) {
                val items = items(ctx, romPath)
                val primary = items.maxByOrNull { lastChange(listOf(it)) }
                val rom = primary?.rom ?: JSONObject().put("path", romPath).put("file", File(romPath).name)
                val c = RommSync.cloudNewest(ctx, romId, rom, primary) ?: return JSONObject().put("error", "In RomM liegt kein Spielstand für dieses Spiel")
                when {
                    primary == null && Profiles.supports(system) ->
                        SharedSaves.install(ctx, system, romPath, listOf(c.getInt("id")), Profiles.owner(ctx, romPath)).also { if (it.has("error")) return it }
                    primary == null -> return JSONObject().put("error", "Erst einmal in Eden spielen und speichern, dann geht das Holen")
                    primary.isFolder -> RommSync.pullFolder(ctx, primary, c)
                    else -> RommSync.pullFile(ctx, primary, c, force = true)
                }
                JSONObject().put("ok", true).put("done", "Cloud-Stand ist jetzt auf dem Thor")
            }
        }.getOrElse { JSONObject().put("error", it.message ?: "Fehler") }
    }

    /** uploads the Thor's save as the newest one in RomM */
    fun pushCloud(ctx: Context, romPath: String): JSONObject {
        if (!DataSaver.rommAllowed(ctx)) return JSONObject().put("error", "„Nur lokal“ ist an – RomM unter Einstellungen → Daten sparen erlauben")
        if (items(ctx, romPath).isEmpty()) return JSONObject().put("error", "Auf dem Thor gibt es noch keinen Spielstand")
        val r = runCatching { RommSync.syncSaves(ctx, null, onlyRom = romPath, force = true) }.getOrElse { return JSONObject().put("error", it.message) }
        val errs = r.optJSONArray("errors")
        return when {
            errs != null && errs.length() > 0 -> JSONObject().put("error", errs.optString(0))
            r.optInt("uploaded") == 0 -> JSONObject().put("error", "Das Spiel ist nicht in RomM")
            else -> JSONObject().put("ok", true).put("done", "Hochgeladen – RomM hat jetzt den Stand vom Thor")
        }
    }

    fun backupNow(ctx: Context, romPath: String): JSONObject =
        if (backup(ctx, romPath, Reason.MANUAL) != null) JSONObject().put("ok", true).put("done", "Gesichert")
        else if (items(ctx, romPath).isEmpty()) JSONObject().put("error", "Auf dem Thor gibt es noch keinen Spielstand")
        else JSONObject().put("ok", true).put("done", "Unverändert – das neueste Backup ist schon dieser Stand")
}
