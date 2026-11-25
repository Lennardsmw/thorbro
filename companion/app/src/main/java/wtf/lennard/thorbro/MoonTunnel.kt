package wtf.lennard.thorbro

import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Moonlight's UDP over the USB cable (spec "Mac → Thor streaming", phase 2). `adb reverse` carries only TCP,
 * so Moonlight (host 127.0.0.1) sends its UDP to our sockets on 127.0.0.1:47998 (video), 47999 (control/ENet)
 * and 48000 (audio). Every datagram goes as `[u16 port][u16 len][payload]` (big endian) into one TCP stream to
 * 127.0.0.1:48100, which RomBro brings to the Mac with `adb reverse tcp:48100 tcp:48100`. RomBro unwraps, sends
 * from one UDP socket per port to Sunshine and wraps the replies the same way; we hand them back to the address
 * Moonlight last sent from on that port. RomBro starts it (POST /api/stream/moonlight/tunnel?on=1) while USB is
 * the link and stops it when the cable goes; the TCP side reconnects every second until stopped.
 */
object MoonTunnel {
    val PORTS = intArrayOf(47998, 47999, 48000)
    const val TCP_PORT = 48100
    private val LOOP: InetAddress = InetAddress.getByName("127.0.0.1")

    @Volatile private var running = false
    @Volatile private var tcp: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var error: String? = null
    private val udp = java.util.concurrent.ConcurrentHashMap<Int, DatagramSocket>()
    private val client = HashMap<Int, SocketAddress>()
    @Volatile private var up = 0L
    @Volatile private var down = 0L

    fun status(): JSONObject = JSONObject().put("running", running).put("connected", out != null)
        .put("up", up).put("down", down).put("tcpPort", TCP_PORT).put("udpPorts", org.json.JSONArray(PORTS.toList()))
        .apply { error?.let { put("error", it) } }

    @Synchronized fun start(): JSONObject {
        if (running) return status()
        error = null; up = 0; down = 0
        try {
            PORTS.forEach { p -> udp[p] = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(LOOP, p)) } }
        } catch (e: Exception) {
            udp.values.forEach { it.close() }; udp.clear()
            throw IllegalStateException("UDP-Port belegt: ${e.message}")
        }
        running = true
        udp.forEach { (p, s) -> daemon("moon-udp-$p") { fromMoonlight(p, s) } }
        daemon("moon-tcp") { link() }
        Logs.i("stream", "Moonlight-Tunnel über USB an (UDP ${PORTS.joinToString()} ↔ TCP $TCP_PORT)")
        return status()
    }

    @Synchronized fun stop(): JSONObject {
        if (!running) return status()
        running = false; error = null
        runCatching { tcp?.close() }
        udp.values.forEach { it.close() }; udp.clear()
        synchronized(client) { client.clear() }
        Logs.i("stream", "Moonlight-Tunnel aus ($up ↑ / $down ↓ Pakete)")
        return status()
    }

    /** Moonlight → Mac: one frame per datagram */
    private fun fromMoonlight(port: Int, s: DatagramSocket) {
        val buf = ByteArray(65_535)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.setData(buf, 0, buf.size)
                s.receive(pkt)
                synchronized(client) { client[port] = pkt.socketAddress }
                val o = out ?: continue // no Mac yet: drop, Moonlight retries
                val n = pkt.length
                val frame = ByteArray(4 + n)
                frame[0] = (port shr 8).toByte(); frame[1] = port.toByte()
                frame[2] = (n shr 8).toByte(); frame[3] = n.toByte()
                System.arraycopy(buf, 0, frame, 4, n)
                try { synchronized(o) { o.write(frame); o.flush() }; up++ } catch (_: Exception) { runCatching { tcp?.close() } }
            } catch (_: Exception) { if (!running) return }
        }
    }

    /** keeps the TCP stream to the Mac up and hands its frames to Moonlight */
    private fun link() {
        while (running) {
            val sock = Socket()
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(LOOP, TCP_PORT), 2000)
                tcp = sock; out = sock.getOutputStream(); error = null
                Logs.i("stream", "Moonlight-Tunnel: Mac verbunden")
                val inp = DataInputStream(sock.getInputStream().buffered(256 * 1024))
                val buf = ByteArray(65_535)
                while (running) {
                    val port = inp.readUnsignedShort()
                    val n = inp.readUnsignedShort()
                    inp.readFully(buf, 0, n)
                    val s = udp[port] ?: continue
                    val to = synchronized(client) { client[port] } ?: continue
                    runCatching { s.send(DatagramPacket(buf, n, to)); down++ }
                }
            } catch (e: Exception) {
                if (running && out != null) Logs.i("stream", "Moonlight-Tunnel: Mac getrennt (${e.message})")
                error = if (running) "Mac nicht erreichbar (adb reverse tcp:$TCP_PORT?)" else null
            } finally {
                out = null; tcp = null
                runCatching { sock.close() }
            }
            if (running) Thread.sleep(1000)
        }
    }

    private fun daemon(name: String, f: () -> Unit) = Thread(f, name).apply { isDaemon = true }.start()
}
