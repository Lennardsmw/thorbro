package wtf.lennard.thorbro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Manual test, not part of the normal run: THORBRO_INVITE=<link> ./gradlew :app:testDebugUnitTest --tests '*OnlineGuestHarness*'
 * Plays the guest Thor on the Mac: 127.0.0.1:24872 on the Mac leads through the tunnel into the host's room, for
 * THORBRO_HARNESS_SECONDS (default 60). Join it with the ENet test client meanwhile.
 */
class OnlineGuestHarness {
    @Test fun guest() {
        val link = System.getenv("THORBRO_INVITE")
        assumeTrue(link != null)
        Tunnel.log = { println("tunnel: $it") }
        val inv = Online.parse(link!!)!!
        println("host ${inv.name}, candidates ${inv.candidates}")
        val g = Tunnel.Guest(inv.secret, inv.candidates)
        println("own candidates ${g.candidates}")
        var done = false
        thread(isDaemon = true) {
            val hello = JSONObject().put("t", "guest").put("n", "Mac-Test").put("c", JSONArray(g.candidates))
            var i = 0
            while (!done && i++ < 8) { println("ntfy: ${Online.publish(inv.secret, hello).getOrNull()}"); Thread.sleep(3000) }
        }
        Online.subscribe(inv.secret, { !done }) { m ->
            println("signal: ${m.optString("t")} ${m.optJSONArray("c")}")
            if (m.optString("t") == "host") m.optJSONArray("c")?.let { c -> g.addTargets((0 until c.length()).map { c.getString(it) }) }
        }
        val ok = g.await(30_000); done = true
        println("connected: $ok via ${g.host}")
        if (!ok) return
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 24872)) }
        val sessions = ConcurrentHashMap<SocketAddress, Tunnel.Guest.Session>()
        thread(isDaemon = true) {
            val buf = ByteArray(65535)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                val from = p.socketAddress
                val ses = sessions.getOrPut(from) { g.open { d -> s.send(DatagramPacket(d, d.size, from)) } }
                ses.send(p.data, p.offset, p.length)
            }
        }
        println("READY relay on 127.0.0.1:24872")
        Thread.sleep((System.getenv("THORBRO_HARNESS_SECONDS")?.toLong() ?: 60) * 1000)
        s.close(); g.close()
    }
}
