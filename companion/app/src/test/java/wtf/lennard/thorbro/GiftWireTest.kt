package wtf.lennard.thorbro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class GiftWireTest {
    private fun tmp(size: Int): File = File.createTempFile("gift", ".bin").apply {
        deleteOnExit(); writeBytes(ByteArray(size).also { Random(size.toLong()).nextBytes(it) })
    }

    /** a game file through a real tunnel (host + guest on loopback), with an odd size */
    @Test fun throughTheTunnel() {
        val src = tmp(3 * 1024 * 1024 + 777)
        val server = GiftWire.Server(mapOf(1.toByte() to src))
        val secret = Tunnel.newSecret()
        val host = Tunnel.Host(secret, setOf(server.port))
        val guest = Tunnel.Guest(secret, listOf("127.0.0.1:${host.sock.localPort}"), stun = false)
        assertTrue(guest.await(3000, server.port))
        val inbox = LinkedBlockingQueue<ByteArray>()
        val s = guest.open(server.port) { inbox.add(it) }
        val out = File.createTempFile("gift", ".out").apply { deleteOnExit() }
        val end = GiftWire.fetch(1, out, 0, src.length(), inbox, { b -> s.send(b, 0, b.size) })
        assertEquals(src.length(), end)
        assertArrayEquals(src.readBytes(), out.readBytes())
        guest.close(); host.close(); server.close()
    }

    /** a lossy link (every 7th datagram dropped) and a resume from the middle */
    @Test fun lossAndResume() {
        val src = tmp(900_000)
        val server = GiftWire.Server(mapOf(2.toByte() to src))
        // a "link" that drops packets on the way back
        val link = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val inbox = LinkedBlockingQueue<ByteArray>()
        var n = 0
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            while (!link.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { link.receive(p) }.isFailure) break
                if (++n % 7 != 0) inbox.add(buf.copyOf(p.length))
            }
        }
        val to = InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)
        val send: (ByteArray) -> Unit = { b -> link.send(DatagramPacket(b, b.size, to)) }
        val out = File.createTempFile("gift", ".out").apply { deleteOnExit() }
        // first half by another way (say LAN), then the tunnel carries on from there
        val half = 450_000L
        out.writeBytes(src.readBytes().copyOf(half.toInt()))
        val end = GiftWire.fetch(2, out, half, src.length(), inbox, send)
        assertEquals(src.length(), end)
        assertArrayEquals(src.readBytes(), out.readBytes())
        link.close(); server.close()
    }
}
