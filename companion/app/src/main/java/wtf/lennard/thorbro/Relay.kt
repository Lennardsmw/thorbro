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
 * Azahar always joins 127.0.0.1:24872 — typed once, Azahar remembers it. ThorBro decides what is behind that address:
 *  - this Thor hosts: its own room ([Room]) listens there,
 *  - this Thor plays at a friend's: this relay listens there and passes Azahar's UDP traffic (ENet) on to the friend's
 *    room, over whatever link reaches it (WLAN, Wi-Fi Direct, NetBird).
 * One upstream socket per Azahar socket, so the room sees one steady peer per player.
 */
object Relay {
    private class Up(val sock: DatagramSocket, @Volatile var last: Long)

    @Volatile private var sock: DatagramSocket? = null
    @Volatile var target: InetSocketAddress? = null
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile private var lastTraffic = 0L
    private val ups = ConcurrentHashMap<SocketAddress, Up>()

    fun active() = sock?.isClosed == false

    /** Azahar sent and got packets in the last few seconds */
    fun connected() = active() && System.currentTimeMillis() - lastTraffic < 8000 && ups.isNotEmpty()

    /** forwards 127.0.0.1:[port] to [host]; null when it runs, else the reason */
    @Synchronized
    fun start(host: String, name: String = host, port: Int = Azahar.PORT): String? {
        val to = runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrElse { return "Adresse $host geht nicht" }
        if (active() && target == to) return null
        stop()
        Room.stop() // the own room holds the port on every interface
        val s = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
        }.getOrElse { return "Port $port belegt (Raum im Azahar-Menü erstellt?)" }
        sock = s; target = to; hostName = name; lastTraffic = 0
        Logs.i("relay", "3DS: 127.0.0.1 → $name ($host)")
        thread(name = "relay-in", isDaemon = true) {
            val buf = ByteArray(65535)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                val from = p.socketAddress
                val up = ups[from] ?: Up(DatagramSocket(), 0).also { u -> ups[from] = u; pumpBack(s, u, from) }
                up.last = System.currentTimeMillis(); lastTraffic = up.last
                runCatching { up.sock.send(DatagramPacket(p.data, p.offset, p.length, to)) }
            }
        }
        thread(name = "relay-reap", isDaemon = true) {
            while (!s.isClosed) {
                Thread.sleep(15_000)
                val old = System.currentTimeMillis() - 60_000
                ups.entries.filter { it.value.last < old }.forEach { (k, u) -> ups.remove(k); u.sock.close() }
            }
        }
        return null
    }

    /** answers from the room go back to the Azahar socket they belong to */
    private fun pumpBack(s: DatagramSocket, u: Up, azahar: SocketAddress) = thread(name = "relay-out", isDaemon = true) {
        val buf = ByteArray(65535)
        while (!u.sock.isClosed && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            runCatching { u.sock.receive(p) }.onFailure { return@thread }
            u.last = System.currentTimeMillis(); lastTraffic = u.last
            runCatching { s.send(DatagramPacket(p.data, p.offset, p.length, azahar)) }
        }
    }

    @Synchronized
    fun stop() {
        val s = sock ?: return
        sock = null; target = null
        s.close()
        ups.values.forEach { it.sock.close() }; ups.clear()
        Logs.i("relay", "3DS-Weiterleitung aus")
    }

    fun status(): JSONObject = JSONObject().put("active", active()).put("connected", connected())
        .put("host", hostName).put("address", target?.address?.hostAddress)
}
