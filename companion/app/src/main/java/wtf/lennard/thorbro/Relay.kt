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
 *    room — straight to its address (WLAN, Wi-Fi Direct, NetBird), or through the encrypted [Tunnel] over the internet.
 * One upstream per Azahar socket, so the room sees one steady peer per player.
 */
object Relay {
    /** one Azahar socket's way to the room */
    private interface Up { var last: Long; fun send(b: ByteArray, off: Int, len: Int); fun close() }

    @Volatile private var sock: DatagramSocket? = null
    @Volatile var target: InetSocketAddress? = null
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile var tunnel: Tunnel.Guest? = null
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
        if (active() && target == to && tunnel == null) return null
        return listen(name, port, to, null) { azahar, s ->
            val u = DatagramSocket()
            val up = object : Up {
                override var last = 0L
                override fun send(b: ByteArray, off: Int, len: Int) { runCatching { u.send(DatagramPacket(b, off, len, to)) } }
                override fun close() = u.close()
            }
            thread(name = "relay-out", isDaemon = true) { // answers from the room go back to the Azahar socket they belong to
                val buf = ByteArray(65535)
                while (!u.isClosed && !s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { u.receive(p) }.onFailure { return@thread }
                    up.last = System.currentTimeMillis(); lastTraffic = up.last
                    runCatching { s.send(DatagramPacket(p.data, p.offset, p.length, azahar)) }
                }
            }
            up
        }
    }

    /** forwards 127.0.0.1:[port] through an online tunnel to a friend's room (the tunnel belongs to the relay from now on) */
    @Synchronized
    fun startTunnel(t: Tunnel.Guest, name: String, port: Int = Azahar.PORT): String? =
        listen(name, port, null, t) { azahar, s ->
            lateinit var up: Up
            val session = t.open { data ->
                up.last = System.currentTimeMillis(); lastTraffic = up.last
                runCatching { s.send(DatagramPacket(data, data.size, azahar)) }
            }
            up = object : Up {
                override var last = 0L
                override fun send(b: ByteArray, off: Int, len: Int) = session.send(b, off, len)
                override fun close() = session.close()
            }
            up
        }

    private fun listen(name: String, port: Int, to: InetSocketAddress?, t: Tunnel.Guest?, newUp: (SocketAddress, DatagramSocket) -> Up): String? {
        stop()
        Room.stop() // the own room holds the port on every interface
        val s = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)) }
        }.getOrElse { t?.close(); return "Port $port belegt (Raum im Azahar-Menü erstellt?)" }
        sock = s; target = to; tunnel = t; hostName = name; lastTraffic = 0
        Logs.i("relay", "3DS: 127.0.0.1 → $name (${to?.address?.hostAddress ?: "online, verschlüsselt"})")
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
        sock = null; target = null
        s.close()
        ups.values.forEach { it.close() }; ups.clear()
        tunnel?.close(); tunnel = null
        Logs.i("relay", "3DS-Weiterleitung aus")
    }

    fun status(): JSONObject = JSONObject().put("active", active()).put("connected", connected())
        .put("host", hostName).put("address", target?.address?.hostAddress)
        .put("online", tunnel != null).put("tunnel", tunnel?.connected() ?: false)
}
