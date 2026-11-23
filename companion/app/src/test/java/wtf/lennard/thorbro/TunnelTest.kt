package wtf.lennard.thorbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TunnelTest {
    /** stands in for Azahar's room: echoes "<sender port>:<payload>" so we can tell guests apart */
    private fun fakeRoom(): DatagramSocket {
        val s = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                val reply = "${p.port}:${String(buf, 0, p.length)}".toByteArray()
                s.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
        return s
    }

    @Test fun sealOpenAndTamper() {
        val k = Tunnel.Keys(Tunnel.newSecret())
        val p = Tunnel.seal(k.toHost, 3, 42, 7, "hallo".toByteArray())
        val m = Tunnel.open(k.toHost, p, 0, p.size)!!
        assertEquals("hallo", String(m.data)); assertEquals(42L, m.session); assertEquals(7L, m.counter)
        assertNull("wrong direction key", Tunnel.open(k.toGuest, p, 0, p.size))
        assertNull("other secret", Tunnel.open(Tunnel.Keys(Tunnel.newSecret()).toHost, p, 0, p.size))
        val bad = p.copyOf(); bad[bad.size - 20] = (bad[bad.size - 20].toInt() xor 1).toByte()
        assertNull("tampered payload", Tunnel.open(k.toHost, bad, 0, bad.size))
        val badHead = p.copyOf(); badHead[5] = 9
        assertNull("tampered header", Tunnel.open(k.toHost, badHead, 0, badHead.size))
    }

    @Test fun replayWindow() {
        val r = Tunnel.Replay()
        assertTrue(r.fresh(0)); assertTrue(!r.fresh(0)); assertTrue(r.fresh(5)); assertTrue(r.fresh(3)); assertTrue(!r.fresh(3))
        assertTrue(r.fresh(100)); assertTrue(!r.fresh(10))
    }

    @Test fun twoGuestsThroughOneHost() {
        val room = fakeRoom()
        val secret = Tunnel.newSecret()
        val host = Tunnel.Host(secret, room.localPort)
        val cand = listOf("127.0.0.1:${host.sock.localPort}", "203.0.113.9:${host.sock.localPort}") // second one is dead
        val g1 = Tunnel.Guest(secret, cand); val g2 = Tunnel.Guest(secret, cand)
        assertTrue(g1.await(3000)); assertTrue(g2.await(3000))
        val q1 = LinkedBlockingQueue<String>(); val q2 = LinkedBlockingQueue<String>()
        val s1 = g1.open { q1.add(String(it)) }; val s2 = g2.open { q2.add(String(it)) }
        Thread.sleep(500)
        s1.send("a".toByteArray(), 0, 1); s2.send("b".toByteArray(), 0, 1)
        val r1 = q1.poll(3, TimeUnit.SECONDS); val r2 = q2.poll(3, TimeUnit.SECONDS)
        assertNotNull(r1); assertNotNull(r2)
        assertTrue(r1!!.endsWith(":a")); assertTrue(r2!!.endsWith(":b"))
        assertTrue("room sees two players", r1.substringBefore(':') != r2.substringBefore(':'))
        // a stranger with another secret gets nothing through
        val evil = Tunnel.Guest(Tunnel.newSecret(), cand)
        assertTrue(!evil.await(1500))
        // raw junk does not crash the host
        DatagramSocket().use { it.send(DatagramPacket(ByteArray(100), 100, InetSocketAddress("127.0.0.1", host.sock.localPort))) }
        s1.send("c".toByteArray(), 0, 1)
        assertTrue(q1.poll(3, TimeUnit.SECONDS)!!.endsWith(":c"))
        g1.close(); g2.close(); evil.close(); host.close(); room.close()
    }

    @Test fun roomFull() {
        val room = fakeRoom(); val secret = Tunnel.newSecret()
        val host = Tunnel.Host(secret, room.localPort, maxGuests = 1)
        val cand = listOf("127.0.0.1:${host.sock.localPort}")
        val g1 = Tunnel.Guest(secret, cand); g1.open { }
        Thread.sleep(500)
        val g2 = Tunnel.Guest(secret, cand)
        assertTrue(!g2.await(1500)); assertTrue(g2.full)
        g1.close(); g2.close(); host.close(); room.close()
    }

    @Test fun stunAndCandidates() {
        DatagramSocket().use { s ->
            val c = Tunnel.candidates(s)
            println("candidates: $c")
            assertTrue(c.isNotEmpty())
        }
    }
}
