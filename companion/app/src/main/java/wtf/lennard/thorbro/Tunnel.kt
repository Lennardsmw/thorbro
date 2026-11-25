package wtf.lennard.thorbro

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Party play over the internet without a game server: a direct UDP tunnel from every party member to the leader
 * (no VPN app, no relay in between). ThorBro and RomBro use the same file (protocol: companion/FRIENDS.md, "Party-Netz").
 *
 * The leader keeps its rooms (3DS [Room], Switch [EdenRoom]) as they are; every member socket gets a session in the
 * leader's tunnel and names the room port it wants in its HELLO (only the leader's [Host.ports] are allowed). The
 * leader hands each session to that room from its own local socket — the room sees one steady player per member, so
 * up to the room's limit can play, online and WLAN players mixed. Members only talk to the leader (star).
 *
 * Getting through home routers: both sides learn their public address from public STUN servers, swap their
 * candidate addresses (LAN, public, IPv6) through the signalling, then send to each other at the same time
 * (hole punching) until a packet comes back.
 *
 * Every packet is encrypted and authenticated (AES-GCM) with keys derived from the room secret, and replays are
 * dropped. Anything else that reaches the port is discarded before it gets near Azahar's room code, so to a
 * stranger the port looks closed. The ThorBro web server is never part of this.
 *
 * Pure JVM on purpose (no Android classes): the same file runs in a test harness on a Mac.
 */
object Tunnel {
    const val VERSION: Byte = 2
    /** the leader's tunnel port (UDP); the 3DS room has 24873, Azahar's relay 24872 */
    const val PORT = 24874
    private const val HELLO: Byte = 1    // guest → host: open/keep this session
    private const val WELCOME: Byte = 2  // host → guest: session accepted
    private const val DATA: Byte = 3
    private const val PING: Byte = 4     // keeps NAT mappings open, either way
    private const val PUNCH: Byte = 5    // host → guest candidates: opens the host's NAT for the guest
    private const val BYE: Byte = 6
    private const val FULL: Byte = 7     // host → guest: room is full
    private const val HEADER = 32        // "TB" ver type session(8) counter(8) nonce(12)
    private const val TAG = 16

    var log: (String) -> Unit = { println("tunnel: $it") }

    val STUN = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478", "stun1.l.google.com:19302")

    private val rnd = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    // ---------- crypto ----------

    /** one key per direction, so both sides can count from 0 without ever reusing a nonce */
    class Keys(private val secret: ByteArray) {
        private fun derive(label: String) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256")); doFinal("thorbro tunnel v2 $label".toByteArray())
        }
        val toHost = SecretKeySpec(derive("guest->host").copyOf(32), "AES")
        val toGuest = SecretKeySpec(derive("host->guest").copyOf(32), "AES")
    }

    /**
     * Packet: "TB" ver type session(8) counter(8) nonce(12) | ciphertext | tag(16). The nonce is random per packet,
     * so no packet type or session can ever repeat one; the counter is only for dropping replays.
     */
    fun seal(key: SecretKeySpec, type: Byte, session: Long, counter: Long, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val head = ByteBuffer.allocate(HEADER).put('T'.code.toByte()).put('B'.code.toByte()).put(VERSION).put(type)
            .putLong(session).putLong(counter).put(iv).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, iv))
        c.updateAAD(head)
        val out = ByteArray(HEADER + len + TAG)
        System.arraycopy(head, 0, out, 0, HEADER)
        c.doFinal(data, off, len, out, HEADER)
        return out
    }

    class Opened(val type: Byte, val session: Long, val counter: Long, val data: ByteArray)

    /** null for anything that is not ours or was tampered with */
    fun open(key: SecretKeySpec, p: ByteArray, off: Int, len: Int): Opened? {
        if (len < HEADER + TAG || p[off] != 'T'.code.toByte() || p[off + 1] != 'B'.code.toByte() || p[off + 2] != VERSION) return null
        val b = ByteBuffer.wrap(p, off, HEADER)
        b.position(off + 3)
        val type = b.get(); val session = b.long; val counter = b.long
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, p.copyOfRange(off + 20, off + 32)))
            c.updateAAD(p, off, HEADER)
            Opened(type, session, counter, c.doFinal(p, off + HEADER, len - HEADER))
        }.getOrNull()
    }

    /** drops repeated and very old counters (64-packet window) */
    class Replay {
        private var top = -1L
        private var mask = 0L
        @Synchronized fun fresh(n: Long): Boolean {
            if (n > top) { val shift = n - top; mask = if (shift >= 64) 1L else (mask shl shift.toInt()) or 1L; top = n; return true }
            val back = top - n
            if (back >= 64) return false
            val bit = 1L shl back.toInt()
            if (mask and bit != 0L) return false
            mask = mask or bit; return true
        }
    }

    // ---------- addresses ----------

    /** RFC 5389 binding request on [sock]; the public address the router gave this socket, or null */
    fun stun(sock: DatagramSocket, servers: List<String> = STUN, timeoutMs: Int = 1200): InetSocketAddress? {
        val old = sock.soTimeout
        try {
            for (server in servers) {
                val addr = runCatching {
                    InetSocketAddress(InetAddress.getAllByName(server.substringBeforeLast(':')).first { it is Inet4Address },
                        server.substringAfterLast(':').toInt())
                }.getOrNull() ?: continue
                val id = ByteArray(12).also { rnd.nextBytes(it) }
                val req = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(0x2112A442).put(id).array()
                sock.soTimeout = timeoutMs
                repeat(2) {
                    runCatching { sock.send(DatagramPacket(req, req.size, addr)) }
                    val buf = ByteArray(512)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        if (runCatching { sock.receive(p) }.isFailure) break
                        parseStun(buf, p.length, id)?.let { return it }
                    }
                }
            }
            return null
        } finally { runCatching { sock.soTimeout = old } }
    }

    /** sends one binding request from [sock] without waiting; the receive loop picks the answer up. Returns its id. */
    fun stunAsync(sock: DatagramSocket, servers: List<String> = STUN): ByteArray? {
        for (server in servers) {
            val addr = runCatching {
                InetSocketAddress(InetAddress.getAllByName(server.substringBeforeLast(':')).first { it is Inet4Address }, server.substringAfterLast(':').toInt())
            }.getOrNull() ?: continue
            val id = ByteArray(12).also { rnd.nextBytes(it) }
            val req = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(0x2112A442).put(id).array()
            if (runCatching { sock.send(DatagramPacket(req, req.size, addr)) }.isSuccess) return id
        }
        return null
    }

    internal fun isStun(b: ByteArray, len: Int) = len >= 20 && b[0].toInt() == 0x01 && b[1].toInt() == 0x01 &&
        b[4].toInt() == 0x21 && b[5].toInt() == 0x12 && (b[6].toInt() and 0xFF) == 0xA4 && b[7].toInt() == 0x42

    internal fun parseStun(b: ByteArray, len: Int, id: ByteArray): InetSocketAddress? {
        if (len < 20) return null
        val bb = ByteBuffer.wrap(b, 0, len)
        if (bb.short.toInt() != 0x0101) return null
        val msgLen = bb.short.toInt() and 0xFFFF
        if (bb.int != 0x2112A442) return null
        for (i in 0 until 12) if (bb.get() != id[i]) return null
        var pos = 20
        while (pos + 4 <= minOf(len, 20 + msgLen)) {
            val type = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            val alen = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val v = pos + 4
            if ((type == 0x0020 || type == 0x0001) && b[v + 1].toInt() == 0x01 && alen >= 8) { // IPv4 (XOR-)MAPPED-ADDRESS
                val xor = type == 0x0020
                val port = (((b[v + 2].toInt() and 0xFF) shl 8) or (b[v + 3].toInt() and 0xFF)) xor (if (xor) 0x2112 else 0)
                val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                val ip = ByteArray(4) { (b[v + 4 + it].toInt() xor (if (xor) cookie[it].toInt() else 0)).toByte() }
                return InetSocketAddress(InetAddress.getByAddress(ip), port)
            }
            pos = v + ((alen + 3) and 3.inv())
        }
        return null
    }

    /** where this socket can be reached: LAN addresses, global IPv6, and the public address from STUN */
    fun candidates(sock: DatagramSocket, port: Int = sock.localPort, withStun: Boolean = true): List<String> {
        val out = LinkedHashSet<String>()
        if (withStun) runCatching { stun(sock)?.let { out += "${it.address.hostAddress}:${it.port}" } }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { nif ->
                nif.inetAddresses.toList().forEach { a ->
                    when {
                        a is Inet4Address && !a.isLinkLocalAddress -> out += "${a.hostAddress}:$port"
                        a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress &&
                            (a.address[0].toInt() and 0xE0) == 0x20 -> out += "[${a.hostAddress!!.substringBefore('%')}]:$port"
                    }
                }
            }
        }
        return out.toList()
    }

    fun parse(c: String): InetSocketAddress? = runCatching {
        val host = if (c.startsWith("[")) c.substring(1, c.indexOf(']')) else c.substringBeforeLast(':')
        InetSocketAddress(InetAddress.getByName(host), c.substringAfterLast(':').toInt())
    }.getOrNull()

    // no SO_REUSEADDR: a second tunnel on the same port would silently share it (and miss packets) instead of failing
    private fun bind(port: Int) = DatagramSocket(null).apply { reuseAddress = false; bind(InetSocketAddress(port)) }

    /** a member's socket and its addresses (with STUN), before the leader's secret is known: sent along with the knock */
    fun socket(): Pair<DatagramSocket, List<String>> { val s = bind(0); return s to candidates(s) }

    // ---------- host ----------

    /**
     * Host side: one UDP port for all guests. Each authenticated session gets its own local socket towards the room
     * at 127.0.0.1:[roomPort]; answers from the room go back to the guest's current address (it may change, e.g.
     * when a phone hotspot re-maps it — any valid packet moves it).
     */
    class Host(secret: ByteArray, val ports: Set<Int>, port: Int = 0, val maxGuests: Int = 15) {
        private val keys = Keys(secret)
        val sock: DatagramSocket = bind(port)
        private val local: List<String> = Tunnel.candidates(sock, withStun = false)
        /**
         * The public address as the router maps this port right now. Home routers re-map an idle UDP port after
         * a while (and need not keep the port number), so it is asked again every 20 s — which also keeps it open.
         */
        @Volatile var public: InetSocketAddress? = stun(sock)
            private set
        @Volatile private var stunId: ByteArray? = null
        /** where guests can reach this host, freshest first */
        val candidates: List<String> get() = (listOfNotNull(public?.let { "${it.address.hostAddress}:${it.port}" }) + local).distinct()

        /** asks STUN again now (the answer arrives in the receive loop within a moment) */
        fun refreshPublic() { stunId = stunAsync(sock) }
        private class Peer(val session: Long, @Volatile var addr: SocketAddress, val local: DatagramSocket, val target: Int) {
            val replay = Replay(); @Volatile var tx = 0L; @Volatile var last = System.currentTimeMillis()
        }
        private val peers = ConcurrentHashMap<Long, Peer>()
        @Volatile private var punchTx = 0L
        val guests get() = peers.size

        fun guestAddresses(): List<String> = peers.values.map { it.addr.toString().removePrefix("/") }

        init {
            thread(name = "tunnel-host", isDaemon = true) {
                val buf = ByteArray(65535)
                while (!sock.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    if (runCatching { sock.receive(p) }.isFailure) { if (sock.isClosed) break else continue }
                    if (isStun(buf, p.length)) {
                        stunId?.let { parseStun(buf, p.length, it) }?.let { if (it != public) log("öffentliche Adresse jetzt ${it.address.hostAddress}:${it.port}"); public = it }
                        continue
                    }
                    val m = open(keys.toHost, buf, p.offset, p.length) ?: continue
                    val peer = peers[m.session]
                    if (peer != null && !peer.replay.fresh(m.counter)) continue
                    when (m.type) {
                        HELLO -> {
                            val pr = peer ?: run {
                                // HELLO names the room: 2 bytes port; only the leader's own rooms
                                val target = if (m.data.size >= 2) ((m.data[0].toInt() and 0xFF) shl 8) or (m.data[1].toInt() and 0xFF) else ports.first()
                                if (target !in ports) return@run null
                                if (peers.size >= maxGuests) { send(p.socketAddress, FULL, m.session, 0, ByteArray(0)); return@run null }
                                Peer(m.session, p.socketAddress, DatagramSocket(), target).also {
                                    it.replay.fresh(m.counter); peers[m.session] = it; pump(it)
                                    log("Gast ${m.session.toString(16)} über ${p.socketAddress} → Port $target")
                                }
                            } ?: continue
                            pr.addr = p.socketAddress; pr.last = System.currentTimeMillis()
                            send(pr, WELCOME, ByteArray(0))
                        }
                        DATA -> peer?.let {
                            it.addr = p.socketAddress; it.last = System.currentTimeMillis()
                            runCatching { it.local.send(DatagramPacket(m.data, m.data.size, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), it.target))) }
                        }
                        PING -> peer?.let { it.addr = p.socketAddress; it.last = System.currentTimeMillis(); send(it, PING, ByteArray(0)) }
                        BYE -> peer?.let { drop(it, "abgemeldet") }
                    }
                }
            }
            thread(name = "tunnel-host-reap", isDaemon = true) {
                var n = 0
                while (!sock.isClosed) {
                    Thread.sleep(10_000)
                    if (n++ % 2 == 0) refreshPublic()
                    val old = System.currentTimeMillis() - 45_000
                    peers.values.filter { it.last < old }.forEach { drop(it, "keine Antwort") }
                }
            }
        }

        private fun pump(pr: Peer) = thread(name = "tunnel-host-room", isDaemon = true) {
            val buf = ByteArray(65535)
            while (!pr.local.isClosed && !sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { pr.local.receive(p) }.isFailure) break
                send(pr, DATA, buf.copyOfRange(p.offset, p.offset + p.length))
            }
        }

        private fun drop(pr: Peer, why: String) {
            if (peers.remove(pr.session) != null) { pr.local.close(); log("Gast ${pr.session.toString(16)} weg ($why)") }
        }

        private fun send(pr: Peer, type: Byte, data: ByteArray) = send(pr.addr, type, pr.session, pr.tx++, data)

        private fun send(to: SocketAddress, type: Byte, session: Long, counter: Long, data: ByteArray) {
            val out = seal(keys.toGuest, type, session, counter, data)
            runCatching { sock.send(DatagramPacket(out, out.size, to)) }
        }

        /**
         * Opens this side's router for a guest's candidates (needed when both are behind NAT). Mobile networks often
         * give every destination its own port (counting up from the one STUN saw), so for public IPv4 candidates the
         * ports just above it are tried too — a few hundred tiny packets, once per knock.
         */
        fun punch(candidates: List<String>, times: Int = 6) = thread(name = "tunnel-punch", isDaemon = true) {
            val exact = candidates.mapNotNull { parse(it) }
            val spray = exact.filter { a -> a.address is Inet4Address && !a.address.isSiteLocalAddress && !a.address.isLoopbackAddress &&
                !(a.address.address[0].toInt() == 100 && (a.address.address[1].toInt() and 0xC0) == 64) }
                .flatMap { a -> (-8..400).map { d -> a.port + d }.filter { it in 1024..65535 && it != a.port }.map { InetSocketAddress(a.address, it) } }
            repeat(times) { round ->
                // a session id of 0 and its own counter range: guests ignore PUNCH, it only has to leave the router
                exact.forEach { send(it, PUNCH, 0, punchTx++, ByteArray(0)) }
                if (round < 3) spray.forEach { send(it, PUNCH, 0, punchTx++, ByteArray(0)) }
                Thread.sleep(300)
            }
        }

        fun close() {
            ArrayList(peers.values).forEach { send(it, BYE, ByteArray(0)); drop(it, "Raum zu") }
            sock.close()
        }
    }

    // ---------- guest ----------

    /**
     * Guest side: tries all host candidates at once and keeps the first that answers. [open] gives one session per
     * local player socket (Azahar's ENet socket), so the host's room sees each as its own member.
     */
    class Guest(val secret: ByteArray, private val hostCandidates: List<String>, port: Int = 0, stun: Boolean = true,
                socket: DatagramSocket? = null, known: List<String>? = null) {
        private val keys = Keys(secret)
        /** a socket made before the secret was known ([Tunnel.socket]) keeps the address the leader already punched to */
        val sock: DatagramSocket = socket ?: bind(port)
        /** this guest's own addresses: the host punches towards them */
        val candidates: List<String> = known ?: Tunnel.candidates(sock, withStun = stun)
        @Volatile var host: SocketAddress? = null
            private set
        @Volatile var lastHeard = 0L
            private set
        @Volatile var full = false
            private set
        inner class Session(val id: Long, val target: Int, private val back: (ByteArray) -> Unit) {
            internal val replay = Replay()
            @Volatile internal var tx = 0L
            @Volatile var welcomed = false
                internal set
            internal fun deliver(b: ByteArray) = back(b)
            fun send(data: ByteArray, off: Int, len: Int) {
                val h = host ?: return
                val out = seal(keys.toHost, DATA, id, tx++, data, off, len)
                runCatching { sock.send(DatagramPacket(out, out.size, h)) }
            }
            fun close() {
                sessions.remove(id)?.let { h -> host?.let { send(BYE, this, it) } }
            }
        }
        private val sessions = ConcurrentHashMap<Long, Session>()
        @Volatile private var targets: List<InetSocketAddress> = hostCandidates.mapNotNull { parse(it) }

        /** fresher host addresses (the host sends them through the signalling when a guest knocks) */
        fun addTargets(c: List<String>) {
            val add = c.mapNotNull { parse(it) }.filter { it !in targets }
            if (add.isNotEmpty()) { targets = add + targets; log("neue Host-Adressen: ${add.joinToString()}") }
        }

        init {
            thread(name = "tunnel-guest", isDaemon = true) {
                val buf = ByteArray(65535)
                while (!sock.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    if (runCatching { sock.receive(p) }.isFailure) { if (sock.isClosed) break else continue }
                    val m = open(keys.toGuest, buf, p.offset, p.length) ?: continue
                    if (m.type == PUNCH) continue
                    val s = sessions[m.session] ?: continue
                    if (!s.replay.fresh(m.counter)) continue
                    lastHeard = System.currentTimeMillis()
                    when (m.type) {
                        WELCOME -> {
                            // several leader addresses may answer the same hello burst: keep the first (fastest)
                            if (host == null) { host = p.socketAddress; log("Host erreicht über ${p.socketAddress}") }
                            s.welcomed = true
                        }
                        DATA -> { host = p.socketAddress; s.deliver(m.data) }
                        FULL -> { full = true; log("Raum ist voll") }
                        BYE -> { log("Host hat den Raum geschlossen"); s.welcomed = false }
                    }
                }
            }
            // hello to every candidate until the host answers, then a keepalive every 15 s (NAT mappings expire ~30 s)
            thread(name = "tunnel-guest-keep", isDaemon = true) {
                while (!sock.isClosed) {
                    val waiting = sessions.values.filter { !it.welcomed }
                    waiting.forEach { s -> (host?.let { listOf(it) } ?: targets).forEach { hello(s, it) } }
                    sessions.values.filter { it.welcomed }.forEach { s -> if (System.currentTimeMillis() - lastHeard > 12_000) host?.let { send(PING, s, it) } }
                    Thread.sleep(if (waiting.isEmpty()) 5_000 else 250)
                    if (host != null && System.currentTimeMillis() - lastHeard > 40_000) { log("Host antwortet nicht mehr, suche neu"); host = null; sessions.values.forEach { it.welcomed = false } }
                }
            }
        }

        private fun hello(s: Session, to: SocketAddress) {
            val out = seal(keys.toHost, HELLO, s.id, s.tx++, byteArrayOf((s.target shr 8).toByte(), s.target.toByte()))
            runCatching { sock.send(DatagramPacket(out, out.size, to)) }
        }

        private fun send(type: Byte, s: Session, to: SocketAddress) {
            val out = seal(keys.toHost, type, s.id, s.tx++, ByteArray(0))
            runCatching { sock.send(DatagramPacket(out, out.size, to)) }
        }

        /** a session to the leader's room at [target] (its port on the leader) */
        fun open(target: Int, back: (ByteArray) -> Unit): Session {
            val s = Session(rnd.nextLong() and Long.MAX_VALUE, target, back)
            sessions[s.id] = s
            (host?.let { listOf(it) } ?: targets).forEach { hello(s, it) }
            return s
        }

        fun connected() = host != null && System.currentTimeMillis() - lastHeard < 40_000

        /** waits up to [ms] for the first answer from the host (opens a probe session for it) */
        fun await(ms: Long, target: Int): Boolean {
            val probe = open(target) { }
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end && !probe.welcomed && !full) Thread.sleep(50)
            probe.close()
            return host != null
        }

        fun close() { ArrayList(sessions.values).forEach { it.close() }; sock.close() }
    }
}
