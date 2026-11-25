package wtf.lennard.thorbro

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local multiplayer between two Thors (or any two ThorBro devices) in the same WLAN or hotspot.
 *
 * Netplay, not video streaming: both devices run the emulator themselves and only inputs travel,
 * so there is no picture delay. Flow:
 *  - Host picks a game → ThorBro opens a "lobby" with a 4-digit PIN and starts the emulator as host.
 *  - Guest finds hosts via mDNS (_thorbro._tcp, the same announcement the server already makes),
 *    reads the open lobby, joins with the PIN, gets the ROM from the host if it lacks it, and starts
 *    the same emulator as client pointed at the host.
 *
 * Per emulator:
 *  - RetroArch: ThorBro writes its own config (the user's retroarch.cfg + netplay keys) and starts with it.
 *    Host: hosting is switched on through RetroArch's network command port (UDP 55355, NETPLAY_HOST_TOGGLE).
 *    Guest: RetroArch has no "connect on start" option; the host IP is pre-filled, the guest confirms once
 *    in RetroArch's menu (Netplay → Connect to host).
 *  - PPSSPP: ad-hoc via the built-in PRO Adhoc server; ThorBro writes [Network] in ppsspp.ini
 *    (host: server on, guest: server = host IP), then the game's own multiplayer menu works.
 *  - Eden (Switch, LDN) and Azahar (3DS): every device runs its own room all the time ([EdenRoom], [Room]); the
 *    emulator always joins 127.0.0.1 and [Relay] leads that to the own room or, in a party, through the [PartyNet]
 *    tunnel to the leader's (same WLAN, NetBird or the internet). ThorBro makes the guest's 3DS name and console ID
 *    differ from the host's (otherwise Azahar refuses the join).
 *  - "share": only hands the game over (no emulator start) — the guest keeps the file.
 *
 * Hosting also opens ThorBro's Wi-Fi Direct net (when allowed): a Thor nearby then gets the invite over the air,
 * even without a shared WLAN.
 *
 * The lobby endpoints are reachable without the pairing code (the friend's Thor is not paired);
 * everything that hands out data needs the lobby PIN, and the lobby closes after too many wrong PINs.
 */
object Netplay {
    const val RA_NETPLAY_PORT = 55435
    private const val RA_CMD_PORT = 55355
    private const val PPSSPP = "org.ppsspp.ppsspp"
    private val dir = File(Storage.primary, "ThorBro/netplay")

    /** consoles whose RetroArch core is known to work with RetroArch netplay (deterministic, savestates) */
    private val retroSystems = setOf(
        "nes", "snes", "gb", "gbc", "gba", "megadrive", "mastersystem", "gamegear", "pcengine", "atari2600",
        "arcade", "lynx", "ngp", "wonderswan", "n64",
    )
    private val shaky = setOf("n64", "gba")

    class Lobby(val system: String, val rom: File, val mode: String, val pin: String, val hostName: String,
                val roomIp: String? = null, val az: JSONObject? = null) {
        val startedAt = System.currentTimeMillis()
        val guests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var wrongPins = 0
        @Volatile var hostState = "starting"
        /** opened by [AutoPlay] for a game started in another frontend; closes when [pkg] is gone */
        @Volatile var auto = false
        @Volatile var pkg: String? = null
        fun public(): JSONObject = JSONObject()
            .put("open", true).put("host", hostName).put("system", system)
            .put("game", if (rom.isDirectory) rom.name else rom.name.substringBeforeLast('.'))
            .put("file", rom.name).put("size", size(rom)).put("mode", mode).put("dir", rom.isDirectory)
            .put("guests", guests.size).put("since", startedAt).put("hostId", appCtx?.let { Me.id(it) })
            .put("roomIp", roomIp).put("roomPort", Azahar.ROOM_PORT)
            .put("azUser", az?.optString("user")).put("azConsole", az?.optString("console"))
    }

    /** what this device joined last (guest side), for the status line */
    @Volatile var joinedRoom: JSONObject? = null

    /** guest session over: 127.0.0.1 points at this Thor again (own room if the server runs) */
    fun clearJoined(ctx: Context? = null) {
        joinedRoom = null
        (ctx ?: appCtx)?.let { Relay.route(it) } // back to the party's or the own room
    }

    /** use the 3DS room of the Thor at [address] (its server or lobby): Azahar keeps joining 127.0.0.1 */
    fun useRoomOf(ctx: Context, address: String, name: String): JSONObject {
        appCtx = ctx.applicationContext
        Relay.start(address, name)?.let { return JSONObject().put("error", it) }
        val hint = Azahar.guestSteps(name)
        joinedRoom = JSONObject().put("game", "3DS-Raum").put("host", name).put("address", address).put("mode", "azahar")
            .put("roomIp", "127.0.0.1").put("hint", hint).put("at", System.currentTimeMillis())
        return JSONObject().put("mode", "azahar").put("host", name).put("hint", hint)
    }

    /** what /api/netplay/lobby tells another Thor; a friend (by id) also gets the PIN, like the Wi-Fi Direct invite */
    fun publicInfo(ctx: Context, askerId: String?): JSONObject {
        val l = lobby
        return (l?.public() ?: JSONObject().put("open", false))
            .put("host", model()).put("hostId", Me.id(ctx))
            .put("server", false).put("room", Room.running() && Room.ready).put("card", Pals.card(ctx))
            .apply { if (l != null && Friends.isFriend(ctx, askerId)) put("pin", l.pin) }
    }

    /**
     * Invites nearby, from both ways: Thors in the same WLAN (mDNS, no network switch needed, so preferred) and
     * Thors announcing over Wi-Fi Direct (not in "Nur WLAN"). Only Thors with a lobby or a running server.
     */
    fun invites(ctx: Context): List<JSONObject> {
        val me = Me.id(ctx)
        val wlan = if (Device.ip() == null) emptyList() else peers(ctx).objs().mapNotNull { p ->
            val l = p.optJSONObject("lobby") ?: return@mapNotNull null
            val id = l.optString("hostId")
            if (!p.optBoolean("reachable") || id == me || !(l.optBoolean("open") || l.optBoolean("server"))) return@mapNotNull null
            JSONObject().put("device", "wlan:" + p.optString("address")).put("address", p.optString("address")).put("via", "WLAN")
                .put("id", id).put("friend", Friends.isFriend(ctx, id)).put("name", l.optString("host", "Thor"))
                .put("game", if (l.optBoolean("open")) l.optString("game") else "Spielserver")
                .put("mode", if (l.optBoolean("open")) l.optString("mode") else "server").put("pin", l.optString("pin"))
                .put("room", l.optBoolean("room"))
        }
        val direct = if (!Direct.permitted(ctx)) emptyList() else Direct.invites(ctx).objs().map { it.put("via", "Wi-Fi Direct") }
        val ids = wlan.map { it.optString("id") }.toSet()
        return wlan + direct.filter { it.optString("id") !in ids }
    }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    @Volatile var lobby: Lobby? = null
        private set

    /** Wi-Fi Direct was switched on by hosting (and goes off again with the lobby) */
    @Volatile private var autoDirect = false

    /** how each emulator plays together, for the multiplayer page */
    fun services(ctx: Context): JSONArray {
        val inst = Emulators.installed(ctx).map { it.id }.toSet()
        val services = JSONArray()
        fun svc(id: String, name: String, how: String) = services.put(JSONObject().put("id", id).put("name", name).put("how", how))
        if ("azahar" in inst) svc("azahar", "3DS (Azahar)",
            if (Room.running() && Room.ready) "einmal in Azahar: Mehrspieler → Direkt verbinden → 127.0.0.1, Port ${Azahar.PORT}" else Room.error ?: "Raum ist aus")
        if ("eden" in inst) svc("eden", "Switch (Eden)",
            if (EdenRoom.running() && EdenRoom.ready) "einmal in Eden: Mehrspieler → Raum beitreten → 127.0.0.1, Port ${EdenRoom.PORT}" else EdenRoom.error ?: "Raum ist aus")
        if ("ppsspp" in inst) svc("ppsspp", "PSP (PPSSPP)", "Ad-hoc-Server des Leiters · nur im gleichen Netz")
        if ("retroarch" in inst) svc("retroarch", "Retro (RetroArch)", "Leiter hostet · nur im gleichen Netz")
        return services
    }

    private fun size(f: File) = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    private fun model() = appCtx?.let { Me.name(it) } ?: (android.os.Build.MODEL ?: "Thor")

    /** "retroarch" | "ppsspp" | "eden" | "azahar" | null for a console, given what is installed */
    fun mode(ctx: Context, system: String): String? {
        val inst = Emulators.installed(ctx).map { it.id }.toSet()
        return when {
            system == "psp" && "ppsspp" in inst -> "ppsspp"
            system == "switch" && "eden" in inst -> "eden"
            system == "n3ds" && "azahar" in inst -> "azahar"
            system in retroSystems && "retroarch" in inst -> "retroarch"
            else -> null
        }
    }

    fun status(ctx: Context): JSONObject {
        appCtx = ctx.applicationContext
        val supported = JSONArray()
        Roms.systems.forEach { s -> mode(ctx, s.id)?.let { m ->
            supported.put(JSONObject().put("id", s.id).put("name", s.name).put("mode", m).put("shaky", s.id in shaky))
        } }
        return JSONObject()
            .put("ip", Device.ip())
            .put("name", model())
            .put("shizuku", Leds.shizukuState())
            .put("supported", supported)
            .put("lobby", lobby?.let { it.public().put("pin", it.pin).put("hostState", it.hostState).put("guestNames", JSONArray(it.guests.toList())) })
            .put("joined", joinedRoom)
            .put("addresses", Room.addresses())
            .put("me", JSONObject().put("id", Me.id(ctx)).put("name", Me.name(ctx)))
            .put("direct", Direct.active())
            .put("services", services(ctx))
            .put("relay", Relay.status())
            .put("partyNet", PartyNet.status())
            .put("party", Party.json(ctx).opt("party"))
            .put("azahar", if ("azahar" in Emulators.installed(ctx).map { it.id }) JSONObject()
                .put("identity", Azahar.identity(ctx))
                .put("room", if (Relay.active()) (if (Relay.connected()) "connected" else "waiting") else Azahar.roomState())
                .put("server", Room.status().put("available", Room.available(ctx)))
                .put("ip", roomIp()) else null)
    }

    /** the IP the other Thor reaches this one at: in its own Wi-Fi Direct net the group address, else the WLAN */
    fun roomIp(): String? = if (Direct.active()) Direct.ownIp() ?: Direct.OWNER_IP else Device.ip()

    // ---------- host ----------

    /** "Mit Freund spielen": opens the lobby, the room (3DS) and Wi-Fi Direct for invites, then starts the game */
    fun host(ctx: Context, system: String, rom: File): JSONObject {
        val mode = mode(ctx, system) ?: return JSONObject().put("error", "Für diese Konsole gibt es kein Netplay (oder der Emulator fehlt)")
        if (rom.isDirectory) return JSONObject().put("error", "Ordner-Spiele werden noch nicht unterstützt")
        appCtx = ctx.applicationContext
        if (mode == "azahar") { Room.start(ctx, rom.name.substringBeforeLast('.'))?.let { return JSONObject().put("error", it) }; joinedRoom = null; Relay.route(ctx) }
        val l = open(ctx, system, rom, mode)
        val res = start(ctx, l, hostIp = null)
        if (res.has("error")) { close(ctx); return res }
        when (mode) {
            "retroarch" -> thread(name = "netplay-host") { enableRetroArchHost(l) }
            "azahar" -> thread(name = "netplay-azahar") { watchRoom(l) }
        }
        return res.put("pin", l.pin).put("ip", l.roomIp).put("mode", mode).put("direct", Direct.active())
    }

    /** a game already running (started from another frontend, seen by [AutoPlay]): open the lobby around it */
    fun hostRunning(ctx: Context, system: String, rom: File, pkg: String): JSONObject {
        val mode = mode(ctx, system) ?: return JSONObject().put("error", "kein Netplay für $system")
        appCtx = ctx.applicationContext
        if (mode == "azahar") Room.start(ctx, rom.name.substringBeforeLast('.'))?.let { return JSONObject().put("error", it) }
        val l = open(ctx, system, rom, mode)
        l.auto = true; l.pkg = pkg
        when (mode) {
            "retroarch" -> { l.hostState = "starting"; thread(name = "netplay-host") { enableRetroArchHost(l, wait = 0) } }
            "azahar" -> thread(name = "netplay-azahar") { watchRoom(l) }
            else -> l.hostState = "hosting" // PPSSPP: built-in server is on (prepare); Eden: room in Eden
        }
        Logs.i("netplay", "Automatisch eingeladen: ${rom.name}")
        return JSONObject().put("pin", l.pin).put("mode", mode)
    }

    /**
     * Once, when the first friend is added (and after each guest session): emulators ready to host from any frontend.
     * RetroArch: network commands on (ThorBro switches hosting on through them). PPSSPP: WLAN + built-in ad-hoc server on.
     * Only touches an emulator that is not running (they write their settings back on exit). Backups in ThorBro/netplay.
     */
    fun prepare(ctx: Context): JSONObject {
        appCtx = ctx.applicationContext
        val out = JSONObject()
        val inst = Emulators.installed(ctx).map { it.id }.toSet()
        fun running(pkg: String) = Leds.shellOut("pidof", pkg)?.isNotBlank() == true
        if ("retroarch" in inst) out.put("retroarch", when {
            running(Emulators.RA) -> "läuft gerade – später"
            else -> {
                val cur = Leds.shellOut("cat", Emulators.RA_CONFIG)
                when {
                    cur == null -> "ohne Shizuku nicht möglich"
                    Regex("(?m)^\\s*network_cmd_enable\\s*=\\s*\"true\"").containsMatchIn(cur) -> "bereit"
                    else -> {
                        dir.mkdirs(); File(dir, "retroarch.cfg.bak").writeText(cur)
                        val keys = mapOf("network_cmd_enable" to "true", "network_cmd_port" to "$RA_CMD_PORT")
                        val text = cur.lineSequence().filter { l -> keys.keys.none { k -> l.trimStart().startsWith("$k ") || l.trimStart().startsWith("$k=") } }
                            .joinToString("\n").trimEnd() + "\n" + keys.entries.joinToString("") { (k, v) -> "$k = \"$v\"\n" }
                        if (Leds.shellOut("sh", "-c", "cat > ${Shell.q(Emulators.RA_CONFIG)}", stdin = text.toByteArray()) != null) "eingerichtet" else "Schreiben ging nicht"
                    }
                }
            }
        })
        if ("ppsspp" in inst) out.put("ppsspp", if (running(PPSSPP)) "läuft gerade – später" else if (ppssppConfig(null)) "eingerichtet" else "ppsspp.ini nicht gefunden")
        Logs.i("netplay", "Vorbereitet: $out")
        return out
    }

    /** the game's picture (Cocoon icon, else the box art), for tiles and notifications; may download, call off the main thread */
    fun art(ctx: Context, system: String, rom: File): File? = runCatching {
        Cocoon.media(ctx, system, rom.path, "icon") ?: run {
            val r = JSONObject().put("path", rom.path).put("file", rom.name).put("dir", rom.isDirectory)
            Media.metaFor(ctx, system, listOf(r)).optJSONObject(rom.path)?.optString("cover")?.takeIf { it.isNotEmpty() }
                ?.let { Media.cover(ctx, system, it) }
        }
    }.getOrNull()

    /** (console, ROM) by path or by name (RetroArch only tells the name) */
    fun findRom(ctx: Context, path: String? = null, name: String? = null): Pair<String, File>? {
        fun norm(x: String) = x.substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() }
        val want = name?.let(::norm)
        val canon = path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        val systems = Roms.scan(ctx, false).optJSONArray("systems") ?: return null
        for (i in 0 until systems.length()) {
            val sys = systems.getJSONObject(i)
            val roms = sys.optJSONArray("roms") ?: continue
            for (j in 0 until roms.length()) {
                val r = roms.getJSONObject(j)
                val p = r.optString("path")
                val hit = (canon != null && (p == path || runCatching { File(p).canonicalPath }.getOrDefault(p) == canon)) ||
                    (want != null && want.isNotEmpty() && (norm(r.optString("file")) == want || norm(r.optString("name")) == want))
                if (hit && !r.optBoolean("dir")) return sys.getString("id") to File(p)
            }
        }
        return null
    }

    /** "Spiel weitergeben": offers the file only; a Thor nearby gets the invite and keeps the game */
    fun share(ctx: Context, system: String, rom: File): JSONObject {
        if (!rom.isFile) return JSONObject().put("error", "Nur einzelne Spieldateien lassen sich weitergeben")
        appCtx = ctx.applicationContext
        val l = open(ctx, system, rom, "share")
        l.hostState = "sharing"
        return JSONObject().put("pin", l.pin).put("ip", l.roomIp).put("mode", "share").put("direct", Direct.active())
    }

    private fun open(ctx: Context, system: String, rom: File, mode: String): Lobby {
        ensureDirect(ctx)
        val l = Lobby(system, rom, mode, (1000..9999).random().toString(), model(), roomIp = roomIp(),
            az = if (mode == "azahar") Azahar.identity(ctx, rom) else null)
        lobby = l
        Logs.i("netplay", "Lobby offen: ${rom.name} ($mode), PIN gesetzt")
        Direct.refreshInvite(ctx)
        Party.lobbyChanged(ctx) // the party sees the game in our status and joins
        return l
    }

    /** Wi-Fi Direct on (if allowed and not already in someone's net), so nearby Thors see the invite */
    private fun ensureDirect(ctx: Context) {
        if (Direct.active() || Direct.inForeignGroup() || !Direct.permitted(ctx)) return
        val r = runCatching { Direct.start(ctx) }.getOrNull()
        if (r != null && !r.has("error")) autoDirect = true
        else Logs.i("netplay", "Wi-Fi Direct nicht gestartet: ${r?.optString("error")}")
    }

    fun close(ctx: Context? = null) {
        lobby?.let { Logs.i("netplay", "Lobby geschlossen: ${it.rom.name}") }
        lobby = null
        val c = ctx ?: appCtx
        c?.let { Party.lobbyChanged(it) }
        if (c != null) {
            if (autoDirect) { autoDirect = false; runCatching { Direct.stop(c) } } else Direct.refreshInvite(c)
        }
    }

    /** Azahar: the room runs here; "hosting" while it is up, guests are whoever else is in it */
    private fun watchRoom(l: Lobby) {
        while (lobby === l) {
            l.hostState = if (Room.running() && Room.ready) "hosting" else "error"
            Thread.sleep(2000)
        }
    }

    /** RetroArch takes a few seconds to load the core and content; then switch hosting on (once, it is a toggle) */
    private fun enableRetroArchHost(l: Lobby, wait: Long = 6000) {
        Thread.sleep(wait)
        repeat(4) { attempt ->
            if (lobby !== l) return
            if (listening(RA_NETPLAY_PORT)) { l.hostState = "hosting"; Logs.i("netplay", "RetroArch hostet"); return }
            if (attempt == 0 || attempt == 2) runCatching {
                DatagramSocket().use { s ->
                    val b = "NETPLAY_HOST_TOGGLE".toByteArray()
                    s.send(DatagramPacket(b, b.size, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), RA_CMD_PORT))
                }
            }
            Thread.sleep(4000)
        }
        if (listening(RA_NETPLAY_PORT)) l.hostState = "hosting"
        else { l.hostState = "manual"; Logs.i("netplay", "RetroArch hostet nicht automatisch – im RetroArch-Menü Netplay → Host starten") }
    }

    private fun listening(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 500); true }
    }.getOrDefault(false)

    /** called by the guest over HTTP; checks the PIN and counts wrong tries */
    fun checkPin(pin: String?): Lobby? {
        val l = lobby ?: return null
        if (pin == l.pin) return l
        l.wrongPins++
        if (l.wrongPins >= 10) { Logs.i("netplay", "Lobby wegen falscher PINs geschlossen"); close() }
        return null
    }

    fun joined(l: Lobby, who: String) {
        val name = who.take(40).ifBlank { "Gast" }
        if (name !in l.guests) l.guests += name
        Logs.i("netplay", "$name ist beigetreten")
    }

    // ---------- guest ----------

    /** Joins the lobby on [address] with [pin]: fetch ROM if needed, configure, start. Runs as a job. */
    fun join(ctx: Context, address: String, pin: String, job: Jobs.Job, party: Boolean = false): JSONObject {
        appCtx = ctx.applicationContext
        val base = "http://${hostPart(address)}:${ThorService.PORT}"
        job.message = "Verbinde mit $address"
        val info = JSONObject(httpGet("$base/api/netplay/lobby"))
        if (!info.optBoolean("open")) throw IllegalStateException("Dort ist gerade keine Lobby offen")
        val system = info.getString("system")
        val mode = info.getString("mode")
        if (mode != "share" && mode(ctx, system) != mode) throw IllegalStateException(when (mode) {
            "retroarch" -> "RetroArch (mit Core) fehlt auf diesem Thor"
            "ppsspp" -> "PPSSPP fehlt auf diesem Thor"
            "azahar" -> "Azahar fehlt auf diesem Thor"
            else -> "Eden fehlt auf diesem Thor"
        })
        val joinRes = httpPost("$base/api/netplay/join?pin=${enc(pin)}&name=${enc(model())}&id=${enc(Me.id(ctx))}")
        if (joinRes.first == 403) throw IllegalStateException("PIN falsch")
        if (joinRes.first != 200) throw IllegalStateException("Host antwortet nicht (HTTP ${joinRes.first})")

        Friends.add(ctx, info.optString("hostId").ifEmpty { null }, info.optString("host"))
        val file = info.getString("file")
        val size = info.getLong("size")
        val rom = findLocal(ctx, system, file, size) ?: run {
            job.message = "Lade ${info.optString("game")} vom Host"
            download(ctx, "$base/api/netplay/rom?pin=${enc(pin)}", targetDir(ctx, system), file, size, job)
        }
        if (mode == "share") {
            job.progress = 1.0
            return JSONObject().put("game", info.optString("game")).put("host", address).put("mode", mode)
                .put("hint", "${info.optString("game")} ist jetzt auf diesem Thor:\n${rom.path}")
        }
        job.progress = 0.95
        job.message = "Starte ${info.optString("game")}"
        var changed: String? = null
        if (mode == "azahar") {
            forceStop(Azahar.PKG) // it keeps the 3DS settings in memory and would write them back
            Thread.sleep(400)
            changed = runCatching { Azahar.makeDistinct(ctx, rom, info.optString("azUser").ifEmpty { null }, info.optString("azConsole").ifEmpty { null }) }
                .onFailure { Logs.i("azahar", "3DS-Name/ID nicht angepasst: ${it.message}") }.getOrNull()
        }
        // Azahar joins 127.0.0.1; ThorBro passes it on to the host's room: in a party through its tunnel (already
        // routed), else straight to the address this Thor just reached it at
        val viaParty = party && PartyNet.guest != null
        if (mode == "azahar" && !viaParty) Relay.start(address, info.optString("host"), info.optInt("roomPort", Azahar.ROOM_PORT))?.let { throw IllegalStateException(it) }
        val roomIp = address
        val l = Lobby(system, rom, mode, pin, info.optString("host"), roomIp = roomIp)
        val res = start(ctx, l, hostIp = address)
        if (res.has("error")) throw IllegalStateException(res.getString("error"))
        if (changed != null) res.put("hint", res.optString("hint") + "\n\nAngepasst, damit Azahar euch auseinanderhält: $changed")
        if (!viaParty) joinedRoom = JSONObject().put("game", info.optString("game")).put("host", info.optString("host")).put("address", address)
            .put("mode", mode).put("roomIp", roomIp).put("hint", res.optString("hint")).put("at", System.currentTimeMillis())
        return res.put("game", info.optString("game")).put("host", address).put("mode", mode)
    }

    /**
     * Party member, leader not in the same network: the rooms go through the party tunnel (3DS, Switch), so this device
     * only has to start the same game — it must already be here (game files do not go through the tunnel).
     */
    fun startForParty(ctx: Context, g: JSONObject, leaderName: String): JSONObject {
        appCtx = ctx.applicationContext
        val mode = g.optString("mode")
        val title = g.optString("title")
        if (mode !in setOf("azahar", "eden")) return JSONObject().put("error", "Nicht im gleichen Netz – ${modeName(mode)} geht nur im gleichen WLAN oder über NetBird")
        if (PartyNet.guest == null) return JSONObject().put("error", "Keine Verbindung zu $leaderName (Party-Tunnel kommt nicht zustande)")
        if (mode(ctx, g.optString("system")) != mode) return JSONObject().put("error", "${modeName(mode)} fehlt auf diesem Thor")
        val (system, rom) = findRom(ctx, name = title)?.takeIf { it.first == g.optString("system") }
            ?: return JSONObject().put("error", "$title fehlt auf diesem Thor – übers Internet kommt das Spiel nicht mit")
        var changed: String? = null
        if (mode == "azahar") {
            forceStop(Azahar.PKG); Thread.sleep(400)
            changed = runCatching { Azahar.makeDistinct(ctx, rom, g.optString("azUser").ifEmpty { null }, g.optString("azConsole").ifEmpty { null }) }.getOrNull()
        }
        Relay.route(ctx)
        val res = start(ctx, Lobby(system, rom, mode, g.optString("pin"), leaderName, roomIp = "127.0.0.1"), hostIp = "127.0.0.1")
        if (changed != null) res.put("hint", res.optString("hint") + "\n\nAngepasst, damit Azahar euch auseinanderhält: $changed")
        Logs.i("netplay", "Party über den Tunnel: $title ($mode)")
        return res
    }

    private fun modeName(m: String) = when (m) { "retroarch" -> "RetroArch"; "ppsspp" -> "PPSSPP"; "eden" -> "Eden"; "azahar" -> "Azahar"; else -> m }

    /** one button for an invite from [Direct.invites]: join the other Thor's net, then its lobby (PIN came with the invite) */
    fun accept(ctx: Context, invite: JSONObject, job: Jobs.Job): JSONObject {
        job.message = "Verbinde mit ${invite.optString("name")}"
        appCtx = ctx.applicationContext
        // same WLAN: straight there; else into the other Thor's Wi-Fi Direct net
        val addr = invite.optString("address").ifEmpty {
            Direct.join(ctx, invite.getString("ssid"), invite.getString("pass")); Direct.OWNER_IP
        }
        Friends.add(ctx, invite.optString("id").ifEmpty { null }, invite.optString("name")) // next time: just a notification
        val pin = invite.optString("pin")
        if (pin.isEmpty()) {
            // a running server without a game: use its 3DS room (Azahar keeps joining 127.0.0.1)
            if (invite.optString("mode") == "server" && "azahar" in Emulators.installed(ctx).map { it.id }) {
                val r = useRoomOf(ctx, addr, invite.optString("name"))
                if (r.has("error")) throw IllegalStateException(r.getString("error"))
                return r
            }
            return JSONObject().put("hint", "Verbunden mit ${invite.optString("name")}. Dort ist noch keine Lobby offen – sobald dort ein Spiel startet, kommt die Einladung.")
        }
        var last: Throwable? = null
        repeat(5) { // the HTTP server needs a moment after the link comes up
            runCatching { return join(ctx, addr, pin, job) }.onFailure { last = it }
            if (last?.message?.contains("PIN") == true) throw last!!
            Thread.sleep(1500)
        }
        throw last ?: IllegalStateException("Lobby nicht erreichbar")
    }

    private fun findLocal(ctx: Context, system: String, file: String, size: Long): File? {
        val scan = Roms.scan(ctx, false).getJSONArray("systems")
        for (i in 0 until scan.length()) {
            val s = scan.getJSONObject(i)
            if (s.getString("id") != system) continue
            val roms = s.getJSONArray("roms")
            for (j in 0 until roms.length()) {
                val r = roms.getJSONObject(j)
                if (r.optString("file") == file && r.optLong("size") == size) return File(r.getString("path")).takeIf { it.exists() }
            }
        }
        return null
    }

    /** where other games of that console already are, else <ROM root>/<console>, else /ROMs/<console> */
    private fun targetDir(ctx: Context, system: String): File {
        val scan = Roms.scan(ctx, false)
        val systems = scan.getJSONArray("systems")
        for (i in 0 until systems.length()) {
            val s = systems.getJSONObject(i)
            if (s.getString("id") == system && s.getJSONArray("roms").length() > 0)
                return File(s.getJSONArray("roms").getJSONObject(0).getString("path")).parentFile!!
        }
        val root = Library.romsRoot(ctx)
        return File(root, system)
    }

    private fun download(ctx: Context, url: String, dir: File, name: String, size: Long, job: Jobs.Job): File {
        if (name.contains('/') || name.startsWith(".")) throw IllegalStateException("Ungültiger Dateiname")
        dir.mkdirs()
        if (Storage.free(dir) < size + 64L * 1024 * 1024) throw IllegalStateException("Zu wenig Speicher für ${fmt(size)}")
        val part = File(dir, "$name.thorbro-part")
        val out = File(dir, name)
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 60_000 }
        if (c.responseCode != 200) throw IllegalStateException("ROM-Übertragung: HTTP ${c.responseCode}")
        Transfers.begin(ctx)
        try {
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(1 shl 17); var done = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (size > 0) job.progress = 0.9 * done / size
                }
            } }
            if (part.length() != size) { part.delete(); throw IllegalStateException("Übertragung unvollständig") }
            if (!part.renameTo(out)) throw IllegalStateException("Konnte ${out.name} nicht speichern")
        } finally { Transfers.end() }
        Roms.invalidate()
        Logs.i("netplay", "ROM empfangen: ${out.path}")
        return out
    }

    // ---------- start with netplay settings ----------

    private fun start(ctx: Context, l: Lobby, hostIp: String?): JSONObject {
        appCtx = ctx.applicationContext
        return when (l.mode) {
            "retroarch" -> {
                val cfg = retroArchConfig(hostIp)
                forceStop(Emulators.RA)
                Emulators.launch(ctx, l.system, l.rom, "retroarch", raConfig = cfg.path).apply {
                    if (hostIp != null) put("hint", "In RetroArch: Menü → Netplay → Mit Netplay-Host verbinden → $hostIp bestätigen")
                }
            }
            "ppsspp" -> {
                val ok = ppssppConfig(hostIp)
                Emulators.launch(ctx, l.system, l.rom, "ppsspp").apply {
                    put("hint", "Im Spiel den Ad-hoc-/Multiplayer-Modus wählen" + if (ok) "" else " (ppsspp.ini nicht gefunden: in PPSSPP Einstellungen → Netzwerk → WLAN an, Adhoc-Server ${hostIp ?: "an"})")
                }
            }
            "azahar" -> {
                forceStop(Azahar.PKG)
                Emulators.launch(ctx, l.system, l.rom, "azahar").apply {
                    put("hint", if (hostIp == null) Azahar.hostSteps() else Azahar.guestSteps(l.hostName))
                }
            }
            else -> Emulators.launch(ctx, l.system, l.rom, "eden").apply {
                // the rooms run in ThorBro; Eden always joins 127.0.0.1 (host and guest alike), [Relay] does the rest
                put("hint", "In Eden: Mehrspieler → Raum beitreten → 127.0.0.1, Port ${EdenRoom.PORT} (Eden merkt es sich), dann im Spiel lokal spielen")
            }
        }
    }

    @Volatile private var appCtx: Context? = null

    private fun forceStop(pkg: String) {
        if (Leds.shellOut("am", "force-stop", pkg) == null)
            runCatching { appCtx?.getSystemService(android.app.ActivityManager::class.java)?.killBackgroundProcesses(pkg) }
    }

    /**
     * The user's retroarch.cfg (read through Shizuku; Android/data is closed to ThorBro) with the netplay keys
     * replaced. Without Shizuku the file only #includes the user's config, so their settings still load.
     */
    private fun retroArchConfig(hostIp: String?): File {
        val ours = linkedMapOf(
            "network_cmd_enable" to "true",
            "network_cmd_port" to "$RA_CMD_PORT",
            "netplay_nickname" to model(),
            "netplay_ip_port" to "$RA_NETPLAY_PORT",
            "netplay_ip_address" to (hostIp ?: ""),
            "netplay_use_mitm_server" to "false",
            "netplay_public_announce" to "false",
            "netplay_start_as_spectator" to "false",
            "config_save_on_exit" to "false",
        )
        val user = Leds.shellOut("cat", Emulators.RA_CONFIG)
        val text = StringBuilder()
        if (user != null) {
            user.lineSequence().filter { line -> ours.keys.none { k -> line.trimStart().startsWith("$k ") || line.trimStart().startsWith("$k=") } }
                .forEach { text.append(it).append('\n') }
        } else {
            text.append("#include \"${Emulators.RA_CONFIG}\"\n")
        }
        ours.forEach { (k, v) -> text.append("$k = \"$v\"\n") }
        dir.mkdirs()
        val f = File(dir, "retroarch-netplay.cfg")
        f.writeText(text.toString())
        return f
    }

    /** PPSSPP keeps ppsspp.ini in its memory stick: /PSP (if chosen) or its Android/data folder */
    private fun ppssppConfig(hostIp: String?): Boolean {
        forceStop(PPSSPP) // it rewrites ppsspp.ini on exit
        Thread.sleep(400)
        val values = mapOf(
            "EnableWlan" to "True",
            "EnableAdhocServer" to if (hostIp == null) "True" else "False",
            "proAdhocServer" to (hostIp ?: "127.0.0.1"),
        )
        val public = File(Storage.primary, "PSP/SYSTEM/ppsspp.ini")
        if (public.isFile) {
            public.copyTo(File(dir.apply { mkdirs() }, "ppsspp.ini.bak"), overwrite = true)
            public.writeText(setIni(public.readText(), "Network", values))
            return true
        }
        val priv = "/storage/emulated/0/Android/data/$PPSSPP/files/PSP/SYSTEM/ppsspp.ini"
        val cur = Leds.shellOut("cat", priv) ?: return false
        dir.mkdirs()
        File(dir, "ppsspp.ini.bak").writeText(cur)
        return Leds.shellOut("sh", "-c", "cat > '$priv'", stdin = setIni(cur, "Network", values).toByteArray()) != null
    }

    /** sets keys inside one [section] of an ini text, adding section/keys when missing */
    internal fun setIni(text: String, section: String, values: Map<String, String>): String {
        val lines = text.lines().toMutableList()
        var start = lines.indexOfFirst { it.trim().equals("[$section]", true) }
        if (start < 0) { if (lines.lastOrNull()?.isNotBlank() == true) lines += ""; lines += "[$section]"; start = lines.size - 1 }
        var end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val left = values.toMutableMap()
        for (i in start + 1 until end) {
            val key = lines[i].substringBefore('=').trim()
            left.remove(key)?.let { lines[i] = "$key = $it" }
        }
        left.forEach { (k, v) -> lines.add(end, "$k = $v"); end++ }
        return lines.joinToString("\n")
    }

    // ---------- finding other Thors ----------

    /** other ThorBro devices in the network, each with its lobby (if one is open) */
    fun peers(ctx: Context, ms: Long = 2500): JSONArray {
        val me = Device.ip()
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { found[s.serviceName] = s }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        nsd.discoverServices("_thorbro._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        Thread.sleep(ms)
        runCatching { nsd.stopServiceDiscovery(l) }
        val out = JSONArray()
        // inside another Thor's Wi-Fi Direct network the owner is always 192.168.49.1 (mDNS may not pass there)
        if (Direct.inForeignGroup()) out.put(peer(Direct.OWNER_IP).put("service", "Wi-Fi Direct"))
        found.values.forEach { s ->
            val latch = CountDownLatch(1)
            var res: NsdServiceInfo? = null
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { latch.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) { res = i; latch.countDown() }
            })
            latch.await(3, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            val addr = res?.host?.hostAddress ?: return@forEach
            if (addr == me || addr.contains(':') || (Direct.inForeignGroup() && addr == Direct.OWNER_IP)) return@forEach
            out.put(peer(addr).put("service", s.serviceName))
        }
        return out
    }

    /** one device by IP (also for typing the IP by hand when mDNS is blocked) */
    fun peer(address: String): JSONObject {
        val o = JSONObject().put("address", address)
        runCatching { JSONObject(httpGet("http://${hostPart(address)}:${ThorService.PORT}/api/netplay/lobby?id=${appCtx?.let { Me.id(it) } ?: ""}", 2500)) }
            .onSuccess { o.put("reachable", true).put("lobby", it) }
            .onFailure { o.put("reachable", false) }
        return o
    }

    // ---------- small helpers ----------

    private fun hostPart(a: String) = if (a.contains(':')) "[$a]" else a
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun fmt(n: Long) = "%.1f GB".format(n / 1e9)

    private fun httpGet(url: String, timeout: Int = 5000): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = timeout; readTimeout = timeout
            if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
            inputStream.bufferedReader().use { it.readText() }
        }

    private fun httpPost(url: String): Pair<Int, String> =
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000; doOutput = true
            outputStream.close()
            val code = responseCode
            code to runCatching { (if (code < 400) inputStream else errorStream).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
}
