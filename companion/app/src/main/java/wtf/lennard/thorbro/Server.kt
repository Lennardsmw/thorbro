package wtf.lennard.thorbro

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * HTTP API of the companion app. Everything under /api except /api/ping needs the pairing code,
 * as `Authorization: Bearer <code>` or `?token=<code>` (for <img> and download links).
 * The web UI in assets/web is served from / without a code; it asks for the code itself.
 */
object Server {
    private const val PART = ".thorbro-part"

    fun create(ctx: Context, port: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
            }
            routing {
                // ---- web UI ----
                get("/") { asset(ctx, "index.html") }
                get("/{file}") {
                    val name = call.parameters["file"] ?: ""
                    if (!Regex("^[a-z0-9._-]+$").matches(name)) return@get fail("not found", HttpStatusCode.NotFound)
                    asset(ctx, name)
                }

                // ---- device ----
                get("/api/ping") {
                    json(JSONObject().put("name", "ThorBro").put("model", android.os.Build.MODEL))
                }
                get("/api/info") {
                    if (denied(ctx)) return@get
                    json(Device.info(ctx))
                }

                // ---- apps ----
                get("/api/apps") {
                    if (denied(ctx)) return@get
                    json(Apps.list(ctx))
                }
                get("/api/apps/{pkg}/icon") {
                    if (denied(ctx)) return@get
                    val png = try { Apps.icon(ctx, call.parameters["pkg"]!!) } catch (_: Exception) {
                        return@get fail("unknown app", HttpStatusCode.NotFound)
                    }
                    call.response.header(HttpHeaders.CacheControl, "max-age=86400")
                    call.respondBytes(png, ContentType.Image.PNG)
                }
                post("/api/apps/{pkg}/launch") {
                    if (denied(ctx)) return@post
                    val ok = Apps.launch(ctx, call.parameters["pkg"]!!)
                    if (ok) json(JSONObject().put("ok", true)) else fail("app has no launcher", HttpStatusCode.NotFound)
                }
                post("/api/apps/{pkg}/uninstall") {
                    if (denied(ctx)) return@post
                    Apps.uninstall(ctx, call.parameters["pkg"]!!)
                    json(JSONObject().put("ok", true).put("note", "confirm on the device"))
                }

                // ---- ROM library ----
                get("/api/roms") {
                    if (denied(ctx)) return@get
                    val res = withContext(Dispatchers.IO) { Roms.scan(ctx, q("refresh") == "1") }
                    json(res)
                }

                get("/api/roms/meta") {
                    if (denied(ctx)) return@get
                    val id = q("system") ?: return@get fail("system missing")
                    val res = withContext(Dispatchers.IO) {
                        val scan = Roms.scan(ctx, false).getJSONArray("systems")
                        val sys = (0 until scan.length()).map { scan.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
                        val roms = sys?.getJSONArray("roms")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
                        Media.metaFor(ctx, id, roms)
                    }
                    json(res)
                }
                // Cocoon's own media for a ROM: ?system=&path=<rom path>&kind=icon|hero|logo|screenshot_*
                get("/api/roms/media") {
                    if (denied(ctx)) return@get
                    val rom = Storage.resolve(q("path")) ?: return@get fail("path not allowed")
                    val f = withContext(Dispatchers.IO) { Cocoon.media(ctx, q("system") ?: "", rom.path, q("kind") ?: "icon") }
                        ?: return@get fail("no image", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "max-age=86400")
                    call.respondFile(f)
                }
                get("/api/roms/cover") {
                    if (denied(ctx)) return@get
                    val f = withContext(Dispatchers.IO) { Media.cover(ctx, q("system") ?: "", q("name") ?: "") }
                        ?: return@get fail("no cover", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "max-age=2592000")
                    call.respondFile(f)
                }
                get("/api/consoles/{id}/icon") {
                    if (denied(ctx)) return@get
                    val f = withContext(Dispatchers.IO) { Media.icon(ctx, call.parameters["id"] ?: "") }
                        ?: return@get fail("no icon", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "max-age=2592000")
                    call.respondFile(f)
                }

                // ---- play ----
                get("/api/emulators") {
                    if (denied(ctx)) return@get
                    json(Emulators.overview(ctx))
                }
                post("/api/emulators/choose") {
                    if (denied(ctx)) return@post
                    Emulators.choose(ctx, q("system") ?: return@post fail("system missing"), q("emulator") ?: return@post fail("emulator missing"))
                    json(Emulators.overview(ctx))
                }
                post("/api/emulators/grant") {
                    if (denied(ctx)) return@post
                    ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_PICK_FOLDER, true))
                    json(JSONObject().put("ok", true).put("note", "Ordnerauswahl auf dem Thor geöffnet"))
                }
                post("/api/roms/launch") {
                    if (denied(ctx)) return@post
                    val rom = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    val res = Emulators.launch(ctx, q("system") ?: return@post fail("system missing"), rom, q("emulator"))
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }

                // ---- save backups ----
                get("/api/saves") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { Saves.overview(ctx) })
                }
                post("/api/saves/backup") {
                    if (denied(ctx)) return@post
                    json(JSONObject().put("results", withContext(Dispatchers.IO) { Saves.backup(ctx, q("id"), q("force") == "1") }))
                }
                post("/api/saves/restore") {
                    if (denied(ctx)) return@post
                    val zip = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    val res = withContext(Dispatchers.IO) { Saves.restore(ctx, zip) }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/saves/config") {
                    if (denied(ctx)) return@post
                    q("auto")?.let { Saves.setAuto(ctx, it == "1") }
                    json(JSONObject().put("auto", Saves.auto(ctx)))
                }

                // ---- cleanup ----
                get("/api/cleanup") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { Cleanup.scan(ctx) })
                }
                post("/api/cleanup/junk") {
                    if (denied(ctx)) return@post
                    json(withContext(Dispatchers.IO) { Cleanup.removeJunk(ctx) })
                }

                // ---- files ----
                get("/api/files") {
                    if (denied(ctx)) return@get
                    val dir = Storage.resolve(q("path") ?: Storage.primary.path) ?: return@get fail("path not allowed")
                    if (!dir.isDirectory) return@get fail("not a folder", HttpStatusCode.NotFound)
                    json(JSONObject().put("path", dir.path).put("entries", Storage.list(dir)))
                }
                get("/api/files/raw") {
                    if (denied(ctx)) return@get
                    val f = Storage.resolve(q("path")) ?: return@get fail("path not allowed")
                    if (!f.isFile) return@get fail("not a file", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${f.name.replace("\"", "")}\"")
                    call.respondFile(f)
                }
                post("/api/files/mkdir") {
                    if (denied(ctx)) return@post
                    val f = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    if (f.mkdirs() || f.isDirectory) json(JSONObject().put("ok", true)) else fail("could not create folder")
                }
                post("/api/files/move") {
                    if (denied(ctx)) return@post
                    val from = Storage.resolve(q("from")) ?: return@post fail("from not allowed")
                    val to = Storage.resolve(q("to")) ?: return@post fail("to not allowed")
                    if (Storage.isRoot(from)) return@post fail("cannot move a storage root")
                    if (to.exists()) return@post fail("target exists", HttpStatusCode.Conflict)
                    to.parentFile?.mkdirs()
                    Roms.invalidate()
                    if (from.renameTo(to)) json(JSONObject().put("ok", true)) else fail("move failed")
                }
                delete("/api/files") {
                    if (denied(ctx)) return@delete
                    val f = Storage.resolve(q("path")) ?: return@delete fail("path not allowed")
                    if (Storage.isRoot(f)) return@delete fail("cannot delete a storage root")
                    val ok = withContext(Dispatchers.IO) { f.deleteRecursively() }
                    Roms.invalidate()
                    if (ok) json(JSONObject().put("ok", true)) else fail("delete failed")
                }

                // ---- resumable upload ----
                // 1. GET  /api/upload?path=&size=      -> {offset, exists, free}
                // 2. PUT  /api/upload?path=&offset=N   raw bytes, appended; 409 + {offset} if N is wrong
                // 3. POST /api/upload/finish?path=&size=&overwrite=1
                get("/api/upload") {
                    if (denied(ctx)) return@get
                    val f = Storage.resolve(q("path")) ?: return@get fail("path not allowed")
                    val part = File(f.path + PART)
                    json(JSONObject()
                        .put("offset", if (part.exists()) part.length() else 0L)
                        .put("exists", f.exists())
                        .put("existingSize", if (f.isFile) f.length() else 0L)
                        .put("free", Storage.free(f)))
                }
                put("/api/upload") {
                    if (denied(ctx)) return@put
                    val f = Storage.resolve(q("path")) ?: return@put fail("path not allowed")
                    val offset = q("offset")?.toLongOrNull() ?: 0L
                    val part = File(f.path + PART)
                    f.parentFile?.mkdirs()
                    val current = if (part.exists()) part.length() else 0L
                    if (offset != current) {
                        return@put json(JSONObject().put("offset", current), HttpStatusCode.Conflict)
                    }
                    Transfers.begin(ctx)
                    try {
                        withContext(Dispatchers.IO) {
                            FileOutputStream(part, true).use { out ->
                                call.receiveChannel().toInputStream().use { it.copyTo(out, 1 shl 20) }
                            }
                        }
                    } finally {
                        Transfers.end()
                    }
                    json(JSONObject().put("offset", part.length()))
                }
                post("/api/upload/finish") {
                    if (denied(ctx)) return@post
                    val f = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    val size = q("size")?.toLongOrNull() ?: return@post fail("size missing")
                    val part = File(f.path + PART)
                    if (!part.exists() && size == 0L) part.createNewFile()
                    if (part.length() != size) {
                        return@post json(JSONObject().put("error", "size mismatch").put("offset", part.length()), HttpStatusCode.Conflict)
                    }
                    if (f.exists()) {
                        if (q("overwrite") != "1") return@post fail("file exists", HttpStatusCode.Conflict)
                        f.delete()
                    }
                    if (!part.renameTo(f)) return@post fail("rename failed")
                    Roms.invalidate()
                    json(JSONObject().put("ok", true).put("path", f.path))
                }

                // ---- install ----
                post("/api/install") {
                    if (denied(ctx)) return@post
                    val f = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    if (!f.isFile || !f.name.endsWith(".apk", true)) return@post fail("not an .apk")
                    val id = withContext(Dispatchers.IO) { Installer.install(ctx, f) }
                    json(JSONObject().put("session", id).put("status", Installer.status[id]))
                }
                // ThorBro updates itself: raw APK in the body. Only APKs of this app are accepted.
                post("/api/self-update") {
                    if (denied(ctx)) return@post
                    val apk = File(ctx.cacheDir, "self-update.apk")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(apk).use { out -> call.receiveChannel().toInputStream().use { it.copyTo(out, 1 shl 20) } }
                    }
                    val pm = ctx.packageManager
                    val info = pm.getPackageArchiveInfo(apk.path, 0)
                        ?: return@post fail("not a valid APK")
                    if (info.packageName != ctx.packageName) {
                        apk.delete()
                        return@post fail("APK is ${info.packageName}, not ThorBro")
                    }
                    val current = pm.getPackageInfo(ctx.packageName, 0)
                    val id = withContext(Dispatchers.IO) { Installer.install(ctx, apk) }
                    json(JSONObject()
                        .put("session", id)
                        .put("status", Installer.status[id])
                        .put("from", current.versionName)
                        .put("to", info.versionName)
                        .put("note", "ThorBro restarts after the update; confirm on the Thor if asked"))
                }
                // ---- update from the update feed ----
                get("/api/update") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { Updater.check(ctx) })
                }
                post("/api/update") {
                    if (denied(ctx)) return@post
                    json(withContext(Dispatchers.IO) { Updater.update(ctx) })
                }
                post("/api/update/config") {
                    if (denied(ctx)) return@post
                    Updater.configure(ctx, q("feed"), q("auto")?.let { it == "1" })
                    json(JSONObject().put("feed", Updater.feed(ctx)).put("auto", Updater.auto(ctx)))
                }
                get("/api/install/{id}") {
                    if (denied(ctx)) return@get
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@get fail("bad id")
                    json(JSONObject().put("session", id).put("status", Installer.status[id] ?: "unknown"))
                }
            }
        }

    private fun RoutingContext.q(name: String): String? = call.request.queryParameters[name]

    private suspend fun RoutingContext.json(body: Any, status: HttpStatusCode = HttpStatusCode.OK) =
        call.respondText(body.toString(), ContentType.Application.Json, status)

    private suspend fun RoutingContext.fail(msg: String, status: HttpStatusCode = HttpStatusCode.BadRequest) =
        json(JSONObject().put("error", msg), status)

    /** Responds 401 and returns true when the pairing code is missing or wrong. */
    private suspend fun RoutingContext.denied(ctx: Context): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        if (Prefs.tokenMatches(ctx, header ?: q("token"))) return false
        fail("pairing code missing or wrong", HttpStatusCode.Unauthorized)
        return true
    }

    private suspend fun RoutingContext.asset(ctx: Context, name: String) {
        val bytes = try { ctx.assets.open("web/$name").use { it.readBytes() } } catch (_: Exception) {
            return fail("not found", HttpStatusCode.NotFound)
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            else -> ContentType.Application.OctetStream
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(bytes, type)
    }
}
