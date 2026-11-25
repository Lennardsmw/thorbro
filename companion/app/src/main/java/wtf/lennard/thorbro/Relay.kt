package wtf.lennard.thorbro

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The emulators always join a room on 127.0.0.1 — typed once, they remember it. ThorBro decides what is behind it:
 *  - no party, or leading one: the own room ([Room] for 3DS, [EdenRoom] for Switch),
 *  - member of a party: the leader's room, through the encrypted [Tunnel] (which itself picks WLAN, NetBird or the
 *    internet, whatever answers first),
 *  - a joined lobby (Wi-Fi Direct / same WLAN, the old way): that Thor's room, straight to its address.
 * One upstream per emulator socket, so the room sees one steady peer per player.
 */
class UdpRelay(val port: Int, val ownRoom: Int, val what: String) {
    /** one emulator socket's way to the room */
    private interface Up { var last: Long; fun send(b: ByteArray, off: Int, len: Int); fun close() }

    @Volatile private var sock: DatagramSocket? = null
    @Volatile var target: InetSocketAddress? = null
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile var tunnel: Tunnel.Guest? = null
        private set
    @Volatile private var tunnelPort = 0
    @Volatile private var lastTraffic = 0L
    private val ups = ConcurrentHashMap<SocketAddress, Up>()

    fun active() = sock?.isClosed == false

    /** the emulator sent and got packets in the last few seconds */
    fun connected() = active() && System.currentTimeMillis() - lastTraffic < 8000 && ups.isNotEmpty()

    /** forwards 127.0.0.1:[port] straight to [host]:[roomPort]; null when it runs, else the reason */
    @Synchronized
    fun direct(host: String, name: String = host, roomPort: Int = ownRoom): String? {
        val to = runCatching { InetSocketAddress(InetAddress.getByName(host), roomPort) }.getOrElse { return "Adresse $host geht nicht" }
        if (active() && target == to && tunnel == null) return null
        return listen(name, to, null) { emu, s ->
            val u = DatagramSocket()
            val up = object : Up {
                override var last = 0L
                override fun send(b: ByteArray, off: Int, len: Int) { runCatching { u.send(DatagramPacket(b, off, len, to)) } }
                override fun close() = u.close()
            }
            thread(name = "relay-out", isDaemon = true) { // answers from the room go back to the socket they belong to
                val buf = ByteArray(65535)
                while (!u.isClosed && !s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { u.receive(p) }.onFailure { return@thread }
                    up.last = System.currentTimeMillis(); lastTraffic = up.last
                    runCatching { s.send(DatagramPacket(p.data, p.offset, p.length, emu)) }
                }
            }
            up
        }
    }

    /** forwards 127.0.0.1:[port] through the party tunnel to the leader's room at [remotePort] (the tunnel stays the caller's) */
    @Synchronized
    fun tunnel(t: Tunnel.Guest, name: String, remotePort: Int = ownRoom): String? {
        if (active() && tunnel === t && tunnelPort == remotePort) return null
        return listen(name, null, t) { emu, s ->
            lateinit var up: Up
            val session = t.open(remotePort) { data ->
                up.last = System.currentTimeMillis(); lastTraffic = up.last
                runCatching { s.send(DatagramPacket(data, data.size, emu)) }
            }
            up = object : Up {
                override var last = 0L
                override fun send(b: ByteArray, off: Int, len: Int) = session.send(b, off, len)
                override fun close() = session.close()
            }
            up
        }.also { if (it == null) tunnelPort = remotePort }
    }

    private fun listen(name: String, to: InetSocketAddress?, t: Tunnel.Guest?, newUp: (SocketAddress, DatagramSocket) -> Up): String? {
        stop()
        val s = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)) }
        }.getOrElse { return "Port $port belegt (Raum im Emulator-Menü erstellt?)" }
        sock = s; target = to; tunnel = t; hostName = name; lastTraffic = 0
        Logs.i("relay", "$what: 127.0.0.1:$port → $name (${to?.address?.hostAddress ?: "Party-Tunnel"})")
        thread(name = "relay-in", isDaemon = true) {
            val buf = ByteArray(65535)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                val from = p.socketAddress
                val up = ups[from] ?: newUp(from, s).also { ups[from] = it }
                up.last = System.currentTimeMillis(); lastTraffic = up.last
                up.send(p.data, p.offset, p.length)
            }
        }
        thread(name = "relay-reap", isDaemon = true) {
            while (!s.isClosed) {
                Thread.sleep(15_000)
                val old = System.currentTimeMillis() - 60_000
                ups.entries.filter { it.value.last < old }.forEach { (k, u) -> ups.remove(k); u.close() }
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        val s = sock ?: return
        sock = null; target = null; tunnel = null; tunnelPort = 0
        s.close()
        ups.values.forEach { it.close() }; ups.clear()
    }

    fun status(): JSONObject = JSONObject().put("active", active()).put("connected", connected())
        .put("host", hostName).put("address", target?.address?.hostAddress)
        .put("online", tunnel != null).put("tunnel", tunnel?.connected() ?: false)
}

object Relay {
    val az = UdpRelay(Azahar.PORT, Azahar.ROOM_PORT, "3DS")
    val eden = UdpRelay(EdenRoom.PORT, EdenRoom.ROOM_PORT, "Switch")

    fun active() = az.active()
    fun connected() = az.connected()

    /** 3DS straight to another Thor's room (a joined lobby) */
    fun start(host: String, name: String = host, roomPort: Int = Azahar.ROOM_PORT): String? = az.direct(host, name, roomPort)

    /**
     * Points every 127.0.0.1 room at the party leader's (party member, through [PartyNet]) or the own room. A 3DS lobby
     * joined the old way keeps its target.
     */
    @Synchronized
    fun route(ctx: android.content.Context) {
        val g = PartyNet.guest
        val leader = PartyNet.leaderName
        if (Netplay.joinedRoom == null) {
            (if (g != null) az.tunnel(g, leader, Azahar.ROOM_PORT) else az.direct("127.0.0.1", "eigener Raum"))
                ?.let { Logs.i("relay", "3DS-Weiterleitung: $it") }
        }
        if (EdenRoom.available(ctx) || g != null) {
            (if (g != null) eden.tunnel(g, leader, EdenRoom.ROOM_PORT) else eden.direct("127.0.0.1", "eigener Raum"))
                ?.let { Logs.i("relay", "Switch-Weiterleitung: $it") }
        }
    }

    fun status(): JSONObject = az.status().put("eden", eden.status())
}
