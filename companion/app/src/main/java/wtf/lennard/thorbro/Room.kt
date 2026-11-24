package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * The 3DS multiplayer room runs on the Thor itself: Azahar's room server (companion/room, built by
 * scripts/build-room.sh, shipped as lib/arm64/libazaharroom.so). No internet and no server elsewhere needed:
 * both Thors join this room in Azahar (the host at 127.0.0.1, the guest at this Thor's address).
 *
 * It listens on every network the Thor is in, so the same room works in the home WLAN, in a hotspot,
 * in ThorBro's Wi-Fi Direct net and — when the Thor is in NetBird — for a friend in NetBird from anywhere.
 */
object Room {
    @Volatile private var proc: Process? = null
    @Volatile var ready = false
        private set
    @Volatile var members: List<String> = emptyList()
        private set
    @Volatile var error: String? = null
        private set
    @Volatile private var game: String = ""
    private val restarts = java.util.concurrent.CopyOnWriteArrayList<Long>()

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libazaharroom.so")

    fun available(ctx: Context) = binary(ctx).canExecute()

    fun running() = proc?.isAlive == true

    /** starts the room (or keeps the running one); returns null when it is up, else the reason */
    @Synchronized
    fun start(ctx: Context, gameName: String): String? {
        if (running() && ready) { game = gameName; return null }
        stop()
        Relay.stop() // this Thor hosts now: 127.0.0.1 is its own room again
        val bin = binary(ctx)
        if (!bin.canExecute()) return "Raumserver fehlt in dieser ThorBro-Version"
        error = null; ready = false; members = emptyList(); game = gameName
        val p = runCatching {
            ProcessBuilder(bin.path, "${Azahar.PORT}", "Thor", "8").redirectErrorStream(true).start()
        }.getOrElse { return "Raumserver startet nicht: ${it.message}" }
        proc = p
        thread(name = "azahar-room", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("ready") -> { ready = true; Logs.i("room", "3DS-Raum läuft (Port ${Azahar.PORT})") }
                        line.startsWith("members ") -> {
                            members = line.removePrefix("members ").substringAfter(' ', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            Logs.i("room", "Im Raum: ${members.joinToString().ifEmpty { "niemand" }}")
                        }
                        line.startsWith("error") -> { error = line.removePrefix("error ").trim(); Logs.i("room", line) }
                        line.startsWith("[info]") -> Logs.i("room", line.removePrefix("[info] "))
                        line.isNotBlank() -> Logs.i("room", line.take(200))
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (proc === p) {
                ready = false; members = emptyList()
                // 137 = killed by Android, 139 = crashed
                error = "Raumserver beendet (Code $code)"
                Logs.i("room", "3DS-Raum unerwartet beendet (Code $code)")
                proc = null
                val now = System.currentTimeMillis()
                restarts.removeAll { now - it > 120_000 }
                if (Netplay.serverOn && !Relay.active() && restarts.size < 5) {
                    restarts += now
                    Thread.sleep(1000)
                    start(ctx.applicationContext, game)?.let { Logs.i("room", "Neustart ging nicht: $it") } ?: Logs.i("room", "3DS-Raum neu gestartet")
                }
            }
        }
        // wait for "ready" (or the port being taken, e.g. by a room made in Azahar's own menu)
        repeat(30) {
            if (ready) return null
            if (!p.isAlive) return error ?: "Raumserver beendet (Port ${Azahar.PORT} belegt?)"
            Thread.sleep(100)
        }
        return if (ready) null else "Raumserver antwortet nicht"
    }

    @Synchronized
    fun stop() {
        val p = proc ?: return
        proc = null
        ready = false; members = emptyList()
        runCatching { p.outputStream.close() } // the room ends when its stdin closes
        if (runCatching { p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false).not()) p.destroy()
        Logs.i("room", "3DS-Raum geschlossen")
    }

    /** every address another device can use to reach the room, labeled by the network it belongs to */
    fun addresses(): JSONArray {
        val out = JSONArray()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { a ->
                    val ip = a.hostAddress ?: return@forEach
                    val kind = when {
                        nif.name.startsWith("p2p") || ip.startsWith("192.168.49.") -> "Wi-Fi Direct"
                        nif.name.startsWith("wt") || ip.startsWith("100.116.") -> "NetBird"
                        nif.name.startsWith("tailscale") || nif.name.startsWith("tun") && ip.startsWith("100.") -> "VPN"
                        nif.name.startsWith("wlan") || nif.name.startsWith("swlan") || nif.name.startsWith("ap") -> "WLAN"
                        nif.name.startsWith("rmnet") || nif.name.startsWith("ccmni") -> null // mobile data: not reachable
                        else -> "Netz"
                    } ?: return@forEach
                    out.put(JSONObject().put("kind", kind).put("ip", ip))
                }
            }
        }
        return out
    }

    fun status(): JSONObject = JSONObject()
        .put("running", running() && ready)
        .put("port", Azahar.PORT)
        .put("game", game)
        .put("members", JSONArray(members))
        .put("error", error)
        .put("addresses", addresses())
}
