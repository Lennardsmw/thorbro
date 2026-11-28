package wtf.lennard.thorbro

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Downloads inbox: games that land in Android's Download folder (browser, other apps) show up on ThorBro's
 * Downloads page as "bereit zum Installieren" (kind "found" in the [TransferQueue]). Installing (one click, or by
 * itself with [Prefs.autoInstall]): unpack (zip/7z/rar) or copy → console folder ([Discs]) → scrape: ThorBro's own
 * library and cover first, then Cocoon (its cover folder), iiSU picks the game up on its own scan of the ROM folder.
 * Then clean up ([Prefs.autoClean]) and send to RomM ([Prefs.autoUpload]).
 */
object Inbox {
    val dir: File get() = File(Storage.primary, "Download")
    private val discExts = setOf("iso", "chd", "cso", "cue", "rvz", "gcz", "wbfs", "wua", "pbp", "xiso")
    private val skipExts = setOf("crdownload", "part", "tmp", "download", "apk", "thorbro-part")
    private const val STABLE_MS = 15_000L
    private const val IGNORED = "inbox.ignored"
    private var observer: FileObserver? = null
    private val main = Handler(Looper.getMainLooper())

    /** does this look like a game (or an archive with one)? */
    fun candidate(f: File): Boolean {
        if (!f.isFile || f.name.startsWith(".") || f.length() == 0L) return false
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext in skipExts) return false
        return Archives.isArchive(f) || Roms.uniqueSystem(ext) != null || ext in discExts
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (observer == null) {
            observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    main.removeCallbacksAndMessages(this@Inbox); main.postAtTime({ scanLater(app) }, this@Inbox, android.os.SystemClock.uptimeMillis() + STABLE_MS + 1000)
                }
            }.also { it.startWatching() }
        }
        scanLater(app)
    }

    private fun scanLater(ctx: Context) = kotlin.concurrent.thread(isDaemon = true, name = "inbox") { runCatching { scan(ctx) } }

    /** every finished candidate in Download that is not in the list yet (and was not removed by hand) */
    @Synchronized fun scan(ctx: Context) {
        val ignored = Prefs.of(ctx).getStringSet(IGNORED, emptySet())!!
        val now = System.currentTimeMillis()
        for (f in dir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if (!candidate(f) || f.path in ignored) continue
            if (now - f.lastModified() < STABLE_MS) { main.postDelayed({ scanLater(ctx) }, STABLE_MS); continue } // still being written
            TransferQueue.addFound(ctx, f, auto = Prefs.autoInstall(ctx))
        }
    }

    fun ignore(ctx: Context, path: String) {
        val p = Prefs.of(ctx); p.edit().putStringSet(IGNORED, p.getStringSet(IGNORED, emptySet())!! + path).apply()
    }

    /** installs item [e] (kind "found": src = file in Download) */
    fun install(ctx: Context, e: JSONObject): Jobs.Job {
        val app = ctx.applicationContext
        return Jobs.start("Installieren") { job ->
            val src = File(e.getString("src"))
            if (!src.isFile) error("Datei ist nicht mehr da: ${src.name}")
            val root = Library.romsRoot(app).apply { mkdirs() }
            val clean = Prefs.autoClean(app)
            job.game(e.optString("name"), "")
            val placed: List<File> = if (Archives.isArchive(src)) {
                // unpack straight into the ROM folder, then sort what came out into console folders
                job.message = "entpacke ${src.name}"
                val r = Archives.extract(src, root, deleteAfter = clean, job = job, sort = app)
                val a = r.optJSONArray("paths") ?: org.json.JSONArray()
                (0 until a.length()).map { File(a.getString(it)) }
            } else {
                val ext = src.name.substringAfterLast('.', "").lowercase()
                val sys = Roms.uniqueSystem(ext) ?: Discs.sniff(src) ?: error("Konsole nicht erkannt: ${src.name}")
                val t = File(File(root, sys).apply { mkdirs() }, Discs.cleanName(src.name))
                if (t.exists() && t.length() == src.length()) { if (clean) src.delete() }
                else copy(app, src, t, job, move = clean)
                listOf(t)
            }
            val games = scrape(app, placed, job)
            if (games.isEmpty()) error("Nichts Spielbares gefunden (unbekannte Konsole?) – liegt in ${root.path}")
            if (clean) cleanup(app)
            if (Prefs.autoUpload(app) && runCatching { Romm.status(app).optBoolean("connected") }.getOrDefault(false)) {
                for (g in games) TransferQueue.addUpload(app, setOf(g.path), Discs.cleanName(g.name).substringBeforeLast('.'), g.length(), g.parentFile!!.name)
            }
            History.add(app, "install", e.optString("name"), games.first().parentFile!!.name, size = games.sumOf { it.length() })
            Progress.done(app, "Installiert", games.joinToString { it.nameWithoutExtension })
            JSONObject().put("paths", org.json.JSONArray(games.map { it.path }))
        }
    }

    /**
     * After unpacking/placing: games in subfolders are sorted too, then the library is rescanned and the cover
     * loaded (ThorBro first), then handed to Cocoon. Returns the games that now sit in a console folder.
     */
    fun scrape(ctx: Context, placed: List<File>, job: Jobs.Job? = null): List<File> {
        val root = Library.romsRoot(ctx).canonicalFile
        val inConsole = { f: File -> f.exists() && f.canonicalFile.parentFile?.parentFile == root }
        val out = mutableListOf<File>()
        for (p in placed) {
            if (inConsole(p)) { out += p; continue }
            if (p.isDirectory && p.canonicalFile.parentFile == root && Roms.systems.none { it.id == p.name }) {
                // "Game/Game.iso" out of an archive: take the games out of the folder, drop it when empty
                val inner = p.walkTopDown().maxDepth(3).filter { it.isFile && candidate(it) && !Archives.isArchive(it) }.toList()
                val moved = inner.map { f -> File(root, f.name).let { t -> if (!t.exists() && f.renameTo(t)) t else f } }
                out += Discs.place(ctx, moved).filter(inConsole)
                if (p.walkTopDown().none { it.isFile }) p.deleteRecursively()
            }
        }
        job?.message = "lade Cover"
        Roms.invalidate(); runCatching { Roms.scan(ctx, true) }
        for (g in out) {
            val sys = g.parentFile!!.name
            val cover = runCatching { Media.coverForFile(ctx, sys, g) }.getOrNull()
            if (cover != null) runCatching { toCocoon(ctx, sys, g, cover) }
        }
        return out
    }

    /** Cocoon finds a cover by the ROM's name in downloaded_media/<system>/icon; only added when it has none */
    private fun toCocoon(ctx: Context, sys: String, game: File, cover: File) {
        if (Cocoon.media(ctx, sys, game.path, "icon") != null) return
        val root = Cocoon.roots(ctx).firstOrNull() ?: return
        val d = File(root, "downloaded_media/$sys/icon").apply { mkdirs() }
        cover.copyTo(File(d, game.nameWithoutExtension + "." + cover.extension.ifEmpty { "png" }), overwrite = false)
    }

    private fun copy(ctx: Context, src: File, t: File, job: Jobs.Job, move: Boolean) {
        if (move && src.renameTo(t)) return // same volume: instant
        val part = File(t.path + ".thorbro-part")
        val total = src.length().coerceAtLeast(1)
        Transfers.begin(ctx)
        try {
            FileInputStream(src).use { i -> FileOutputStream(part).use { o ->
                val buf = ByteArray(4 shl 20); var done = 0L
                while (true) { job.check(); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                    job.progress = done.toDouble() / total; job.bytes(done, total); job.gameBytes(done, total) }
                o.fd.sync()
            } }
        } catch (s: Jobs.Stopped) { part.delete(); throw s } finally { Transfers.end() }
        if (t.exists()) t.delete()
        if (!part.renameTo(t)) error("Umbenennen ging nicht")
        if (move) src.delete()
    }

    /** leftovers: empty .thorbro-part files (older than 1 h) in the ROM folder root and Download, old staging copies */
    fun cleanup(ctx: Context) {
        val old = System.currentTimeMillis() - 3_600_000
        for (d in listOf(Library.romsRoot(ctx), dir)) d.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".thorbro-part") && it.length() == 0L && it.lastModified() < old }
            .forEach { it.delete() }
        Staging.dir.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }.forEach { it.deleteRecursively() }
    }
}
