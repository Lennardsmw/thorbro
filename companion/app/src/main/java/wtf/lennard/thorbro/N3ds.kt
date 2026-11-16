package wtf.lennard.thorbro

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 3DS saves in Azahar: one folder per game, found by the title id in the ROM header:
 * <ROMs>/n3ds/sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<title id low>/data/00000001/
 */
object N3ds {
    private val ids = ConcurrentHashMap<String, String>()

    /** "00040000000edf00" from a .3ds/.cci (NCSD) or .cxi (NCCH) header; null for other formats */
    fun titleId(rom: File): String? {
        if (!rom.isFile || rom.length() < 0x200) return null
        val k = "${rom.path}|${rom.length()}|${rom.lastModified()}"
        ids[k]?.let { return it.ifEmpty { null } }
        val id = runCatching {
            RandomAccessFile(rom, "r").use { r ->
                val b = ByteArray(0x200); r.readFully(b)
                val off = when (String(b, 0x100, 4, Charsets.US_ASCII)) { "NCSD" -> 0x108; "NCCH" -> 0x118; else -> -1 }
                if (off < 0) "" else "%016x".format((0 until 8).fold(0L) { a, i -> a or ((b[off + i].toLong() and 0xFF) shl (8 * i)) })
            }
        }.getOrDefault("").takeIf { it.startsWith("00040000") } ?: ""
        ids[k] = id
        return id.ifEmpty { null }
    }

    /** the game's save folder; with [create], made in the existing Azahar user folder when the game never saved */
    fun saveDir(rom: File, create: Boolean = false): File? {
        val tid = titleId(rom) ?: return null
        val romsRoot = generateSequence(rom.parentFile) { it.parentFile }.firstOrNull { it.name.equals("ROMs", true) } ?: return null
        val sd = File(romsRoot, "n3ds/sdmc/Nintendo 3DS")
        val users = sd.listFiles { f -> f.isDirectory && f.name.length == 32 }.orEmpty()
            .flatMap { id0 -> id0.listFiles { f -> f.isDirectory && f.name.length == 32 }.orEmpty().toList() }
        val rel = "title/00040000/${tid.substring(8)}/data/00000001"
        users.map { File(it, rel) }.firstOrNull { it.isDirectory }?.let { return it }
        return if (create) users.firstOrNull()?.let { File(it, rel).apply { mkdirs() } } else null
    }
}
