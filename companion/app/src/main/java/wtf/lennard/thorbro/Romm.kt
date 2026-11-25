package wtf.lennard.thorbro

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Client for a RomM server (github.com/rommapp/romm): browse its library and copy games onto the device.
 * Sign-in uses RomM's device flow: ThorBro shows a code, the user approves it in RomM's web UI.
 */
object Romm {
    private val scopes = listOf("me.read", "platforms.read", "platforms.write", "roms.read", "roms.write", "assets.read", "assets.write", "firmware.read", "firmware.write")

    fun hasScope(ctx: Context, scope: String) = (Prefs.of(ctx).getString("rommScopes", "") ?: "").split(' ').contains(scope)

    fun url(ctx: Context) = (Prefs.of(ctx).getString("rommUrl", "") ?: "").trimEnd('/')
    private fun token(ctx: Context) = Prefs.of(ctx).getString("rommToken", "") ?: ""
    private fun deviceId(ctx: Context) = Prefs.of(ctx).getString("rommDevice", null)
        ?: UUID.randomUUID().toString().also { Prefs.of(ctx).edit().putString("rommDevice", it).apply() }

    @Volatile private var pending: JSONObject? = null

    // ---------- home network shortcut ----------
    /** RomM's address inside the home network; used whenever it answers and is the same server as [url], so
     *  uploads and downloads do not go out to the internet and back. Unset = the home RomM on the vm;
     *  an empty string turns the shortcut off. */
    private const val DEFAULT_LAN = "http://192.168.178.159:6664"
    fun lanUrl(ctx: Context) = (Prefs.of(ctx).getString("rommLanUrl", null) ?: DEFAULT_LAN).trimEnd('/')
    fun setLanUrl(ctx: Context, u: String) { Prefs.of(ctx).edit().putString("rommLanUrl", u.trim().trimEnd('/')).apply(); lanCheck = 0L to false }
    @Volatile private var lanCheck = 0L to false
    /** the public server's heartbeat fingerprint, so a stranger's box at the same LAN address never gets the token */
    @Volatile private var publicPrint: String? = null

    private fun heartbeatPrint(base: String, timeout: Int, read: Int = timeout): String? = runCatching {
        (URL("$base/api/heartbeat").openConnection() as HttpURLConnection).run {
            connectTimeout = timeout; readTimeout = read
            if (responseCode != 200) return@run null
            val j = JSONObject(inputStream.bufferedReader().use { it.readText() })
            j.getJSONObject("SYSTEM").optString("VERSION") + "|" + j.optJSONObject("METADATA_SOURCES")?.toString()
        }
    }.getOrNull()

    private fun fastBase(ctx: Context): String? {
        val lan = lanUrl(ctx).ifEmpty { return null }
        val pub = url(ctx)
        if (lan == pub) return null
        val (at, ok) = lanCheck
        if (System.currentTimeMillis() - at < 60_000) return if (ok) lan else null
        val reach = heartbeatPrint(lan, 1500, 6000)?.let { local ->
            val remote = publicPrint ?: heartbeatPrint(pub, 5000)?.also { publicPrint = it }
            remote != null && remote == local
        } ?: false
        lanCheck = System.currentTimeMillis() to reach
        return if (reach) lan else null
    }

    /** forget the LAN check, e.g. after a failed request when the device just left home */
    fun recheckLan() { lanCheck = 0L to false }

    fun status(ctx: Context): JSONObject = JSONObject().put("url", url(ctx)).put("connected", token(ctx).isNotEmpty())
        .put("lanUrl", lanUrl(ctx)).put("viaLan", lanCheck.second && lanUrl(ctx).isNotEmpty())
        .put("pending", pending).put("canWrite", hasScope(ctx, "roms.write"))

    fun disconnect(ctx: Context) { Prefs.of(ctx).edit().remove("rommToken").apply(); pending = null }

    /** starts the device sign-in; returns the code to approve in RomM */
    fun connect(ctx: Context, baseUrl: String): JSONObject {
        val base = baseUrl.trim().trimEnd('/')
        Prefs.of(ctx).edit().putString("rommUrl", base).remove("rommToken").apply()
        val body = JSONObject()
            .put("client_device_identifier", deviceId(ctx))
            .put("name", "ThorBro (${Build.MODEL})")
            .put("client", "ThorBro")
            .put("platform", "android")
            .put("client_version", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
            .put("requested_scopes", JSONArray(scopes))
        val init = JSONObject(request(ctx, "POST", "/api/auth/device/init", body.toString(), auth = false))
        val p = JSONObject()
            .put("userCode", init.getString("user_code"))
            .put("verifyUrl", base + init.getString("verification_path_complete"))
            .put("expiresIn", init.getInt("expires_in"))
        pending = p
        val deviceCode = init.getString("device_code")
        val interval = init.optInt("interval", 5).coerceAtLeast(2) * 1000L
        val until = System.currentTimeMillis() + init.getInt("expires_in") * 1000L
        thread(name = "romm-auth") {
            while (System.currentTimeMillis() < until && pending === p) {
                Thread.sleep(interval)
                val res = runCatching { request(ctx, "POST", "/api/auth/device/token", JSONObject().put("device_code", deviceCode).toString(), auth = false) }
                val json = res.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                val tok = json?.optString("access_token")
                if (!tok.isNullOrEmpty()) {
                    val granted = json.optJSONArray("scopes")?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } } ?: ""
                    Prefs.of(ctx).edit().putString("rommToken", tok).putString("rommScopes", granted)
                        .putString("rommDeviceId", json.optString("device_id")).apply()
                    pending = null
                    return@thread
                }
            }
            if (pending === p) pending = null
        }
        return p
    }

    // ---------- library ----------

    /** RomM platform slug -> ThorBro console id (folder name on the device) */
    private val slugMap = mapOf("3ds" to "n3ds", "ngc" to "gc", "dc" to "dreamcast", "genesis" to "megadrive", "genesis-slash-megadrive" to "megadrive",
        "sms" to "mastersystem", "tg16" to "pcengine", "turbografx16--1" to "pcengine", "ngpc" to "ngp", "neo-geo-pocket-color" to "ngp",
        "wonderswan-color" to "wonderswan", "ps" to "psx", "playstation" to "psx")

    fun systemFor(slug: String): String = slugMap[slug] ?: Roms.systems.firstOrNull { it.id == slug }?.id ?: slug

    fun platforms(ctx: Context): JSONArray {
        val arr = JSONArray(request(ctx, "GET", "/api/platforms"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optInt("rom_count") == 0) continue
            out.put(JSONObject().put("id", p.getInt("id")).put("name", p.optString("display_name").ifEmpty { p.optString("name") })
                .put("slug", p.optString("fs_slug")).put("system", systemFor(p.optString("fs_slug"))).put("count", p.optInt("rom_count"))
                .put("size", p.optLong("fs_size_bytes")))
        }
        return out
    }

    fun roms(ctx: Context, platformId: Int, search: String?): JSONObject {
        val q = "platform_ids=$platformId&limit=500&order_by=name" + (search?.takeIf { it.isNotBlank() }?.let { "&search_term=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val page = JSONObject(request(ctx, "GET", "/api/roms?$q"))
        val items = page.optJSONArray("items") ?: JSONArray()
        // which of them are already on the device (by file name in the console folder)
        val local = Roms.scan(ctx, false).getJSONArray("systems").let { s ->
            (0 until s.length()).flatMap { i -> s.getJSONObject(i).getJSONArray("roms").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("file") } } }.toSet()
        }
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            out.put(JSONObject().put("id", r.getInt("id")).put("name", r.optString("name").ifEmpty { r.optString("fs_name_no_tags") })
                .put("file", r.optString("fs_name")).put("size", r.optLong("fs_size_bytes")).put("multi", r.optBoolean("has_multiple_files"))
                .put("summary", r.optString("summary")).put("hasCover", r.optString("path_cover_small").isNotEmpty())
                .put("system", systemFor(r.optString("platform_fs_slug")))
                .put("onDevice", r.optString("fs_name") in local))
        }
        return JSONObject().put("total", page.optInt("total", out.length())).put("items", out)
    }

    /** RomM's own console icon (SVG) for a ThorBro console id; null when RomM has none or is not set up */
    fun platformIcon(ctx: Context, systemId: String, target: File): Boolean = runCatching {
        if (url(ctx).isEmpty()) return false
        val c = open(ctx, "GET", "/assets/platforms/${RommSync.slugFor(systemId)}.svg", auth = false)
        if (c.responseCode != 200 || c.contentType?.contains("svg") != true) return false
        val bytes = c.inputStream.use { it.readBytes() }
        if (bytes.size < 100) return false
        target.parentFile?.mkdirs(); target.writeBytes(bytes); true
    }.getOrDefault(false)

    fun cover(ctx: Context, romId: Int): ByteArray? {
        val r = JSONObject(request(ctx, "GET", "/api/roms/$romId/simple"))
        val path = r.optString("path_cover_small").ifEmpty { r.optString("path_cover_large") }.ifEmpty { return null }
        // newer RomM returns the full "/assets/romm/resources/..." path with a "?ts=<date time>" containing a space
        val rel = when { path.startsWith("http") -> path; path.startsWith("/assets/") -> path; else -> "/assets/romm/resources/$path".replace("//", "/") }
        val c = open(ctx, "GET", rel.replace(" ", "%20"))
        return if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    }

    /** copies a game from RomM into <ROMs>/<console>/ ; multi-file games arrive as zip and get unpacked */
    fun download(ctx: Context, romId: Int, romsRoot: File, job: Jobs.Job): JSONObject {
        val r = JSONObject(request(ctx, "GET", "/api/roms/$romId/simple"))
        val system = systemFor(r.optString("platform_fs_slug"))
        val name = r.getString("fs_name")
        val multi = r.optBoolean("has_multiple_files")
        val dir = File(romsRoot, system).apply { mkdirs() }
        val target = File(dir, if (multi) "$name.zip" else name)
        val part = File(target.path + ".thorbro-part")
        val total = r.optLong("fs_size_bytes")
        val path = "/api/roms/$romId/content/" + URLEncoder.encode(target.name, "UTF-8").replace("+", "%20")
        val c = open(ctx, "GET", path, rangeFrom = if (part.exists()) part.length() else 0)
        if (c.responseCode !in listOf(200, 206)) error("RomM antwortet mit HTTP ${c.responseCode}")
        val append = c.responseCode == 206
        job.message = name
        job.game(r.optString("name").ifEmpty { name }, system, romId)
        Transfers.begin(ctx)
        try {
            c.inputStream.use { inp ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = if (append) part.length() else 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { job.progress = done.toDouble() / total * (if (multi) 0.9 else 1.0); job.bytes(done, total); job.gameBytes(done, total) }
                    }
                }
            }
        } finally { Transfers.end() }
        if (target.exists()) target.delete()
        part.renameTo(target)
        if (multi) {
            job.message = "entpacke $name"
            Archives.extract(target, File(dir, name), deleteAfter = true, job = job)
        }
        Roms.invalidate()
        return JSONObject().put("path", if (multi) File(dir, name).path else target.path).put("system", system)
            .put("name", r.optString("name").ifEmpty { name })
    }

    /** a game's RomM record (name on disk, size, single/multi file) */
    fun romInfo(ctx: Context, romId: Int): JSONObject = JSONObject(request(ctx, "GET", "/api/roms/$romId/simple"))

    /** a game's file from byte [from] (to [to], inclusive), for gifts that load over RomM */
    fun content(ctx: Context, romId: Int, fsName: String, from: Long, to: Long?): HttpURLConnection {
        val path = "/api/roms/$romId/content/" + URLEncoder.encode(fsName, "UTF-8").replace("+", "%20")
        val c = open(ctx, "GET", path, headers = if (from > 0 || to != null) mapOf("Range" to "bytes=$from-${to ?: ""}") else emptyMap())
        if (c.responseCode !in listOf(200, 206)) error("RomM antwortet mit HTTP ${c.responseCode}")
        return c
    }

    // ---------- raw API for syncing ----------

    fun rawPlatforms(ctx: Context): List<JSONObject> = JSONArray(request(ctx, "GET", "/api/platforms")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    fun rawRoms(ctx: Context, platformId: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val page = JSONObject(request(ctx, "GET", "/api/roms?platform_ids=$platformId&limit=500&offset=$offset"))
            val items = page.optJSONArray("items") ?: break
            for (i in 0 until items.length()) out += items.getJSONObject(i)
            offset += items.length()
            if (items.length() == 0 || offset >= page.optInt("total", offset)) break
        }
        return out
    }

    fun createPlatform(ctx: Context, slug: String): Int =
        JSONObject(request(ctx, "POST", "/api/platforms", JSONObject().put("fs_slug", slug).toString())).getInt("id")

    fun uploadStart(ctx: Context, platformId: Int, name: String, size: Long, chunks: Int): String {
        val c = open(ctx, "POST", "/api/roms/upload/start", headers = mapOf(
            "x-upload-platform" to "$platformId", "x-upload-filename" to name,
            "x-upload-total-size" to "$size", "x-upload-total-chunks" to "$chunks"))
        val j = JSONObject(read(c))
        return j.optString("upload_id").ifEmpty { j.optString("id") }.ifEmpty { error("RomM: keine upload_id") }
    }

    /** sends one chunk in small slices so [sent] can report progress while it is on the wire; retries a dropped
     *  connection (e.g. leaving home mid-upload: the retry goes out over the public address) */
    fun uploadChunk(ctx: Context, uploadId: String, index: Int, data: ByteArray, len: Int, sent: (Int) -> Unit = {}) {
        var attempt = 0
        while (true) {
            var reported = 0
            try {
                val c = open(ctx, "PUT", "/api/roms/upload/$uploadId", headers = mapOf("x-chunk-index" to "$index",
                    "Content-Type" to "application/octet-stream"))
                c.doOutput = true
                c.setFixedLengthStreamingMode(len)
                c.outputStream.use { o ->
                    var off = 0
                    while (off < len) {
                        val n = minOf(256 * 1024, len - off)
                        o.write(data, off, n); off += n; reported += n; sent(n)
                    }
                }
                read(c)
                return
            } catch (e: java.io.IOException) {
                if (reported > 0) sent(-reported)
                if (++attempt >= 4) throw e
                recheckLan()
                Thread.sleep(1000L * attempt)
            }
        }
    }

    /** RomM glues the chunks into the final file before it answers: on a busy server that takes minutes for a big game */
    fun uploadComplete(ctx: Context, uploadId: String) { read(open(ctx, "POST", "/api/roms/upload/$uploadId/complete", readTimeout = 30 * 60_000)) }

    /** returns RomM's save object (id, updated_at …) */
    fun uploadSave(ctx: Context, romId: Int, f: File, emulator: String, contentHash: String, slot: String? = null): JSONObject {
        val boundary = "thorbro" + System.currentTimeMillis()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"saveFile\"; filename=\"${f.name.replace("\"", "")}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        // no device_id: RomM would then also require the devices.write scope
        val q = "rom_id=$romId&emulator=${URLEncoder.encode(emulator, "UTF-8")}&overwrite=true&content_hash=$contentHash" +
            (slot?.let { "&slot=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val c = open(ctx, "POST", "/api/saves?$q", headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"))
        c.doOutput = true
        c.setFixedLengthStreamingMode(head.toByteArray().size + f.length() + tail.toByteArray().size)
        c.outputStream.use { o -> o.write(head.toByteArray()); f.inputStream().use { it.copyTo(o) }; o.write(tail.toByteArray()) }
        return JSONObject(read(c))
    }

    fun listSaves(ctx: Context, romId: Int): List<JSONObject> =
        JSONArray(request(ctx, "GET", "/api/saves?rom_id=$romId")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** RomM answered 403: the pairing lacks a scope (older pairings have no firmware.write) */
    class MissingScope(msg: String) : IllegalStateException(msg)

    fun listFirmware(ctx: Context, platformId: Int): List<JSONObject> =
        JSONArray(request(ctx, "GET", "/api/firmware?platform_id=$platformId")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** uploads [files] as firmware of one platform (multipart field "files"); same names are overwritten */
    fun uploadFirmware(ctx: Context, platformId: Int, files: List<Pair<String, File>>): JSONObject {
        val boundary = "thorbro" + System.currentTimeMillis()
        val parts = files.map { (name, f) ->
            "--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"${name.replace("\"", "")}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray() to f
        }
        val crlf = "\r\n".toByteArray(); val tail = "--$boundary--\r\n".toByteArray()
        val c = open(ctx, "POST", "/api/firmware?platform_id=$platformId", headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"))
        c.doOutput = true
        c.setFixedLengthStreamingMode(parts.sumOf { it.first.size + it.second.length() + crlf.size } + tail.size)
        c.outputStream.use { o -> parts.forEach { (h, f) -> o.write(h); f.inputStream().use { it.copyTo(o) }; o.write(crlf) }; o.write(tail) }
        if (c.responseCode == 403) throw MissingScope("RomM neu koppeln für Firmware (Recht firmware.write fehlt)")
        return JSONObject(read(c))
    }

    /** removes firmware entries and their files */
    fun deleteFirmware(ctx: Context, ids: List<Int>) {
        val c = open(ctx, "POST", "/api/firmware/delete", JSONObject().put("firmware", JSONArray(ids)).put("delete_from_fs", JSONArray(ids)).toString())
        if (c.responseCode == 403) throw MissingScope("RomM neu koppeln für Firmware (Recht firmware.write fehlt)")
        read(c)
    }

    /** all of the user's saves on one platform (each carries its "rom_id") */
    fun listPlatformSaves(ctx: Context, platformId: Int): List<JSONObject> =
        JSONArray(request(ctx, "GET", "/api/saves?platform_id=$platformId")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** a save's slot; null for RomM's default slot */
    fun slotOf(s: JSONObject): String? = if (s.isNull("slot")) null else s.optString("slot").ifEmpty { null }

    /** a game's detail; "all_user_saves" holds own saves plus other users' public ones */
    fun romDetail(ctx: Context, romId: Int): JSONObject = JSONObject(request(ctx, "GET", "/api/roms/$romId"))

    /** public saves can be seen and downloaded by every RomM user */
    fun setSavePublic(ctx: Context, saveId: Int, on: Boolean): JSONObject =
        JSONObject(request(ctx, "PUT", "/api/saves/$saveId/visibility", JSONObject().put("is_public", on).toString()))

    fun downloadSave(ctx: Context, saveId: Int, target: File) {
        val c = open(ctx, "GET", "/api/saves/$saveId/content")
        if (c.responseCode != 200) error("RomM HTTP ${c.responseCode} beim Herunterladen")
        c.inputStream.use { i -> target.outputStream().use { i.copyTo(it) } }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 401 || code == 403) error("RomM: keine Berechtigung (neu koppeln?) ${text.take(120)}")
        if (code >= 400) error("RomM HTTP $code: ${text.take(200)}")
        return text
    }

    // ---------- HTTP ----------

    private fun open(ctx: Context, method: String, path: String, body: String? = null, auth: Boolean = true, rangeFrom: Long = 0,
                     headers: Map<String, String> = emptyMap(), raw: ByteArray? = null, rawLen: Int = 0, readTimeout: Int = 120_000): HttpURLConnection {
        val base = (if (auth) fastBase(ctx) else null) ?: url(ctx).ifEmpty { error("Keine RomM-Adresse eingetragen") }
        val c = URL(if (path.startsWith("http")) path else base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", "ThorBro")
        c.setRequestProperty("Accept", "application/json")
        if (auth) token(ctx).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (raw != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(rawLen)
            c.outputStream.use { it.write(raw, 0, rawLen) }
        }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    private fun request(ctx: Context, method: String, path: String, body: String? = null, auth: Boolean = true): String {
        val c = open(ctx, method, path, body, auth)
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 401) error("RomM: nicht angemeldet")
        if (code >= 400) error("RomM HTTP $code: ${text.take(200)}")
        return text
    }
}
