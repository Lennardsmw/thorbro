package wtf.lennard.thorbro

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Puts games that land loose in a ROM folder (upload, link, unpacked archive) into their console folder.
 * A unique extension says it (.nds → nds); an .iso is read: Xbox 360 / Xbox (XDVDFS), PS3, PS2, PS1, PSP (ISO 9660),
 * GameCube, Wii (disc magic). Site tags like "(axekin.com)" come off the name, so libretro finds the cover.
 * Archives lying loose in a ROM folder are unpacked by themselves ([sweep]): through the transfer queue, so it shows
 * on the Downloads page.
 */
object Discs {
    private val cache = ConcurrentHashMap<String, String>() // path|size|mtime → system ("" = unknown)

    /** console of a disc image, null = not recognised */
    fun sniff(f: File): String? {
        if (!f.isFile || f.length() < 0x10000) return null
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        cache[key]?.let { return it.ifEmpty { null } }
        val s = runCatching { RandomAccessFile(f, "r").use { read(it) } }.getOrNull()
        cache[key] = s ?: ""
        return s
    }

    private fun RandomAccessFile.bytes(at: Long, n: Int): ByteArray? {
        if (at < 0 || at + n > length()) return null
        seek(at); val b = ByteArray(n); readFully(b); return b
    }
    private fun ByteArray.u32le(o: Int) = (this[o].toLong() and 0xff) or ((this[o + 1].toLong() and 0xff) shl 8) or
        ((this[o + 2].toLong() and 0xff) shl 16) or ((this[o + 3].toLong() and 0xff) shl 24)
    private fun ByteArray.u32be(o: Int) = ((this[o].toLong() and 0xff) shl 24) or ((this[o + 1].toLong() and 0xff) shl 16) or
        ((this[o + 2].toLong() and 0xff) shl 8) or (this[o + 3].toLong() and 0xff)
    private fun ByteArray.ascii(o: Int, n: Int) = String(this, o, n, Charsets.ISO_8859_1)

    private fun read(r: RandomAccessFile): String? {
        // Nintendo discs: magic words in the disc header
        r.bytes(0, 0x20)?.let { h ->
            if (h.u32be(0x18) == 0x5D1C9EA3L) return "wii"
            if (h.u32be(0x1C) == 0xC2339F3DL) return "gc"
        }
        // Xbox / Xbox 360 (XDVDFS): "MICROSOFT*XBOX*MEDIA" at sector 32 of the game partition
        for (part in longArrayOf(0L, 0xFD90000L, 0x2080000L, 0x18300000L)) {
            val h = r.bytes(part + 0x10000, 0x20) ?: continue
            if (h.ascii(0, 20) != "MICROSOFT*XBOX*MEDIA") continue
            val dirSector = h.u32le(20); val dirSize = h.u32le(24).coerceAtMost(1L shl 20).toInt()
            val dir = r.bytes(part + dirSector * 2048, dirSize)?.let { String(it, Charsets.ISO_8859_1).lowercase() } ?: ""
            return when {
                "default.xex" in dir -> "xbox360"
                "default.xbe" in dir -> "xbox"
                part == 0x18300000L -> "xbox"
                else -> "xbox360"
            }
        }
        // PS3: "PlayStation3" at the start of sector 1
        r.bytes(0x800, 12)?.let { if (it.ascii(0, 12) == "PlayStation3") return "ps3" }
        // ISO 9660: look at the root folder
        val pvd = r.bytes(0x8000, 2048) ?: return null
        if (pvd.ascii(1, 5) != "CD001") return null
        val names = rootNames(r, pvd)
        return when {
            "PS3_GAME" in names -> "ps3"
            "PSP_GAME" in names || "UMD_DATA.BIN" in names -> "psp"
            "SYSTEM.CNF" in names -> systemCnf(r, pvd)
            else -> null
        }
    }

    private fun rootEntries(r: RandomAccessFile, pvd: ByteArray): List<Triple<String, Long, Long>> {
        val lba = pvd.u32le(156 + 2); val size = pvd.u32le(156 + 10).coerceAtMost(1L shl 16).toInt()
        val d = r.bytes(lba * 2048, size) ?: return emptyList()
        val out = mutableListOf<Triple<String, Long, Long>>()
        var i = 0
        while (i < d.size) {
            val len = d[i].toInt() and 0xff
            if (len == 0) { i = (i / 2048 + 1) * 2048; continue }
            if (i + 33 > d.size) break
            val nl = d[i + 32].toInt() and 0xff
            if (i + 33 + nl > d.size) break
            val name = d.ascii(i + 33, nl).substringBefore(';')
            out += Triple(name.uppercase(), d.u32le(i + 2), d.u32le(i + 10))
            i += len
        }
        return out
    }
    private fun rootNames(r: RandomAccessFile, pvd: ByteArray) = rootEntries(r, pvd).map { it.first }.toSet()

    /** SYSTEM.CNF says BOOT2 on PS2 discs, BOOT on PS1 discs */
    private fun systemCnf(r: RandomAccessFile, pvd: ByteArray): String {
        val e = rootEntries(r, pvd).firstOrNull { it.first == "SYSTEM.CNF" } ?: return "ps2"
        val txt = r.bytes(e.second * 2048, e.third.coerceAtMost(4096).toInt())?.let { String(it, Charsets.ISO_8859_1).uppercase() } ?: ""
        return if ("BOOT2" in txt) "ps2" else "psx"
    }

    /** "Grand Theft Auto IV (axekin.com).iso" → "Grand Theft Auto IV.iso" */
    fun cleanName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        var base = if (ext.isEmpty()) name else name.substringBeforeLast('.')
        base = base.replace(Regex("\\s*[\\[(][^\\])]*\\b[\\w-]+\\.(com|net|org|to|cc|io|ws|me|info|ru|xyz|club|site)\\b[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-?\\s*www\\.[\\w-]+\\.[a-z]{2,6}", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ").trim().ifEmpty { base }
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** the ROM folder a file lies loose in (not in a console folder), null = somewhere else */
    private fun looseIn(ctx: Context, f: File): File? {
        val root = Library.romsRoot(ctx).canonicalFile
        return if (f.canonicalFile.parentFile == root) root else null
    }

    /** moves loose games into their console folder; returns where each one went (unknown ones stay) */
    fun place(ctx: Context, files: List<File>): List<File> = files.map { f ->
        val root = looseIn(ctx, f) ?: return@map f
        if (!f.isFile) return@map f
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val sys = Roms.uniqueSystem(ext) ?: sniff(f) ?: return@map f
        val dir = File(root, sys).apply { mkdirs() }
        var t = File(dir, cleanName(f.name))
        if (t.exists()) t = File(dir, f.name).takeIf { !it.exists() } ?: return@map f
        if (f.renameTo(t)) { Roms.invalidate(); t } else f
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Loose archives in the ROM folder (finished, not a .thorbro-part) are unpacked by themselves, then sorted.
     * Runs at start and after a library scan; the queue makes sure each one is only in the list once.
     */
    fun sweep(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            val root = Library.romsRoot(ctx)
            val loose = root.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && Archives.isArchive(it) }
            for (a in loose) {
                if (System.currentTimeMillis() - a.lastModified() < 10_000) continue // still being written
                TransferQueue.addMove(ctx, a.path, root.path, cleanName(a.name.substringBeforeLast('.')), a.length(), "", extract = true, overwrite = false, quiet = true)
            }
            // loose games that are already unpacked (an .iso uploaded to the ROM folder itself)
            place(ctx, root.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && !Archives.isArchive(it) && !it.name.endsWith(".thorbro-part") })
        } finally { running.set(false) }
    }
}
