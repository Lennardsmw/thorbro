package wtf.lennard.thorbro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Multiplayer without opening ThorBro, once two Thors are friends ([Friends]):
 *  - Guest: ThorBro listens for invites over the air in the background and shows a notification
 *    ("Lena lädt ein: Pokémon X · Annehmen"). Annehmen joins the Wi-Fi Direct net, fetches the game if needed and starts it.
 *  - Host: a game started from ANY frontend (ES-DE, Daijishō, the emulator itself …) is recognized — the emulator in front
 *    and the game it was opened with (Shizuku: `dumpsys activity`; RetroArch: its network command port) — and the friends
 *    get invited by themselves. The lobby closes when the emulator is gone.
 * Works only while the screen is on, and only when there is at least one friend, so nothing runs for nothing.
 */
object AutoPlay {
    private const val CH_INVITE = "invites"
    private const val CH_HOST = "netplay"
    private const val ID_HOST = 4101
    const val ACTION_CLOSE = "wtf.lennard.thorbro.NETPLAY_CLOSE"

    @Volatile private var running = false
    @Volatile private var lastGame: String? = null
    @Volatile private var goneTicks = 0
    private val notified = mutableMapOf<String, Int>() // invite key → notification id
    private var lastScan = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        channels(app)
        thread(name = "autoplay", isDaemon = true) {
            while (running) {
                val wait = runCatching { tick(app) }.onFailure { Logs.e("autoplay", "tick", it) }.getOrDefault(15_000L)
                Thread.sleep(wait)
            }
        }
    }

    fun stop() { running = false }

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INVITE, "Multiplayer-Einladungen", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_HOST, "Multiplayer-Lobby", NotificationManager.IMPORTANCE_LOW))
    }

    /** one round; returns how long to wait before the next one */
    private fun tick(ctx: Context): Long {
        if ((!Friends.any(ctx) && !Netplay.serverOn && !Party.leading(ctx)) || !Power.interactive(ctx)) { hostNotice(ctx); return 15_000L }
        val top = if (Shell.ready()) topActivity() else null
        val emu = top?.let { t -> Emulators.all.firstOrNull { it.pkg == t.first } }

        // ---- host: a game was started somewhere else ----
        val lobby = Netplay.lobby
        if (lobby != null && lobby.auto) {
            if (running(lobby.pkg)) goneTicks = 0 else if (++goneTicks >= 2) { Netplay.close(ctx); lastGame = null }
        } else if (emu != null && (Friends.auto(ctx) || Netplay.serverOn || Party.leading(ctx)) && lobby == null) {
            val game = findGame(ctx, emu, top.second)
            val key = game?.let { "${emu.id}:${it.second.path}" }
            if (game != null && key != lastGame && Netplay.mode(ctx, game.first) == emu.id) {
                lastGame = key
                val res = Netplay.hostRunning(ctx, game.first, game.second, emu.pkg)
                if (res.has("error")) Logs.i("autoplay", "Nicht eingeladen: ${res.optString("error")}")
            }
        }
        if (emu == null && lobby == null) lastGame = null
        // guest session over: put the emulator back to hosting settings (PPSSPP keeps the friend's address otherwise)
        // (waits until the emulator was seen running, or 10 min, so "use a friend's room" survives until Azahar is opened)
        Netplay.joinedRoom?.let { j ->
            val pkg = Emulators.all.firstOrNull { it.id == j.optString("mode") }?.pkg
            if (pkg != null && (emu?.pkg == pkg || running(pkg))) j.put("seen", true)
            else if (pkg != null && (j.optBoolean("seen") || now() - j.optLong("at") > 600_000)) {
                Netplay.clearJoined(ctx); runCatching { Netplay.prepare(ctx) }
            }
        }
        hostNotice(ctx)

        // ---- guest: invites from friends nearby (radio scan, not while playing) ----
        val now = System.currentTimeMillis()
        if (Netplay.lobby == null && emu == null && Netplay.joinedRoom == null && !Direct.inForeignGroup() && now - lastScan > 25_000) {
            lastScan = now
            val found = Netplay.invites(ctx)
                .filter { Friends.isFriend(ctx, it.optString("id")) && (it.optString("pin").isNotEmpty() || it.optString("mode") == "server") }
            val keys = found.map { it.optString("id") + it.optString("pin") }.toSet()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            notified.keys.filter { it !in keys }.forEach { k -> notified.remove(k)?.let { nm.cancel(it) } }
            found.forEach { inv ->
                val k = inv.optString("id") + inv.optString("pin")
                if (k !in notified) { notified[k] = 4200 + (notified.size % 50); inviteNotice(ctx, inv, notified[k]!!) }
            }
        }
        return 6_000L
    }

    private fun now() = System.currentTimeMillis()

    private fun running(pkg: String?) = pkg != null && Shell.out("pidof", pkg)?.isNotBlank() == true

    // ---------- what is in front, and which game ----------

    /** (package, intent data) of the resumed activity, from `dumpsys activity activities` (needs Shizuku) */
    internal fun topActivity(): Pair<String, String?>? {
        val text = Shell.out("sh", "-c", "dumpsys activity activities | grep -E 'ResumedActivity|Hist |intent=\\{'") ?: return null
        val lines = text.lines()
        val resumed = lines.firstOrNull { it.contains("ResumedActivity") && it.contains("ActivityRecord{") } ?: return null
        val rec = Regex("ActivityRecord\\{(\\S+) u\\d+ ([^/\\s]+)/").find(resumed) ?: return null
        val (token, pkg) = rec.destructured
        val at = lines.indexOfFirst { it.contains("Hist ") && it.contains("ActivityRecord{$token ") }
        val intent = if (at >= 0) lines.drop(at + 1).take(4).firstOrNull { it.contains("intent={") } else null
        val dat = intent?.let { Regex("dat=(\\S+)").find(it)?.groupValues?.get(1) }
        return pkg to dat
    }

    /** turns an intent data URI into a file path (SAF documents of the internal storage or an SD card, file://, plain paths) */
    internal fun uriPath(dat: String?): String? {
        if (dat.isNullOrEmpty()) return null
        if (dat.startsWith("/")) return dat
        if (dat.startsWith("file://")) return URLDecoder.decode(dat.removePrefix("file://"), "UTF-8")
        if (!dat.startsWith("content://")) return null
        val doc = URLDecoder.decode(dat.substringAfterLast("/document/", dat.substringAfterLast("/tree/", "")), "UTF-8").ifEmpty { return null }
        val vol = doc.substringBefore(':', "")
        val rel = doc.substringAfter(':', "")
        if (vol.isEmpty() || rel.isEmpty()) return null
        return if (vol == "primary") "/storage/emulated/0/$rel" else "/storage/$vol/$rel"
    }

    /** (console, ROM) of the game the emulator in front was opened with */
    private fun findGame(ctx: Context, emu: Emulators.Emu, dat: String?): Pair<String, File>? {
        uriPath(dat)?.let { p -> Netplay.findRom(ctx, path = p)?.let { return it } }
        if (emu.id == "retroarch") retroArchGame()?.let { name -> Netplay.findRom(ctx, name = name)?.let { return it } }
        return null
    }

    /** RetroArch's own answer: "GET_STATUS PLAYING <system>,<game>,crc32=…" (needs network commands on, see Netplay.prepare) */
    private fun retroArchGame(): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 700
            val q = "GET_STATUS".toByteArray()
            s.send(DatagramPacket(q, q.size, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 55355))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val r = String(buf, 0, p.length).trim()
            if (!r.startsWith("GET_STATUS PLAYING") && !r.startsWith("GET_STATUS PAUSED")) null
            else r.substringAfter(' ').substringAfter(' ').split(',').getOrNull(1)?.trim()
        }
    }.getOrNull()

    // ---------- notifications ----------

    private fun inviteNotice(ctx: Context, inv: JSONObject, id: Int) {
        val share = inv.optString("mode") == "share"
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, wtf.lennard.thorbro.ui.InviteActivity::class.java)
            .putExtra("invite", inv.toString()).putExtra("notification", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_INVITE)
            .setSmallIcon(R.drawable.ic_stat_game)
            .setContentTitle(if (share) "${inv.optString("name")} schickt dir ein Spiel" else "${inv.optString("name")} lädt ein")
            .setContentText(inv.optString("game").ifEmpty { "Multiplayer" })
            .setCategory(Notification.CATEGORY_SOCIAL)
            .apply { gameBitmap(ctx, "", null, inv.optString("game"))?.let { setLargeIcon(it) } }
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(10 * 60_000L)
            .addAction(Notification.Action.Builder(null, if (share) "Annehmen" else "Beitreten", open).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
        Logs.i("autoplay", "Einladung von ${inv.optString("name")}: ${inv.optString("game")}")
    }

    /** ongoing note while a lobby opened by itself runs, with "Beenden" */
    private fun hostNotice(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val l = Netplay.lobby
        if ((l == null || !l.auto) && !Netplay.serverOn) { nm.cancel(ID_HOST); return }
        val close = PendingIntent.getBroadcast(ctx, ID_HOST, Intent(ctx, CloseReceiver::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val who = l?.guests?.toList()?.joinToString(", ").orEmpty()
        val text = when {
            who.isNotEmpty() -> "Dabei: $who"
            l?.mode == "azahar" -> "In Azahar: Mehrspieler → Raum beitreten → 127.0.0.1"
            l == null -> "Starte ein Spiel aus einem beliebigen Frontend"
            else -> "Freunde in der Nähe bekommen eine Einladung"
        }
        val n = Notification.Builder(ctx, CH_HOST)
            .setSmallIcon(R.drawable.ic_stat_game)
            .setContentTitle(if (l != null) "Mehrspieler offen: ${l.public().optString("game")}" else "Spielserver läuft")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .apply { l?.let { gameBitmap(ctx, it.system, it.rom, null) }?.let { setLargeIcon(it) } }
            .addAction(Notification.Action.Builder(null, "Beenden", close).build())
            .build()
        runCatching { nm.notify(ID_HOST, n) }
    }

    private val bitmaps = mutableMapOf<String, android.graphics.Bitmap?>()

    /** the game's picture for the notification: own copy of the ROM by path, or (guest) by name */
    private fun gameBitmap(ctx: Context, system: String, rom: File?, name: String?): android.graphics.Bitmap? {
        val key = rom?.path ?: "$system/$name"
        return synchronized(bitmaps) { bitmaps.getOrPut(key) {
            val (sys, f) = if (rom != null) system to rom else name?.let { Netplay.findRom(ctx, name = it) } ?: return@getOrPut null
            Netplay.art(ctx, sys, f)?.takeIf { it.extension.lowercase() != "svg" }?.let { img ->
                runCatching {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(img.path, o)
                    o.inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / 256); o.inJustDecodeBounds = false
                    android.graphics.BitmapFactory.decodeFile(img.path, o)
                }.getOrNull()
            }
        } }
    }

    class CloseReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_CLOSE) thread { Netplay.close(ctx.applicationContext); hostNotice(ctx.applicationContext) }
        }
    }
}
