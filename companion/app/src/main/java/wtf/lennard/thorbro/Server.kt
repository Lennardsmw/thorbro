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
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
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
    /** last radio scan for invites (holds the nets' passwords; the panel only gets device addresses) */
    @Volatile private var lastInvites: org.json.JSONArray? = null
    private const val PART = ".thorbro-part"
    private const val CHUNKS = ".thorbro-part.chunks"

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
                // ThorBro for another device (e.g. over Wi-Fi Direct): public, it is only the app itself
                get("/get") { call.respondText(Direct.page(ctx), ContentType.Text.Html) }
                get("/thorbro.apk") {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"ThorBro.apk\"")
                    call.respondFile(Direct.apk(ctx))
                }
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
                post("/api/apps/{pkg}/restart") {
                    if (denied(ctx)) return@post
                    if (Apps.restart(ctx, call.parameters["pkg"]!!)) json(JSONObject().put("ok", true)) else fail("app not installed", HttpStatusCode.NotFound)
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
                    val sys = q("system") ?: return@post fail("system missing")
                    val res = withContext(Dispatchers.IO) { Profiles.launch(ctx, sys, rom, q("emulator"), q("profile")) }
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

                // ---- profiles + shared saves ----
                get("/api/profiles") {
                    if (denied(ctx)) return@get
                    val rom = q("path")?.let { Storage.resolve(it) }
                    // with a game: whose save is live and who has one (for the start picker)
                    json(Profiles.json(ctx).apply { if (rom != null) put("game", withContext(Dispatchers.IO) { Profiles.forGame(ctx, rom.path) }) })
                }
                post("/api/profiles") {
                    if (denied(ctx)) return@post
                    val res = Profiles.add(ctx, q("name") ?: "")
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/profiles/rename") {
                    if (denied(ctx)) return@post
                    val res = Profiles.rename(ctx, q("id") ?: "", q("name") ?: "")
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/profiles/delete") {
                    if (denied(ctx)) return@post
                    val res = withContext(Dispatchers.IO) { Profiles.remove(ctx, q("id") ?: "") }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                get("/api/saves/game") {
                    if (denied(ctx)) return@get
                    val rom = (Storage.resolve(q("path")) ?: Ports.resolve(ctx, q("path"))) ?: return@get fail("path not allowed")
                    json(withContext(Dispatchers.IO) { SharedSaves.forGame(ctx, q("system") ?: "", rom.path) })
                }
                post("/api/saves/profile") {
                    if (denied(ctx)) return@post
                    val rom = (Storage.resolve(q("path")) ?: Ports.resolve(ctx, q("path"))) ?: return@post fail("path not allowed")
                    if (!Profiles.supports(q("system") ?: "")) return@post fail("Switch-Spielstände verwaltet Eden selbst", HttpStatusCode.Conflict)
                    val res = withContext(Dispatchers.IO) { Profiles.activate(ctx, rom.path, q("id") ?: "") }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/saves/install") {
                    if (denied(ctx)) return@post
                    val rom = (Storage.resolve(q("path")) ?: Ports.resolve(ctx, q("path"))) ?: return@post fail("path not allowed")
                    val ids = (q("save") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (ids.isEmpty()) return@post fail("save missing")
                    val res = withContext(Dispatchers.IO) { SharedSaves.install(ctx, q("system") ?: "", rom.path, ids, q("profile") ?: Profiles.MAIN) }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/saves/share") {
                    if (denied(ctx)) return@post
                    val ids = (q("save") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (ids.isEmpty()) return@post fail("save missing")
                    val res = withContext(Dispatchers.IO) { SharedSaves.share(ctx, ids, q("on") == "1") }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
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

                // ---- screen mirror ----
                get("/api/screen") {
                    if (denied(ctx)) return@get
                    json(JSONObject().put("running", Screen.running).put("width", Screen.width).put("height", Screen.height).put("frameAt", Screen.frameAt))
                }
                post("/api/screen/start") {
                    if (denied(ctx)) return@post
                    if (Screen.running || Screen.starting) return@post json(JSONObject().put("ok", true).put("note", if (Screen.running) "läuft schon" else "wartet auf Bestätigung am Thor"))
                    ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_CAPTURE, true))
                    json(JSONObject().put("ok", true).put("note", "Auf dem Thor „Jetzt starten“ tippen"))
                }
                post("/api/screen/stop") {
                    if (denied(ctx)) return@post
                    Screen.stop()
                    json(JSONObject().put("ok", true))
                }
                get("/api/screen/frame.jpg") {
                    if (denied(ctx)) return@get
                    Screen.viewed()
                    val f = Screen.frame ?: return@get fail("kein Bild", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(f, ContentType.Image.JPEG)
                }
                get("/api/screen/stream") {
                    if (denied(ctx)) return@get
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=thorbroframe")) {
                        var sent = 0L
                        while (Screen.running && !isClosedForWrite) {
                            Screen.viewed()
                            val f = Screen.frame
                            if (f != null && Screen.frameAt != sent) {
                                sent = Screen.frameAt
                                writeStringUtf8("--thorbroframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n")
                                writeFully(f)
                                writeStringUtf8("\r\n")
                                flush()
                            }
                            delay(40)
                        }
                    }
                }

                // ---- RetroAchievements ----
                get("/api/ra") {
                    if (denied(ctx)) return@get
                    json(RetroAchievements.status(ctx))
                }
                post("/api/ra/config") {
                    if (denied(ctx)) return@post
                    val b = runCatching { JSONObject(call.receiveText()) }.getOrDefault(JSONObject())
                    RetroAchievements.configure(ctx, b.optString("user", null), b.optString("key", null))
                    json(RetroAchievements.status(ctx))
                }
                get("/api/ra/system") {
                    if (denied(ctx)) return@get
                    val id = q("system") ?: return@get fail("system missing")
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            val scan = Roms.scan(ctx, false).getJSONArray("systems")
                            val sys = (0 until scan.length()).map { scan.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
                            val roms = sys?.getJSONArray("roms")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
                            RetroAchievements.forSystem(ctx, id, roms)
                        }.getOrElse { JSONObject().put("_error", it.message) }
                    }
                    json(res)
                }

                // ---- RomM library ----
                get("/api/romm") {
                    if (denied(ctx)) return@get
                    json(Romm.status(ctx))
                }
                post("/api/romm/connect") {
                    if (denied(ctx)) return@post
                    val u = q("url") ?: return@post fail("url missing")
                    json(withContext(Dispatchers.IO) { runCatching { Romm.connect(ctx, u) }.getOrElse { JSONObject().put("error", it.message) } })
                }
                // change the server address but keep the sign-in (e.g. NetBird instead of the public domain)
                post("/api/romm/url") {
                    if (denied(ctx)) return@post
                    val u = q("url")?.trim()?.trimEnd('/') ?: return@post fail("url missing")
                    Prefs.of(ctx).edit().putString("rommUrl", u).apply()
                    json(Romm.status(ctx))
                }
                post("/api/romm/lan") {
                    if (denied(ctx)) return@post
                    Romm.setLanUrl(ctx, q("url") ?: "")
                    json(Romm.status(ctx))
                }
                post("/api/romm/disconnect") {
                    if (denied(ctx)) return@post
                    Romm.disconnect(ctx); json(Romm.status(ctx))
                }
                get("/api/romm/platforms") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { runCatching { JSONObject().put("items", Romm.platforms(ctx)) }.getOrElse { JSONObject().put("error", it.message) } })
                }
                get("/api/romm/roms") {
                    if (denied(ctx)) return@get
                    val pid = q("platform")?.toIntOrNull() ?: return@get fail("platform missing")
                    json(withContext(Dispatchers.IO) { runCatching { Romm.roms(ctx, pid, q("search")) }.getOrElse { JSONObject().put("error", it.message) } })
                }
                get("/api/romm/cover/{id}") {
                    if (denied(ctx)) return@get
                    val b = withContext(Dispatchers.IO) { runCatching { Romm.cover(ctx, call.parameters["id"]!!.toInt()) }.getOrNull() }
                        ?: return@get fail("no cover", HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "max-age=86400")
                    call.respondBytes(b, ContentType.Image.Any)
                }
                post("/api/romm/download") {
                    if (denied(ctx)) return@post
                    val id = q("id")?.toIntOrNull() ?: return@post fail("id missing")
                    val root = q("root")?.let { Storage.resolve(it) }
                        ?: Roms.scan(ctx, false).optJSONArray("romRoots")?.let { a -> (0 until a.length()).map { File(a.getString(it)) }.lastOrNull() }
                        ?: File(Storage.primary, "ROMs")
                    val j = Jobs.start("RomM-Download") { job -> Romm.download(ctx, id, root, job) }
                    json(j.json())
                }

                get("/api/romm/sync") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { runCatching { RommSync.plan(ctx) }.getOrElse { JSONObject().put("error", it.message) } })
                }
                post("/api/romm/sync") {
                    if (denied(ctx)) return@post
                    val systems = (q("systems") ?: "").split(',').filter { it.isNotBlank() }.toSet()
                    val withSaves = q("saves") != "0"
                    val j = RommSync.start(ctx, systems, withSaves)
                    json(j.json())
                }
                post("/api/romm/sync/config") {
                    if (denied(ctx)) return@post
                    q("auto")?.let { RommSync.setAuto(ctx, it == "1") }
                    json(JSONObject().put("auto", RommSync.auto(ctx)))
                }

                get("/api/players") {
                    if (denied(ctx)) return@get
                    json(Players.overview(ctx))
                }

                get("/api/bt/pads") {
                    if (denied(ctx)) return@get
                    json(org.json.JSONArray(BtPads.list(ctx).map { JSONObject().put("address", it.address).put("name", it.name) }))
                }
                post("/api/bt/unpair") {
                    if (denied(ctx)) return@post
                    val a = q("address") ?: return@post fail("address missing")
                    json(JSONObject().put("ok", withContext(Dispatchers.IO) { BtPads.unpair(ctx, a) }))
                }

                // ---- controller LEDs ----
                get("/api/leds") {
                    if (denied(ctx)) return@get
                    json(JSONObject().put("thor", Leds.thorState(ctx)).put("pads", Leds.pads()))
                }
                post("/api/leds/thor") {
                    if (denied(ctx)) return@post
                    json(Leds.setThor(ctx, q("on")?.let { it == "1" }, q("color")?.toIntOrNull(16)))
                }
                post("/api/leds/pad") {
                    if (denied(ctx)) return@post
                    val ok = Leds.setPad(q("id")?.toIntOrNull() ?: return@post fail("id missing"), q("color")?.toIntOrNull(16) ?: 0)
                    json(JSONObject().put("ok", ok))
                }

                get("/api/debug/logs") {
                    if (denied(ctx)) return@get
                    val n = q("lines")?.toIntOrNull() ?: 300
                    json(JSONObject().put("version", BuildConfigVersion.name(ctx)).put("app", Logs.tail(n))
                        .put("logcat", withContext(Dispatchers.IO) { Logs.logcat(n) }).put("crashes", CrashLog.read(ctx)))
                }
                get("/api/debug/crash") {
                    if (denied(ctx)) return@get
                    call.respondText(CrashLog.read(ctx), ContentType.Text.Plain)
                }
                get("/api/debug/frames") {
                    if (denied(ctx)) return@get
                    json(FrameStats.json())
                }
                post("/api/debug/frames/reset") {
                    if (denied(ctx)) return@post
                    FrameStats.reset(); json(JSONObject().put("ok", true))
                }

                // read-only: system settings whose name matches ?q= (to find vendor keys like LED control)
                get("/api/debug/settings") {
                    if (denied(ctx)) return@get
                    val rx = Regex(q("q") ?: "led|light|rgb|joy", RegexOption.IGNORE_CASE)
                    val out = JSONObject()
                    for ((table, uri) in listOf("system" to android.provider.Settings.System.CONTENT_URI,
                        "secure" to android.provider.Settings.Secure.CONTENT_URI, "global" to android.provider.Settings.Global.CONTENT_URI)) {
                        val m = JSONObject()
                        runCatching {
                            ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                                while (c.moveToNext()) { val n = c.getString(0) ?: continue; if (rx.containsMatchIn(n)) m.put(n, c.getString(1)) }
                            }
                        }.onFailure { m.put("_error", it.message) }
                        out.put(table, m)
                    }
                    json(out)
                }

                // ---- library vs. RomM: status, offload, store ----
                get("/api/library/status") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { runCatching { Library.status(ctx, q("refresh") == "1") }.getOrElse { JSONObject().put("error", it.message) } })
                }
                post("/api/library/offload") {
                    if (denied(ctx)) return@post
                    val paths = call.request.queryParameters.getAll("path").orEmpty().mapNotNull { Storage.resolve(it)?.path }
                    if (paths.isEmpty()) return@post fail("path missing")
                    // queued: several games go one after another; GET /api/activity shows n/m
                    val name = q("name") ?: java.io.File(paths.first()).name
                    val size = paths.sumOf { p -> java.io.File(p).let { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length() } }
                    Background.offload(ctx, paths, name, size)
                    json(Background.list())
                }
                get("/api/history") {
                    if (denied(ctx)) return@get
                    json(History.list(ctx, q("limit")?.toIntOrNull() ?: 50))
                }
                get("/api/activity") {
                    if (denied(ctx)) return@get
                    json(Background.list())
                }
                get("/api/store") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { runCatching { Library.store(ctx, q("refresh") == "1") }.getOrElse { JSONObject().put("error", it.message) } })
                }
                post("/api/store/download") {
                    if (denied(ctx)) return@post
                    val id = q("id")?.toIntOrNull() ?: return@post fail("id missing")
                    json(Library.download(ctx, id).json())
                }
                get("/api/store/downloads") {
                    if (denied(ctx)) return@get
                    val o = JSONObject(); Library.downloads.forEach { (id, j) -> o.put(id.toString(), j.json()) }
                    json(o)
                }

                // ---- GPU drivers ----
                get("/api/drivers") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { Drivers.overview(ctx, q("refresh") == "1") })
                }
                post("/api/drivers/download") {
                    if (denied(ctx)) return@post
                    val url = q("url") ?: return@post fail("url missing")
                    val j = Jobs.start("Treiber laden") { job -> Drivers.download(url, job) }
                    json(j.json())
                }
                delete("/api/drivers") {
                    if (denied(ctx)) return@delete
                    if (Drivers.delete(q("name") ?: "")) json(JSONObject().put("ok", true)) else fail("not found", HttpStatusCode.NotFound)
                }

                // ---- streaming (Moonlight) ----
                get("/api/stream") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { Streaming.status(ctx) })
                }
                post("/api/stream/install") {
                    if (denied(ctx)) return@post
                    val j = Jobs.start("Moonlight installieren") { job -> Streaming.install(ctx, job) }
                    json(j.json())
                }
                get("/api/stream/hosts") {
                    if (denied(ctx)) return@get
                    json(JSONObject().put("hosts", withContext(Dispatchers.IO) { Streaming.hosts(ctx) }))
                }

                // ---- netplay (local multiplayer between two ThorBro devices) ----
                // public for the other Thor: only the open lobby; data needs the lobby PIN
                get("/api/netplay/lobby") {
                    json(Netplay.lobby?.public() ?: JSONObject().put("open", false))
                }
                post("/api/netplay/join") {
                    val l = Netplay.checkPin(q("pin")) ?: return@post fail("wrong pin or no lobby", HttpStatusCode.Forbidden)
                    Netplay.joined(l, q("name") ?: "")
                    json(l.public().put("ok", true))
                }
                get("/api/netplay/rom") {
                    val l = Netplay.checkPin(q("pin")) ?: return@get fail("wrong pin or no lobby", HttpStatusCode.Forbidden)
                    if (!l.rom.isFile) return@get fail("no file", HttpStatusCode.NotFound)
                    Transfers.begin(ctx)
                    try { call.respondFile(l.rom) } finally { Transfers.end() }
                }
                // own device (pairing code)
                get("/api/netplay") {
                    if (denied(ctx)) return@get
                    json(Netplay.status(ctx))
                }
                get("/api/netplay/peers") {
                    if (denied(ctx)) return@get
                    val res = withContext(Dispatchers.IO) { q("address")?.let { org.json.JSONArray().put(Netplay.peer(it)) } ?: Netplay.peers(ctx) }
                    json(JSONObject().put("peers", res))
                }
                post("/api/netplay/host") {
                    if (denied(ctx)) return@post
                    val rom = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    val res = withContext(Dispatchers.IO) { Netplay.host(ctx, q("system") ?: "", rom, online = q("online") == "1") }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/netplay/close") {
                    if (denied(ctx)) return@post
                    Netplay.close(ctx)
                    json(JSONObject().put("ok", true))
                }
                post("/api/netplay/connect") {
                    if (denied(ctx)) return@post
                    val addr = q("address")?.takeIf { Regex("^[0-9.]{7,15}$").matches(it) } ?: return@post fail("address missing")
                    val pin = q("pin") ?: return@post fail("pin missing")
                    json(Jobs.start("Netplay beitreten") { Netplay.join(ctx, addr, pin, it) }.json())
                }

                post("/api/netplay/azahar-server") {
                    if (denied(ctx)) return@post
                    runCatching { Netplay.setOnlineServer(ctx, q("address")) }.onFailure { return@post fail(it.message ?: "bad address") }
                    json(JSONObject().put("server", Netplay.onlineServer(ctx)))
                }
                // Thors nearby that invite over Wi-Fi Direct (radio scan; works without a shared network)
                get("/api/netplay/invites") {
                    if (denied(ctx)) return@get
                    val res = withContext(Dispatchers.IO) { Direct.invites(ctx) }
                    // the password and PIN stay on this device; the panel answers with the device address
                    lastInvites = res
                    val pub = org.json.JSONArray()
                    for (i in 0 until res.length()) res.getJSONObject(i).let { pub.put(JSONObject(it.toString()).apply { remove("pass"); remove("pin") }.put("lobby", it.optString("pin").isNotEmpty())) }
                    json(JSONObject().put("invites", pub).put("permission", Direct.permitted(ctx)))
                }
                post("/api/netplay/accept") {
                    if (denied(ctx)) return@post
                    val inv = lastInvites?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("device") == q("device") } }
                        ?: return@post fail("Einladung nicht mehr da – nochmal suchen", HttpStatusCode.NotFound)
                    json(Jobs.start("Einladung annehmen") { Netplay.accept(ctx, inv, it) }.json())
                }

                get("/api/direct") {
                    if (denied(ctx)) return@get
                    json(Direct.status(ctx))
                }
                post("/api/direct/start") {
                    if (denied(ctx)) return@post
                    val res = withContext(Dispatchers.IO) { Direct.start(ctx) }
                    json(res, if (res.has("error")) HttpStatusCode.Conflict else HttpStatusCode.OK)
                }
                post("/api/direct/stop") {
                    if (denied(ctx)) return@post
                    json(withContext(Dispatchers.IO) { Direct.stop(ctx) })
                }

                // ---- background jobs ----
                get("/api/jobs/{id}") {
                    if (denied(ctx)) return@get
                    val j = call.parameters["id"]?.toIntOrNull()?.let { Jobs.get(it) } ?: return@get fail("unknown job", HttpStatusCode.NotFound)
                    json(j.json())
                }
                post("/api/extract") {
                    if (denied(ctx)) return@post
                    val a = Storage.resolve(q("path")) ?: return@post fail("path not allowed")
                    if (!a.isFile || !Archives.isArchive(a)) return@post fail("keine .zip/.7z-Datei")
                    val dest = Storage.resolve(q("dest") ?: a.parent) ?: return@post fail("dest not allowed")
                    val j = Jobs.start("Entpacken ${a.name}") { job -> Archives.extract(a, dest, q("delete") == "1", job) }
                    json(j.json())
                }
                get("/api/storage") {
                    if (denied(ctx)) return@get
                    json(withContext(Dispatchers.IO) { StorageView.overview(ctx, q("refresh") == "1") })
                }
                post("/api/export/esde") {
                    if (denied(ctx)) return@post
                    val dest = Storage.resolve(q("dest") ?: EsDe.defaultDest) ?: return@post fail("dest not allowed")
                    val j = Jobs.start("ES-DE-Export") { job -> EsDe.export(ctx, dest, job) }
                    json(j.json())
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
                    val chunks = File(f.path + CHUNKS)
                    json(JSONObject()
                        .put("offset", if (part.exists() && !chunks.exists()) part.length() else if (chunks.exists()) 0L else 0L)
                        .put("parallel", true)
                        .put("chunks", org.json.JSONArray(if (chunks.exists()) chunks.readLines().filter { it.isNotBlank() }.map { it.substringBefore(' ').toLong() } else emptyList()))
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
                    if (q("pos") == "1") {
                        // parallel mode: chunks arrive in any order and are written at their position
                        Transfers.begin(ctx)
                        val n = try {
                            withContext(Dispatchers.IO) {
                                java.io.RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(offset)
                                    val buf = ByteArray(1 shl 20)
                                    var total = 0L
                                    call.receiveChannel().toInputStream().use { inp ->
                                        while (true) { val r = inp.read(buf); if (r < 0) break; raf.write(buf, 0, r); total += r }
                                    }
                                    total
                                }
                            }
                        } finally { Transfers.end() }
                        synchronized(Server) { File(f.path + CHUNKS).appendText("$offset $n\n") }
                        return@put json(JSONObject().put("ok", true).put("written", n))
                    }
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
                    val chunks = File(f.path + CHUNKS)
                    if (chunks.exists()) {
                        val covered = chunks.readLines().filter { it.isNotBlank() }.map { it.split(' ') }
                            .associate { it[0].toLong() to it[1].toLong() }.values.sum()
                        if (covered != size) return@post json(JSONObject().put("error", "missing chunks").put("covered", covered), HttpStatusCode.Conflict)
                        java.io.RandomAccessFile(part, "rw").use { it.setLength(size) }
                        chunks.delete()
                    }
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
