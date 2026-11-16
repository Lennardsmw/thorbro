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
 *  - Eden: LDN rooms are set up inside Eden; ThorBro starts the game and shows the host IP.
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

    class Lobby(val system: String, val rom: File, val mode: String, val pin: String, val hostName: String) {
        val startedAt = System.currentTimeMillis()
        val guests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var wrongPins = 0
        @Volatile var hostState = "starting"
        fun public(): JSONObject = JSONObject()
            .put("open", true).put("host", hostName).put("system", system)
            .put("game", if (rom.isDirectory) rom.name else rom.name.substringBeforeLast('.'))
            .put("file", rom.name).put("size", size(rom)).put("mode", mode).put("dir", rom.isDirectory)
            .put("guests", guests.size).put("since", startedAt)
    }

    @Volatile var lobby: Lobby? = null
        private set

    private fun size(f: File) = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    private fun model() = android.os.Build.MODEL ?: "Thor"

    /** "retroarch" | "ppsspp" | "eden" | null for a console, given what is installed */
    fun mode(ctx: Context, system: String): String? {
        val inst = Emulators.installed(ctx).map { it.id }.toSet()
        return when {
            system == "psp" && "ppsspp" in inst -> "ppsspp"
            system == "switch" && "eden" in inst -> "eden"
            system in retroSystems && "retroarch" in inst -> "retroarch"
            else -> null
        }
    }

    fun status(ctx: Context): JSONObject {
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
    }

    // ---------- host ----------

    fun host(ctx: Context, system: String, rom: File): JSONObject {
        val mode = mode(ctx, system) ?: return JSONObject().put("error", "Für diese Konsole gibt es kein Netplay (oder der Emulator fehlt)")
        if (rom.isDirectory) return JSONObject().put("error", "Ordner-Spiele werden noch nicht unterstützt")
        val pin = (1000..9999).random().toString()
        val l = Lobby(system, rom, mode, pin, model())
        lobby = l
        Logs.i("netplay", "Lobby offen: ${rom.name} ($mode), PIN gesetzt")
        val res = start(ctx, l, hostIp = null)
        if (res.has("error")) { lobby = null; return res }
        if (mode == "retroarch") thread(name = "netplay-host") { enableRetroArchHost(l) }
        return res.put("pin", pin).put("ip", Device.ip()).put("mode", mode)
    }

    fun close() {
        lobby?.let { Logs.i("netplay", "Lobby geschlossen: ${it.rom.name}") }
        lobby = null
    }

    /** RetroArch takes a few seconds to load the core and content; then switch hosting on (once, it is a toggle) */
    private fun enableRetroArchHost(l: Lobby) {
        Thread.sleep(6000)
        repeat(4) { attempt ->
            if (lobby !== l) return
            if (listening(RA_NETPLAY_PORT)) { l.hostState = "hosting"; Logs.i("netplay", "RetroArch hostet"); return }
            if (attempt == 0 || attempt == 2) runCatching {
                DatagramSocket().use { s ->
                    val b = "NETPLAY_HOST_TOGGLE".toByteArray()
                    s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), RA_CMD_PORT))
                }
            }
            Thread.sleep(4000)
        }
        if (listening(RA_NETPLAY_PORT)) l.hostState = "hosting"
        else { l.hostState = "manual"; Logs.i("netplay", "RetroArch hostet nicht automatisch – im RetroArch-Menü Netplay → Host starten") }
    }

    private fun listening(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500); true }
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
    fun join(ctx: Context, address: String, pin: String, job: Jobs.Job): JSONObject {
        val base = "http://${hostPart(address)}:${ThorService.PORT}"
        job.message = "Verbinde mit $address"
        val info = JSONObject(httpGet("$base/api/netplay/lobby"))
        if (!info.optBoolean("open")) throw IllegalStateException("Dort ist gerade keine Lobby offen")
        val system = info.getString("system")
        val mode = info.getString("mode")
        if (mode(ctx, system) != mode) throw IllegalStateException(when (mode) {
            "retroarch" -> "RetroArch (mit Core) fehlt auf diesem Thor"
            "ppsspp" -> "PPSSPP fehlt auf diesem Thor"
            else -> "Eden fehlt auf diesem Thor"
        })
        val joinRes = httpPost("$base/api/netplay/join?pin=${enc(pin)}&name=${enc(model())}")
        if (joinRes.first == 403) throw IllegalStateException("PIN falsch")
        if (joinRes.first != 200) throw IllegalStateException("Host antwortet nicht (HTTP ${joinRes.first})")

        val file = info.getString("file")
        val size = info.getLong("size")
        val rom = findLocal(ctx, system, file, size) ?: run {
            job.message = "Lade ${info.optString("game")} vom Host"
            download(ctx, "$base/api/netplay/rom?pin=${enc(pin)}", targetDir(ctx, system), file, size, job)
        }
        job.progress = 0.95
        job.message = "Starte ${info.optString("game")}"
        val l = Lobby(system, rom, mode, pin, info.optString("host"))
        val res = start(ctx, l, hostIp = address)
        if (res.has("error")) throw IllegalStateException(res.getString("error"))
        return res.put("game", info.optString("game")).put("host", address).put("mode", mode)
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
        val root = scan.optJSONArray("romRoots")?.optString(0)?.takeIf { it.isNotEmpty() }?.let(::File) ?: File(Storage.primary, "ROMs")
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
            else -> Emulators.launch(ctx, l.system, l.rom, "eden").apply {
                put("hint", if (hostIp == null) "In Eden: Mehrspieler → Raum erstellen. IP für den Gast: ${Device.ip()}"
                    else "In Eden: Mehrspieler → Direkt verbinden → $hostIp")
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
        runCatching { JSONObject(httpGet("http://${hostPart(address)}:${ThorService.PORT}/api/netplay/lobby", 2500)) }
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
