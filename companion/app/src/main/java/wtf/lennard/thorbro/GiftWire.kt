package wtf.lennard.thorbro

import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Moving a gift's file through the [Tunnel] (protocol: companion/FRIENDS.md, "Geschenke"). The tunnel carries
 * datagrams, so the receiver drives the transfer: it asks for byte ranges, the sender's [Server] answers with
 * chunks, and whatever got lost is asked for again. The receiver always knows the first byte it is missing, so a
 * download that switches between ways (local, RomM, tunnel) carries on there.
 *
 * Inside the (encrypted) tunnel session:
 *   request  'Q' part(1) offset(8) count(2)   → count chunks starting at offset
 *   answer   'D' part(1) offset(8) data(≤ CHUNK)
 *
 * Pure JVM like [Tunnel]: tested on the Mac (GiftWireTest), the same file can go into RomBro.
 */
object GiftWire {
    /** payload per datagram: with the tunnel's 48 bytes and our 10 it stays below 1232 (IPv6 minimum MTU) */
    const val CHUNK = 1150
    private const val GROUP = 16
    private const val Q: Byte = 'Q'.code.toByte()
    private const val D: Byte = 'D'.code.toByte()

    /** sender side: answers range requests for the gift's parts, on 127.0.0.1 (only the tunnel host talks to it) */
    class Server(private val parts: Map<Byte, File>) {
        val sock = DatagramSocket(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
        val port get() = sock.localPort
        private val open = HashMap<Byte, RandomAccessFile>()

        init {
            thread(name = "gift-wire", isDaemon = true) {
                val buf = ByteArray(64)
                val out = ByteArray(10 + CHUNK)
                while (!sock.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    if (runCatching { sock.receive(p) }.isFailure) { if (sock.isClosed) break else continue }
                    if (p.length < 12 || buf[0] != Q) continue
                    val b = ByteBuffer.wrap(buf, 0, p.length)
                    b.get(); val part = b.get(); val off = b.long; val count = (b.short.toInt() and 0xFFFF).coerceAtMost(64)
                    val raf = synchronized(open) { open[part] ?: parts[part]?.takeIf { it.isFile }?.let { RandomAccessFile(it, "r") }?.also { open[part] = it } } ?: continue
                    val len = raf.length()
                    for (k in 0 until count) {
                        val at = off + k.toLong() * CHUNK
                        if (at < 0 || at >= len) break
                        val n = synchronized(raf) { raf.seek(at); raf.read(out, 10, minOf(CHUNK.toLong(), len - at).toInt()) }
                        if (n <= 0) break
                        ByteBuffer.wrap(out).put(D).put(part).putLong(at)
                        runCatching { sock.send(DatagramPacket(out, 10 + n, p.socketAddress)) }
                    }
                }
            }
        }

        fun close() { sock.close(); synchronized(open) { open.values.forEach { runCatching { it.close() } }; open.clear() } }
    }

    fun request(part: Byte, offset: Long, count: Int): ByteArray =
        ByteBuffer.allocate(12).put(Q).put(part).putLong(offset).putShort(count.toShort()).array()

    /**
     * Receiver side: fills [target] from [from] to [size] through [send] (one tunnel session); [inbox] gets every
     * datagram the session delivers. Asks for a window of chunks that grows while answers come and halves on loss.
     * Returns the first byte still missing (= [size] when complete). Stops when [stop] says so or nothing arrives for
     * [idleMs].
     */
    fun fetch(part: Byte, target: File, from: Long, size: Long, inbox: LinkedBlockingQueue<ByteArray>, send: (ByteArray) -> Unit,
              progress: (Long) -> Unit = {}, stop: () -> Boolean = { false }, idleMs: Long = 20_000): Long {
        if (from >= size) return size
        val start = from - from % CHUNK
        var prefix = start
        var next = start
        var window = 64
        val inflight = LinkedHashMap<Long, Long>() // offset → asked at
        val got = HashSet<Long>()
        var heard = System.currentTimeMillis()
        var lastReport = 0L
        RandomAccessFile(target, "rw").use { raf ->
            fun ask(off: Long, count: Int) {
                send(request(part, off, count))
                val now = System.currentTimeMillis()
                for (k in 0 until count) { val o = off + k.toLong() * CHUNK; if (o < size) inflight[o] = now }
            }
            while (prefix < size) {
                if (stop()) break
                while (inflight.size < window && next < size) {
                    val count = minOf(GROUP.toLong(), (size - next + CHUNK - 1) / CHUNK).toInt()
                    ask(next, count); next += count.toLong() * CHUNK
                }
                val d = inbox.poll(40, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (d != null && d.size > 10 && d[0] == D && d[1] == part) {
                    val off = ByteBuffer.wrap(d, 2, 8).long
                    if (off >= prefix && off < size && off % CHUNK == 0L && off !in got) {
                        raf.seek(off); raf.write(d, 10, d.size - 10)
                        got += off; inflight.remove(off); heard = now
                        if (window < 1024) window++
                        while (prefix in got) { got.remove(prefix); prefix = minOf(prefix + CHUNK, size) }
                        if (now - lastReport > 250) { lastReport = now; progress(prefix) }
                    }
                }
                // lost: ask again (the oldest first), and send less at once
                val late = inflight.entries.filter { now - it.value > 700 }.map { it.key }.take(GROUP * 4)
                if (late.isNotEmpty()) {
                    window = maxOf(16, window / 2)
                    late.forEach { ask(it, 1) }
                }
                if (now - heard > idleMs) break
            }
            progress(prefix)
        }
        return prefix
    }
}
