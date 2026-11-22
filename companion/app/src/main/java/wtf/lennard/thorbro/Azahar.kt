package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 3DS multiplayer with Azahar (Citra's room system: a virtual LAN, the game's own local wireless mode works over it).
 *
 * The room itself runs on the host Thor ([Room]). Azahar's Android app joins rooms only in its own in-game menu
 * (Mehrspieler → Raum beitreten); no intent or file sets that. So ThorBro does everything around it:
 *  - tells both players exactly what to type (room IP + port; the IP is the one the other Thor can reach),
 *  - fixes the two things that silently break joining between two Thors: the same 3DS user name
 *    (every fresh Azahar is called "AZAHAR" → "Name schon vergeben") and the same console ID (copied nand),
 *  - shows whether the room is really up / the guest is really connected (Azahar's UDP socket in /proc/net/udp).
 *
 * The 3DS system settings live in a config save inside Azahar's user folder:
 * <Azahar user folder>/nand/data/000…0/sysdata/00010017/00000000/config — a block table (u16 count, u16 data offset,
 * then 12-byte entries: u32 id, u32 offset-or-inline-value, u16 size, u16 flags).
 */
object Azahar {
    const val PKG = "org.azahar_emu.azahar"
    const val PORT = 24872
    private const val USERNAME = 0x000A0000
    private const val CONSOLE_ID1 = 0x00090000
    private const val CONSOLE_ID2 = 0x00090001
    private const val CONSOLE_ID3 = 0x00090002
    private val nameRe = Regex("^[a-zA-Z0-9._\\- ]{4,10}$")

    /** Azahar's user folder: on the Thor it sits next to the 3DS games (<ROMs>/n3ds) */
    fun userDir(ctx: Context, rom: File? = null): File? {
        val roots = buildList {
            rom?.let { r -> generateSequence(r.parentFile) { it.parentFile }.firstOrNull { it.name.equals("ROMs", true) }?.let(::add) }
            runCatching { Roms.scan(ctx, false).optJSONArray("romRoots") }.getOrNull()?.let { a -> for (i in 0 until a.length()) add(File(a.getString(i))) }
            add(File(Storage.primary, "ROMs"))
        }
        return roots.map { File(it, "n3ds") }.firstOrNull { File(it, "nand").isDirectory }
    }

    private fun configFile(ctx: Context, rom: File?) =
        userDir(ctx, rom)?.let { File(it, "nand/data/00000000000000000000000000000000/sysdata/00010017/00000000/config") }?.takeIf { it.isFile }

    private class Block(val dataPos: Long, val size: Int)

    private fun blocks(f: RandomAccessFile): Map<Int, Block> {
        val head = ByteArray(4); f.seek(0); f.readFully(head)
        val count = le16(head, 0)
        val table = ByteArray(12 * count); f.readFully(table)
        return (0 until count).associate { i ->
            val o = 12 * i
            val size = le16(table, o + 8)
            // ≤ 4 bytes are stored inline in the offset field
            le32(table, o) to Block(if (size <= 4) 4L + o + 4 else le32(table, o + 4).toLong() and 0xFFFFFFFFL, size)
        }
    }

    /** current 3DS user name and a short hash of the console ID (what the room compares) */
    fun identity(ctx: Context, rom: File? = null): JSONObject? = runCatching {
        val cfg = configFile(ctx, rom) ?: return null
        RandomAccessFile(cfg, "r").use { f ->
            val b = blocks(f)
            val name = b[USERNAME]?.let { blk -> ByteArray(20).also { f.seek(blk.dataPos); f.readFully(it) } }
                ?.let { String(it, Charsets.UTF_16LE).substringBefore('\u0000').trim() } ?: ""
            val id = b[CONSOLE_ID2]?.let { blk -> ByteArray(8).also { f.seek(blk.dataPos); f.readFully(it) } }
            JSONObject().put("user", name).put("console", id?.let { hash(it) } ?: "")
        }
    }.getOrNull()

    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).take(6).joinToString("") { "%02x".format(it) }

    /**
     * Makes this device distinct from the host before joining: new user name if it is the same (or Azahar's default),
     * new console ID if it is the same. Azahar must not be running (it keeps the config in memory). Backup next to it.
     * Returns what was changed (for the hint), or null when nothing had to change.
     */
    fun makeDistinct(ctx: Context, rom: File, hostUser: String?, hostConsole: String?): String? {
        val cfg = configFile(ctx, rom) ?: return null
        val me = identity(ctx, rom) ?: return null
        val sameName = hostUser != null && me.optString("user").equals(hostUser, true)
        val sameId = !hostConsole.isNullOrEmpty() && me.optString("console") == hostConsole
        if (!sameName && !sameId) return null
        File(cfg.parentFile, "config.thorbro.bak").takeIf { !it.exists() }?.let { cfg.copyTo(it) }
        val changes = mutableListOf<String>()
        RandomAccessFile(cfg, "rw").use { f ->
            val b = blocks(f)
            if (sameName) b[USERNAME]?.let { blk ->
                val name = newName(ctx, hostUser)
                val buf = ByteArray(20)
                name.toByteArray(Charsets.UTF_16LE).copyInto(buf)
                f.seek(blk.dataPos); f.write(buf)
                f.write(ByteArray(4)) // the zero after the name; the ng-word flag after it stays
                changes += "3DS-Name → $name"
            }
            if (sameId) {
                val id1 = b[CONSOLE_ID1]; val id2 = b[CONSOLE_ID2]
                if (id2 != null) {
                    val cur = ByteArray(8).also { f.seek(id2.dataPos); f.readFully(it) }
                    val rnd = (1..0xFFFF).random()
                    // like Azahar's "Konsolen-ID neu erzeugen": new random low part, the friend-code seed part stays
                    cur[0] = (rnd and 0xFF).toByte(); cur[1] = (rnd shr 8 and 0xFF).toByte()
                    cur[2] = ((1..255).random()).toByte(); cur[3] = ((0..255).random()).toByte()
                    listOfNotNull(id1, id2).forEach { f.seek(it.dataPos); f.write(cur) }
                    b[CONSOLE_ID3]?.let { f.seek(it.dataPos); f.write(byteArrayOf((rnd and 0xFF).toByte(), (rnd shr 8 and 0xFF).toByte(), 0, 0)) }
                    changes += "neue Konsolen-ID"
                }
            }
        }
        Logs.i("azahar", "Für den Raum angepasst: ${changes.joinToString()}")
        return changes.joinToString(", ").ifEmpty { null }
    }

    /** "Thor" + 4 digits, the same every time on this device, never the host's */
    private fun newName(ctx: Context, avoid: String?): String {
        val p = Prefs.of(ctx)
        var n = p.getString("azahar.name", null)
        if (n == null || !nameRe.matches(n) || n.equals(avoid, true)) {
            n = "Thor" + (1000..9999).random()
            p.edit().putString("azahar.name", n).apply()
        }
        return n
    }

    // ---------- is the room really there? ----------

    /** "hosting" (a socket is bound to the room port), "connected" (talks to a room), "off", or "unknown" (no Shizuku) */
    fun roomState(): String {
        val text = Leds.shellOut("cat", "/proc/net/udp", "/proc/net/udp6")
            ?: runCatching { File("/proc/net/udp").readText() + File("/proc/net/udp6").readText() }.getOrNull()
            ?: return "unknown"
        val hexPort = "%04X".format(PORT)
        var connected = false
        text.lineSequence().drop(1).forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@forEach
            if (cols[1].endsWith(":$hexPort")) return "hosting"
            if (cols[2].endsWith(":$hexPort")) connected = true
        }
        if (text.isBlank()) return "unknown"
        return if (connected) "connected" else "off"
    }

    // ---------- what to type ----------

    /** the room already runs on this Thor ([Room]); the host only joins it */
    fun hostSteps() =
        "Der Raum läuft auf diesem Thor.\nIn Azahar: Zurück-Taste → Mehrspieler → Raum beitreten\nIP: 127.0.0.1   Port: $PORT   Passwort: leer\nDanach im Spiel den lokalen Mehrspieler/Drahtlos-Modus wählen."

    fun guestSteps(ip: String, port: Int = PORT) =
        "In Azahar: Zurück-Taste → Mehrspieler → Raum beitreten\nIP: $ip   Port: $port   Passwort: leer\nDanach im Spiel den lokalen Mehrspieler/Drahtlos-Modus wählen."

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
}
