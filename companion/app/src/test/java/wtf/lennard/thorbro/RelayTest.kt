package wtf.lennard.thorbro

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    @Test fun bindsLoopback() {
        val r = UdpRelay(34872, 34873, "test")
        val err = r.direct("127.0.0.1", "eigener Raum")
        println("err=$err")
        assertNull(err); assertTrue(r.active())
        assertNull(r.direct("127.0.0.1", "eigener Raum"))
        r.stop()
    }
}
