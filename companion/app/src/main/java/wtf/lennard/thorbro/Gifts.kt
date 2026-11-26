package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Sending saves and games to friends (protocol: companion/FRIENDS.md, "Geschenke").
 *
 * The sender keeps the files where they are (a folder game is packed once) and offers them three ways:
 *  - local: its own web server, `/api/gift/<gid>/<part>?k=<token>` with byte ranges (WLAN, Wi-Fi Direct, NetBird),
 *  - RomM: when both use the same RomM server and the game lies there, the receiver loads it with its own login,
 *  - tunnel: a [Tunnel] just for this gift (own secret), carrying [GiftWire] range requests.
 * A save up to 15 MB also travels as an encrypted ntfy attachment, so it arrives while the sender is offline.
 *
 * The receiver sees the gift under "Für dich", tries the allowed ways with a short download each and takes the
 * fastest; if one breaks off, the next carries on at the same byte. At the end the SHA-1 is checked.
 * A received save is put in as the live save (the old one becomes a backup first) or only kept as a backup.
 */
object Gifts {
    const val SAVE: Byte = 1
    const val GAME: Byte = 2
    private const val DAYS = 7L
    private const val ATT_MAX = 15L shl 20
    private const val PROBE = 4L shl 20
    private const val PER_HOUR = 10
    private val rnd = SecureRandom()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun randHex(n: Int) = hex(ByteArray(n).also { rnd.nextBytes(it) })
    private fun now() = System.currentTimeMillis()

    // ---------- stored lists ----------

    private fun read(ctx: Context, key: String): MutableList<JSONObject> =
        runCatching { JSONArray(Prefs.of(ctx).getString(key, "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    private fun write(ctx: Context, key: String, l: List<JSONObject>) { Prefs.of(ctx).edit().putString(key, JSONArray(l).toString()).apply(); Pals.bump() }

    @Synchronized private fun outgoing(ctx: Context) = read(ctx, "gifts.out")
    @Synchronized private fun incoming(ctx: Context) = read(ctx, "gifts.in")
    @Synchronized private fun updateIn(ctx: Context, gid: String, f: (JSONObject) -> Unit) {
        val l = incoming(ctx); l.firstOrNull { it.optString("gid") == gid }?.let(f); write(ctx, "gifts.in", l)
    }
    @Synchronized private fun updateOut(ctx: Context, gid: String, f: (JSONObject) -> Unit) {
        val l = outgoing(ctx); l.firstOrNull { it.optString("gid") == gid }?.let(f); write(ctx, "gifts.out", l)
    }

    /** new gifts not yet looked at: the number on the friends button */
    fun waiting(ctx: Context) = incoming(ctx).count { it.optString("state") == "new" && it.optLong("exp") > now() }

    // ---------- small helpers ----------

    /** the RomM server as a short hash: equal on both sides = same server (the address itself is not sent) */
    fun rommServer(ctx: Context): String? {
        if (!Romm.status(ctx).optBoolean("connected")) return null
        val u = Romm.url(ctx).ifEmpty { return null }.lowercase()
        return hex(MessageDigest.getInstance("SHA-256").digest("thorbro romm v1 $u".toByteArray())).take(16)
    }

    /** where our web server can be reached: every IPv4 of the interfaces (WLAN, Wi-Fi Direct, NetBird …) */
    private fun lanAddrs(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nif ->
            nif.inetAddresses.toList().filter { it is Inet4Address && !it.isLinkLocalAddress }.map { "${it.hostAddress}:${ThorService.PORT}" }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun sha1(f: File, progress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-1")
        var done = 0L; var last = 0L
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                md.update(buf, 0, n); done += n
                if (now() - last > 300) { last = now(); progress(done) }
            }
        }
        return hex(md.digest())
    }

    private fun gcm(key: ByteArray, encrypt: Boolean, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); doFinal(data) }

    /** ntfy.sh allows 250 posts a day per IP: at most [PER_HOUR] gifts an hour */
    @Synchronized private fun rateOk(ctx: Context, n: Int): Boolean {
        val l = (Prefs.of(ctx).getString("gifts.sent", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now() - it < 3600_000 }
        if (l.size + n > PER_HOUR) return false
        Prefs.of(ctx).edit().putString("gifts.sent", (l + List(n) { now() }).joinToString(",")).apply()
        return true
    }

    private fun kindHas(kind: String, part: Byte) = when (part) { SAVE -> kind == "save" || kind == "both"; else -> kind == "game" || kind == "both" }

    /** a folder game as one zip (stored, not compressed: games barely shrink) */
    private fun packFolder(dir: File, zip: File, job: Jobs.Job?) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val tmp = File(zip.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered(1 shl 20)).use { z ->
            z.setLevel(0)
            files.forEach { f ->
                z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path).apply { time = f.lastModified() })
                f.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n); done += n; job?.progress = 0.4 * done / total } }
                z.closeEntry()
            }
        }
        tmp.renameTo(zip)
    }

    // ---------- sending ----------

    private fun outDir(ctx: Context, gid: String) = File(ctx.filesDir, "gifts/out/$gid")

    /**
     * Sends a save, a game or both to friends. Runs as a job (hashing a big game takes a moment).
     * [kind] = save | game | both
     */
    fun send(ctx: Context, to: List<String>, kind: String, system: String, rom: File, job: Jobs.Job? = null): JSONObject {
        val friends = Pals.friends(ctx).filter { it.optString("id") in to }
        if (friends.isEmpty()) return JSONObject().put("error", "Keinen Freund ausgewählt")
        if (kind !in setOf("save", "game", "both")) return JSONObject().put("error", "Was soll verschickt werden?")
        if (!rateOk(ctx, friends.size)) return JSONObject().put("error", "Höchstens $PER_HOUR Geschenke pro Stunde (ntfy.sh-Grenze) – gleich nochmal")
        val gid = randHex(8)
        val tok = Pals.b64(ByteArray(16).also { rnd.nextBytes(it) })
        val dir = outDir(ctx, gid).apply { mkdirs() }
        val title = rom.nameWithoutExtension
        job?.game(title, system, file = rom.path)
        val msg = JSONObject().put("t", "gift").put("gid", gid).put("kind", kind).put("system", system).put("title", title).put("tok", tok)
            .put("exp", now() + DAYS * 86_400_000)
        val out = JSONObject().put("gid", gid).put("kind", kind).put("system", system).put("title", title).put("rom", rom.path).put("tok", tok)
            .put("created", now()).put("exp", msg.getLong("exp"))
        try {
            if (kindHas(kind, SAVE)) {
                job?.message = "packe den Spielstand"
                val items = GameSave.items(ctx, rom.path)
                if (items.isEmpty()) return JSONObject().put("error", "Auf dem Thor gibt es noch keinen Spielstand für $title")
                val zip = File(dir, "save.zip")
                Profiles.pack(ctx, zip, rom.path, items)
                val newest = items.flatMap { if (it.isFolder) it.file.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it.file) }.maxOfOrNull { it.lastModified() } ?: 0L
                val save = JSONObject().put("size", zip.length()).put("sha1", sha1(zip)).put("time", newest)
                    .put("name", items.joinToString(" + ") { it.file.name }).put("profile", Profiles.get(ctx, Profiles.owner(ctx, rom.path))?.name ?: JSONObject.NULL)
                // small saves also go as an ntfy attachment: they arrive even when we are offline (ntfy keeps it a few hours)
                if (zip.length() <= ATT_MAX && DataSaver.mode(ctx) != DataSaver.Mode.LOCAL) attach(zip)?.let { save.put("att", it) }
                msg.put("save", save)
                out.put("save", zip.path)
            }
            if (kindHas(kind, GAME)) {
                val file: File
                if (rom.isDirectory) {
                    job?.message = "packe den Spielordner"
                    file = File(dir, "${rom.name}.zip"); packFolder(rom, file, job)
                    msg.put("packed", true)
                } else file = rom
                job?.message = "prüfe die Spieldatei"
                val size = file.length()
                msg.put("file", file.name).put("size", size).put("sha1", sha1(file) { d -> job?.progress = 0.4 + 0.5 * d / size.coerceAtLeast(1) })
                out.put("game", file.path)
                val romm = rommServer(ctx)?.takeIf { DataSaver.rommAllowed(ctx) && !rom.isDirectory }?.let { srv ->
                    runCatching { SharedSaves.romId(ctx, system, rom.path) }.getOrNull()?.let { JSONObject().put("srv", srv).put("id", it) }
                }
                msg.put("ways", JSONObject().put("lan", JSONArray(lanAddrs())).put("tunnel", DataSaver.mode(ctx) != DataSaver.Mode.LOCAL).put("romm", romm ?: JSONObject.NULL))
            } else {
                msg.put("file", rom.name)
                msg.put("ways", JSONObject().put("lan", JSONArray(lanAddrs())).put("tunnel", DataSaver.mode(ctx) != DataSaver.Mode.LOCAL))
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        val rec = JSONObject()
        var sent = 0
        friends.forEach { f ->
            val ok = Pals.send(ctx, f.optString("pub"), msg)
            if (ok) sent++
            rec.put(f.optString("id"), JSONObject().put("name", f.optString("name")).put("state", if (ok) "sent" else "queued").put("at", now()))
        }
        out.put("to", rec).put("msg", msg)
        synchronized(this) { write(ctx, "gifts.out", listOf(out) + outgoing(ctx).take(40)) }
        History.add(ctx, "gift_send", title, system, detail = friends.joinToString { it.optString("name") })
        val names = friends.joinToString(", ") { it.optString("name") }
        return if (sent == 0) JSONObject().put("ok", true).put("gid", gid).put("done", "ntfy.sh gerade nicht erreichbar – wird an $names geschickt, sobald es geht")
            else JSONObject().put("ok", true).put("gid", gid).put("done", "An $names geschickt")
    }

    /** encrypts [f] and puts it on ntfy.sh as an attachment; {u, k} or null */
    private fun attach(f: File): JSONObject? = runCatching {
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val body = nonce + gcm(key, true, nonce, f.readBytes())
        val c = URL("https://ntfy.sh/tbf-g-${randHex(12)}").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 60_000
        c.setRequestProperty("Filename", "gift.bin")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val att = j.getJSONObject("attachment")
        JSONObject().put("u", att.getString("url")).put("k", Pals.b64(key)).put("until", att.optLong("expires") * 1000)
    }.onFailure { Logs.i("gifts", "ntfy-Anhang ging nicht: ${it.message}") }.getOrNull()

    /** the file behind /api/gift/<gid>/<part>, when the token fits and the gift is still valid */
    fun file(ctx: Context, gid: String, part: String, tok: String?): File? {
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == gid } ?: return null
        if (tok == null || tok != o.optString("tok") || o.optLong("exp") < now()) return null
        return when (part) { "save" -> o.optString("save"); "game" -> o.optString("game"); else -> "" }.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
    }

    // ---------- the sender's tunnel ----------

    private class Offer(val host: Tunnel.Host, val wire: GiftWire.Server, val secret: ByteArray) { @Volatile var used = now() }
    private val offers = ConcurrentHashMap<String, Offer>()

    /** the receiver wants the tunnel: open one for this gift, punch towards it, answer with secret and addresses */
    private fun onOk(ctx: Context, from: JSONObject, m: JSONObject) {
        if (now() - m.optLong("ts") > 10 * 60_000) return
        val gid = m.optString("gid")
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == gid } ?: return
        if (o.optJSONObject("to")?.has(from.optString("id")) != true || o.optLong("exp") < now()) return
        if (DataSaver.mode(ctx) == DataSaver.Mode.LOCAL) return
        val offer = offers[gid] ?: synchronized(offers) {
            offers[gid] ?: run {
                val parts = HashMap<Byte, File>()
                o.optString("save").takeIf { it.isNotEmpty() }?.let { parts[SAVE] = File(it) }
                o.optString("game").takeIf { it.isNotEmpty() }?.let { parts[GAME] = File(it) }
                val wire = GiftWire.Server(parts)
                val secret = Tunnel.newSecret()
                Offer(Tunnel.Host(secret, setOf(wire.port), maxGuests = 4), wire, secret).also { offers[gid] = it }
            }
        }
        offer.used = now()
        val c = m.optJSONArray("c") ?: JSONArray()
        val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }
        Logs.i("gifts", "${from.optString("name").take(24)} holt ${o.optString("title")} über den Tunnel (${cands.size} Adressen)")
        offer.host.punch(cands)
        offer.host.refreshPublic(); Thread.sleep(600)
        Pals.send(ctx, from.optString("pub"), JSONObject().put("t", "gift-net").put("gid", gid).put("s", Pals.b64(offer.secret))
            .put("c", JSONArray(offer.host.candidates)).put("port", offer.wire.port).put("lan", JSONArray(lanAddrs())))
        updateOut(ctx, gid) { it.optJSONObject("to")?.optJSONObject(from.optString("id"))?.put("state", "loading") }
    }

    private fun closeOffer(gid: String) { offers.remove(gid)?.let { it.host.close(); it.wire.close() } }

    private fun onNo(ctx: Context, from: JSONObject, m: JSONObject) = finishFor(ctx, m.optString("gid"), from, "declined")
    private fun onDone(ctx: Context, from: JSONObject, m: JSONObject) {
        finishFor(ctx, m.optString("gid"), from, "done")
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == m.optString("gid") } ?: return
        Pals.notify(ctx, "${from.optString("name").take(24)} hat ${o.optString("title")} angenommen", "")
    }
    private fun onWant(ctx: Context, from: JSONObject, m: JSONObject) {
        updateOut(ctx, m.optString("gid")) { it.optJSONObject("to")?.optJSONObject(from.optString("id"))?.put("want", true) }
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == m.optString("gid") } ?: return
        Pals.notify(ctx, "${from.optString("name").take(24)} möchte auch das Spiel", "${o.optString("title")} – im Freunde-Menü schicken")
    }

    private fun finishFor(ctx: Context, gid: String, from: JSONObject, state: String) {
        updateOut(ctx, gid) { it.optJSONObject("to")?.optJSONObject(from.optString("id"))?.put("state", state)?.put("at", now()) }
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == gid } ?: return
        val to = o.optJSONObject("to") ?: return
        if (to.keys().asSequence().all { to.getJSONObject(it).optString("state") in setOf("done", "declined") }) closeOffer(gid)
    }

    // ---------- receiving ----------

    private fun onGift(ctx: Context, from: JSONObject, m: JSONObject) {
        val gid = m.optString("gid").takeIf { it.matches(Regex("^[0-9a-f]{16}$")) } ?: return
        if (m.optLong("exp") < now()) return
        val known = incoming(ctx).firstOrNull { it.optString("gid") == gid }
        if (known != null) { // sent again (ntfy only keeps 12 h): fresher addresses, nothing else
            updateIn(ctx, gid) { it.put("msg", m) }
            return
        }
        val e = JSONObject().put("gid", gid).put("from", JSONObject().put("id", from.optString("id")).put("name", from.optString("name").take(24)).put("pub", from.optString("pub")))
            .put("msg", m).put("state", "new").put("at", now()).put("exp", m.optLong("exp"))
        synchronized(this) { write(ctx, "gifts.in", listOf(e) + incoming(ctx).filter { it.optLong("exp") > now() - 86_400_000 }.take(40)) }
        val what = when (m.optString("kind")) { "save" -> "einen Spielstand"; "both" -> "ein Spiel mit Spielstand"; else -> "ein Spiel" }
        Pals.notify(ctx, "${from.optString("name").take(24)} schickt dir $what", "${m.optString("title")} – im Freunde-Menü unter „Für dich“")
        Logs.i("gifts", "Geschenk von ${from.optString("name").take(24)}: ${m.optString("title")} (${m.optString("kind")})")
        // saves from friends kept as a backup without asking (own save stays live), if wanted
        if (m.optString("kind") == "save" && DataSaver.giftAuto(ctx) && DataSaver.mode(ctx) != DataSaver.Mode.MANUAL)
            accept(ctx, gid, "backup")
    }

    /** tunnel answers wait here for the download that asked */
    private val nets = ConcurrentHashMap<String, LinkedBlockingQueue<JSONObject>>()
    private fun onNet(ctx: Context, from: JSONObject, m: JSONObject) {
        val e = incoming(ctx).firstOrNull { it.optString("gid") == m.optString("gid") } ?: return
        if (e.optJSONObject("from")?.optString("id") != from.optString("id")) return
        nets[m.optString("gid")]?.offer(m)
    }

    /** live download state for the menu (not stored) */
    private class Live(@Volatile var done: Long, @Volatile var total: Long, @Volatile var way: String, @Volatile var job: Int)
    private val live = ConcurrentHashMap<String, Live>()

    fun decline(ctx: Context, gid: String) {
        val e = incoming(ctx).firstOrNull { it.optString("gid") == gid } ?: return
        updateIn(ctx, gid) { it.put("state", "declined") }
        thread(isDaemon = true) { Pals.send(ctx, e.getJSONObject("from").optString("pub"), JSONObject().put("t", "gift-no").put("gid", gid)) }
    }

    /** "Spiel fehlt": asks the sender for the game too */
    fun askGame(ctx: Context, gid: String): String? {
        val e = incoming(ctx).firstOrNull { it.optString("gid") == gid } ?: return "Geschenk ist weg"
        updateIn(ctx, gid) { it.put("asked", true) }
        return if (Pals.send(ctx, e.getJSONObject("from").optString("pub"), JSONObject().put("t", "gift-want").put("gid", gid))) null else "ntfy.sh nicht erreichbar"
    }

    /** sender side: the friend asked for the game of a save gift → send it */
    fun sendWanted(ctx: Context, gid: String, friendId: String, job: Jobs.Job): JSONObject {
        val o = outgoing(ctx).firstOrNull { it.optString("gid") == gid } ?: return JSONObject().put("error", "Geschenk ist weg")
        val rom = File(o.optString("rom")).takeIf { it.exists() } ?: return JSONObject().put("error", "Das Spiel ist nicht mehr auf dem Thor")
        updateOut(ctx, gid) { it.optJSONObject("to")?.optJSONObject(friendId)?.put("want", false) }
        return send(ctx, listOf(friendId), "game", o.optString("system"), rom, job)
    }

    /**
     * Takes a gift: [how] = take (save becomes live) | backup (only kept as a backup) | load (game, or game + save).
     * Runs as a job; the bubble top right shows it.
     */
    fun accept(ctx: Context, gid: String, how: String): Jobs.Job {
        val e = incoming(ctx).firstOrNull { it.optString("gid") == gid }
        val m = e?.optJSONObject("msg") ?: JSONObject()
        val lv = Live(0, 0, "", 0).also { live[gid] = it }
        return Jobs.start("Geschenk: ${m.optString("title")}") { j ->
            lv.job = j.id
            if (e == null) error("Geschenk ist weg")
            updateIn(ctx, gid) { it.put("state", "loading").remove("error") }
            try { receive(ctx, e, how, j).also { r -> updateIn(ctx, gid) { it.put("state", "done").put("result", r) } } }
            catch (x: Exception) {
                val wifi = x is WaitWifi
                updateIn(ctx, gid) { it.put("state", if (wifi) "wifi" else "failed").put("error", x.message) }
                throw x
            } finally { live.remove(gid) }
        }.also { lv.job = it.id }
    }

    private class WaitWifi(msg: String) : Exception(msg)

    private fun receive(ctx: Context, e: JSONObject, how: String, j: Jobs.Job): JSONObject {
        val gid = e.getString("gid")
        val m = e.getJSONObject("msg")
        val from = e.getJSONObject("from")
        val kind = m.optString("kind")
        val system = m.optString("system")
        val title = m.optString("title")
        j.game(title, system)
        val out = JSONObject()
        var rom: File? = null
        if (kindHas(kind, GAME)) {
            val dir = File(Library.romsRoot(ctx), system).apply { mkdirs() }
            val target = File(dir, File(m.optString("file")).name)
            val size = m.optLong("size")
            if (!m.optBoolean("packed") && target.isFile && target.length() == size) {
                j.message = "Spiel ist schon da"
            } else {
                fetch(ctx, e, GAME, target, size, m.optString("sha1"), j)
            }
            rom = target
            if (m.optBoolean("packed")) {
                j.message = "entpacke $title"
                Archives.extract(target, dir, deleteAfter = true, job = j)
                rom = File(dir, target.nameWithoutExtension)
            }
            Roms.invalidate()
            markNew(ctx, rom.path, from.optString("name"))
            out.put("game", rom.path)
            History.add(ctx, "gift_game", title, system, detail = "von ${from.optString("name")}")
        }
        if (kindHas(kind, SAVE)) {
            val save = m.getJSONObject("save")
            val zip = File(ctx.cacheDir, "gift-$gid-save.zip")
            try {
                val att = save.optJSONObject("att")
                val viaAtt = att != null && DataSaver.mode(ctx) != DataSaver.Mode.LOCAL && runCatching {
                    j.message = "lade den Spielstand"
                    val body = URL(att.getString("u")).openStream().use { it.readBytes() }
                    val key = Pals.unb64(att.getString("k"))
                    zip.writeBytes(gcm(key, false, body.copyOfRange(0, 12), body.copyOfRange(12, body.size)))
                    sha1(zip) == save.optString("sha1")
                }.getOrDefault(false)
                if (!viaAtt) fetch(ctx, e, SAVE, zip, save.optLong("size"), save.optString("sha1"), j)
                val r = rom ?: Netplay.findRom(ctx, name = m.optString("file"))?.second
                    ?: run {
                        updateIn(ctx, gid) { it.put("missing", true) }
                        throw IllegalStateException("Spiel fehlt: $title ist nicht auf dem Thor")
                    }
                val mode = when {
                    how == "take" -> "take"
                    how == "backup" -> "backup"
                    GameSave.items(ctx, r.path).isEmpty() -> "take" // a new game: its save goes in right away
                    else -> "backup"
                }
                out.put("save", putSave(ctx, system, r, zip, from.optString("name"), mode))
            } finally { zip.delete() }
        }
        thread(isDaemon = true) { Pals.send(ctx, from.optString("pub"), JSONObject().put("t", "gift-done").put("gid", gid)) }
        Pals.notify(ctx, "$title ist da", "von ${from.optString("name")}")
        return out.put("done", "$title ist da")
    }

    /** games that came as a gift, for the "neu · von …" chip: path → sender */
    private fun markNew(ctx: Context, path: String, from: String) {
        val o = runCatching { JSONObject(Prefs.of(ctx).getString("gifts.new", "{}")) }.getOrDefault(JSONObject())
        o.put(path, JSONObject().put("from", from).put("at", now()))
        Prefs.of(ctx).edit().putString("gifts.new", o.toString()).apply()
    }
    fun newFrom(ctx: Context, path: String): String? = runCatching { JSONObject(Prefs.of(ctx).getString("gifts.new", "{}")).optJSONObject(path) }.getOrNull()
        ?.takeIf { now() - it.optLong("at") < 14 * 86_400_000L }?.optString("from")

    // ---------- the ways ----------

    private interface Way {
        val id: String
        /** bytes per second for a short piece from [from], or throws */
        fun probe(from: Long, end: Long): Double
        /** loads into [part] from [from]; returns the first byte still missing */
        fun load(part: File, from: Long, end: Long, progress: (Long) -> Unit): Long
    }

    private fun copyInto(part: File, from: Long, c: HttpURLConnection, end: Long, progress: (Long) -> Unit): Long {
        var at = if (c.responseCode == 206) from else 0L
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(at)
            c.inputStream.use { i ->
                val buf = ByteArray(1 shl 18); var last = 0L
                while (at < end) {
                    val n = i.read(buf, 0, minOf(buf.size.toLong(), end - at).toInt()); if (n < 0) break
                    raf.write(buf, 0, n); at += n
                    if (now() - last > 250) { last = now(); progress(at) }
                }
            }
        }
        progress(at)
        return at
    }

    private fun timed(bytes: Long, work: () -> Long): Double { val t = System.nanoTime(); val n = work(); return n * 1e9 / (System.nanoTime() - t).coerceAtLeast(1) * (if (n >= bytes / 2) 1.0 else 0.0) }

    private fun drain(c: HttpURLConnection, max: Long): Long {
        var n = 0L
        c.inputStream.use { i -> val buf = ByteArray(1 shl 16); while (n < max) { val r = i.read(buf); if (r < 0) break; n += r } }
        c.disconnect(); return n
    }

    private class Lan(val addrs: List<String>, val gid: String, val part: String, val tok: String) : Way {
        override val id = "lan"
        @Volatile private var good: String? = null
        private fun conn(a: String, from: Long, to: Long?): HttpURLConnection =
            (URL("http://$a/api/gift/$gid/$part?k=${java.net.URLEncoder.encode(tok, "UTF-8")}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500; readTimeout = 20_000
                setRequestProperty("Range", "bytes=$from-" + (to?.toString() ?: ""))
            }
        private fun open(from: Long, to: Long?): HttpURLConnection {
            (listOfNotNull(good) + addrs).distinct().forEach { a ->
                val c = runCatching { conn(a, from, to).also { if (it.responseCode !in listOf(200, 206)) error("HTTP ${it.responseCode}") } }.getOrNull()
                if (c != null) { good = a; return c }
            }
            error("Thor des Freundes nicht im selben Netz")
        }
        override fun probe(from: Long, end: Long): Double { val to = minOf(end, from + PROBE) - 1; return timed(to - from + 1) { drain(open(from, to), to - from + 1) } }
        override fun load(part: File, from: Long, end: Long, progress: (Long) -> Unit) = copyInto(part, from, open(from, null), end, progress)
    }

    private class RommWay(val ctx: Context, val romId: Int) : Way {
        override val id = "romm"
        private val name: String by lazy {
            val r = Romm.romInfo(ctx, romId)
            if (r.optBoolean("has_multiple_files")) error("liegt in RomM als mehrere Dateien")
            r.getString("fs_name")
        }
        override fun probe(from: Long, end: Long): Double { val to = minOf(end, from + PROBE) - 1; return timed(to - from + 1) { drain(Romm.content(ctx, romId, name, from, to), to - from + 1) } }
        override fun load(part: File, from: Long, end: Long, progress: (Long) -> Unit) = copyInto(part, from, Romm.content(ctx, romId, name, from, null), end, progress)
    }

    private class TunnelWay(val ctx: Context, val e: JSONObject, val part: Byte) : Way {
        override val id = "tunnel"
        private var guest: Tunnel.Guest? = null
        private var port = 0
        private val inbox = LinkedBlockingQueue<ByteArray>()
        private var session: Tunnel.Guest.Session? = null

        private fun connect(): Tunnel.Guest.Session {
            session?.let { if (guest?.connected() == true) return it }
            val gid = e.getString("gid")
            val from = e.getJSONObject("from")
            val q = LinkedBlockingQueue<JSONObject>()
            nets[gid] = q
            try {
                val (sock, mine) = Tunnel.socket()
                if (!Pals.send(ctx, from.getString("pub"), JSONObject().put("t", "gift-ok").put("gid", gid).put("c", JSONArray(mine)))) { sock.close(); error("ntfy.sh nicht erreichbar") }
                val net = q.poll(40, java.util.concurrent.TimeUnit.SECONDS) ?: run { sock.close(); error("Freund antwortet nicht (offline?)") }
                val c = net.optJSONArray("c") ?: JSONArray()
                val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }.filter { Tunnel.parse(it) != null }
                guest?.close()
                val g = Tunnel.Guest(Pals.unb64(net.getString("s")), cands, socket = sock, known = mine)
                guest = g; port = net.optInt("port")
                if (!g.await(12_000, port)) { g.close(); guest = null; error("Tunnel kommt nicht zustande") }
                return g.open(port) { inbox.add(it) }.also { session = it }
            } finally { nets.remove(gid) }
        }
        override fun probe(from: Long, end: Long): Double {
            val tmp = File.createTempFile("gift-probe", ".bin", ctx.cacheDir)
            try {
                val to = minOf(end, from + PROBE)
                val t = System.nanoTime()
                val s = connect()
                val n = GiftWire.fetch(part, tmp, from, to, inbox, { b -> s.send(b, 0, b.size) }, idleMs = 8000) - from
                return if (n >= (to - from) / 2) n * 1e9 / (System.nanoTime() - t) else 0.0
            } finally { tmp.delete() }
        }
        override fun load(part: File, from: Long, end: Long, progress: (Long) -> Unit): Long {
            val s = connect()
            inbox.clear()
            return GiftWire.fetch(this.part, part, from, end, inbox, { b -> s.send(b, 0, b.size) }, progress)
        }
        fun close() { guest?.close(); guest = null }
    }

    private val wayNames = mapOf("lan" to "lokal", "romm" to "RomM", "tunnel" to "Tunnel")

    /** loads one part of a gift into [target]: probe the allowed ways, fastest first, carry on where one broke off */
    private fun fetch(ctx: Context, e: JSONObject, part: Byte, target: File, size: Long, sha: String, j: Jobs.Job) {
        val m = e.getJSONObject("msg")
        val gid = e.getString("gid")
        val partName = if (part == SAVE) "save" else "game"
        val ways = m.optJSONObject("ways") ?: JSONObject()
        val mode = DataSaver.mode(ctx)
        val wifi = SaveWatcher.onWifi(ctx)
        if (part == GAME && !wifi && mode == DataSaver.Mode.MOBILE && size > DataSaver.giftMaxMobile(ctx))
            throw WaitWifi("Wartet auf WLAN (größer als ${DataSaver.giftMaxMobile(ctx) shr 20} MB über Mobilfunk)")
        val list = mutableListOf<Way>()
        val lan = ways.optJSONArray("lan")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (lan.isNotEmpty()) list += Lan(lan, gid, partName, m.optString("tok"))
        val romm = ways.optJSONObject("romm")
        if (part == GAME && romm != null && DataSaver.rommAllowed(ctx) && romm.optString("srv") == rommServer(ctx)) list += RommWay(ctx, romm.optInt("id"))
        val tunnel = if (mode != DataSaver.Mode.LOCAL && ways.optBoolean("tunnel", true)) TunnelWay(ctx, e, part).also { list += it } else null
        if (list.isEmpty()) error("Kein erlaubter Weg (Daten sparen: ${mode.label})")

        val partFile = File(target.path + ".thorbro-part")
        // the first missing byte is kept in the gift (the tunnel writes out of order, so the file length is no guide)
        val haveKey = "have.$partName"
        if (!partFile.isFile) updateIn(ctx, gid) { it.remove(haveKey) }
        var have = minOf(partFile.length(), incoming(ctx).firstOrNull { it.optString("gid") == gid }?.optLong(haveKey) ?: 0L, size)
        var reached = have
        val lv = live[gid]
        var saved = now()
        val progress: (Long) -> Unit = { at ->
            reached = maxOf(reached, at)
            j.bytes(at, size); j.gameBytes(at, size); j.progress = at.toDouble() / size.coerceAtLeast(1)
            lv?.done = at; lv?.total = size
            if (now() - saved > 5000) { saved = now(); updateIn(ctx, gid) { it.put(haveKey, reached) } }
        }
        Transfers.begin(ctx)
        try {
            // the fastest way first: a short piece over each, at the same time
            val order: List<Way> = if (list.size > 1 && size - have > 2 * PROBE) {
                j.message = "teste ${list.joinToString(", ") { wayNames[it.id] ?: it.id }} …"
                val speeds = ConcurrentHashMap<String, Double>()
                list.map { w -> thread(isDaemon = true) { speeds[w.id] = runCatching { w.probe(have, size) }.onFailure { Logs.i("gifts", "${w.id}: ${it.message}") }.getOrDefault(0.0) } }
                    .forEach { it.join(45_000) }
                updateIn(ctx, gid) { it.put("probe", JSONObject(speeds.mapValues { (_, v) -> "%.1f".format(v / 1e6) } as Map<*, *>)) }
                Logs.i("gifts", "Wege für ${m.optString("title")}: ${speeds.entries.joinToString { "${it.key} ${"%.1f".format(it.value / 1e6)} MB/s" }}")
                list.sortedByDescending { speeds[it.id] ?: 0.0 }.filter { (speeds[it.id] ?: 0.0) > 0 } .ifEmpty { list }
            } else list
            for (w in order) {
                if (have >= size) break
                j.message = "über ${wayNames[w.id]} · von ${e.getJSONObject("from").optString("name")}"
                lv?.way = w.id
                runCatching { w.load(partFile, have, size, progress) }.onFailure { Logs.i("gifts", "${w.id} abgebrochen: ${it.message}") }
                    .onSuccess { reached = maxOf(reached, it) }
                have = reached
            }
        } finally { Transfers.end(); tunnel?.close(); updateIn(ctx, gid) { it.put(haveKey, reached) } }
        if (have < size) error("Kein Weg hat geklappt – später nochmal (bisher ${have * 100 / size.coerceAtLeast(1)} %)")
        RandomAccessFile(partFile, "rw").use { it.setLength(size) }
        j.message = "prüfe …"
        if (sha.isNotEmpty() && sha1(partFile) != sha) { partFile.delete(); error("Datei kam kaputt an (Prüfsumme) – nochmal versuchen") }
        if (target.exists()) target.delete()
        if (!partFile.renameTo(target)) error("${target.name} nicht schreibbar")
    }

    // ---------- a received save ----------

    /**
     * The sender's save pack names the sender's paths; this rewrites it onto this Thor's own save of the game
     * (the existing one, else where the emulator will look), then keeps it as a backup "Geschenk von …" and —
     * for [mode] take — puts it in (the current save is packed first).
     */
    private fun putSave(ctx: Context, system: String, rom: File, zip: File, fromName: String, mode: String): String {
        val mine = GameSave.items(ctx, rom.path)
        val man = ZipInputStream(zip.inputStream().buffered()).use { z ->
            var en = z.nextEntry
            while (en != null && en.name != "manifest.json") en = z.nextEntry
            if (en == null) error("Spielstand-Paket ohne Inhaltsverzeichnis")
            JSONObject(z.readBytes().decodeToString()).getJSONArray("items")
        }
        val items = JSONArray()
        for (k in 0 until man.length()) {
            val it = man.getJSONObject(k)
            val p = File(it.getString("path"))
            val folder = it.getBoolean("folder")
            val t = if (folder) mine.firstOrNull { s -> s.isFolder }?.file ?: (if (system == "n3ds") N3ds.saveDir(rom, create = true) else null)
                ?: error("Erst einmal ${rom.nameWithoutExtension} starten und speichern, dann übernehmen")
            else mine.firstOrNull { s -> !s.isFolder && s.file.extension.equals(p.extension, true) }?.file ?: mine.singleOrNull { s -> !s.isFolder }?.file
                ?: SharedSaves.defaultTarget(system, rom, p.name) ?: error("Weiß nicht, wohin der Spielstand gehört – einmal im Spiel speichern")
            items.put(JSONObject().put("i", it.getInt("i")).put("path", t.path).put("folder", folder))
        }
        val mapped = File(zip.path + ".mine")
        ZipOutputStream(mapped.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json")); out.write(JSONObject().put("rom", rom.path).put("items", items).toString().toByteArray()); out.closeEntry()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val en = z.nextEntry ?: break
                    if (en.isDirectory || en.name == "manifest.json") continue
                    out.putNextEntry(ZipEntry(en.name).apply { time = en.time }); z.copyTo(out); out.closeEntry()
                }
            }
        }
        val kept = GameSave.addGift(ctx, rom.path, mapped, fromName)
        mapped.delete()
        if (mode == "take") {
            val r = GameSave.restore(ctx, rom.path, kept.path)
            if (r.has("error")) error(r.getString("error"))
            return "übernommen"
        }
        return "als Backup abgelegt"
    }

    // ---------- status for the menus ----------

    fun status(ctx: Context): JSONObject {
        val myRomm = rommServer(ctx)
        val inc = incoming(ctx).filter { it.optString("state") != "declined" && it.optLong("exp") > now() - 86_400_000 }.map { e ->
            val m = e.getJSONObject("msg")
            val ways = m.optJSONObject("ways") ?: JSONObject()
            val save = m.optJSONObject("save")
            JSONObject().put("gid", e.optString("gid")).put("from", JSONObject().put("id", e.getJSONObject("from").optString("id")).put("name", e.getJSONObject("from").optString("name")))
                .put("kind", m.optString("kind")).put("system", m.optString("system")).put("title", m.optString("title")).put("file", m.optString("file"))
                .put("size", m.optLong("size")).put("save", save?.let { JSONObject().put("size", it.optLong("size")).put("time", it.optLong("time")).put("name", it.optString("name")) } ?: JSONObject.NULL)
                .put("state", if (e.optLong("exp") < now() && e.optString("state") == "new") "expired" else e.optString("state"))
                .put("error", e.optString("error").ifEmpty { null } ?: JSONObject.NULL).put("at", e.optLong("at")).put("missing", e.optBoolean("missing")).put("asked", e.optBoolean("asked"))
                .put("ways", JSONObject().put("lan", (ways.optJSONArray("lan")?.length() ?: 0) > 0)
                    .put("romm", ways.optJSONObject("romm")?.optString("srv")?.let { it == myRomm } == true)
                    .put("tunnel", ways.optBoolean("tunnel", true)))
                .put("probe", e.optJSONObject("probe") ?: JSONObject.NULL)
                .put("live", live[e.optString("gid")]?.let { l -> JSONObject().put("done", l.done).put("total", l.total).put("way", l.way).put("job", l.job) } ?: JSONObject.NULL)
                .put("result", e.optJSONObject("result") ?: JSONObject.NULL)
        }
        val out = outgoing(ctx).filter { it.optLong("exp") > now() }.map { o ->
            val to = o.optJSONObject("to") ?: JSONObject()
            JSONObject().put("gid", o.optString("gid")).put("kind", o.optString("kind")).put("title", o.optString("title")).put("system", o.optString("system"))
                .put("at", o.optLong("created")).put("exp", o.optLong("exp"))
                .put("to", JSONArray(to.keys().asSequence().map { id -> to.getJSONObject(id).let { JSONObject(it.toString()).put("id", id) } }.toList()))
        }
        return JSONObject().put("incoming", JSONArray(inc)).put("outgoing", JSONArray(out)).put("waiting", waiting(ctx))
    }

    /**
     * For the send menu: per friend the way a gift will probably take and how long it may need. The receiver
     * decides at the end (it tests the ways), this is only the guess: same network (seen nearby), same RomM
     * server (from the friend's status) with the game in RomM, the tunnel when online, else waiting.
     */
    fun routes(ctx: Context, system: String, rom: File?, kind: String): JSONObject {
        val size = if (kindHas(kind, GAME) && rom != null) (if (rom.isDirectory) rom.walkTopDown().filter { it.isFile }.sumOf { it.length() } else rom.length()) else 0L
        val saveOnly = kind == "save"
        val nearIds = runCatching { Netplay.peers(ctx).let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("lobby")?.optJSONObject("card")?.optString("id") } } }
            .getOrDefault(emptyList()).toSet()
        val myRomm = rommServer(ctx)
        val inRomm = !saveOnly && rom != null && myRomm != null && runCatching { SharedSaves.romId(ctx, system, rom.path) != null }.getOrDefault(false)
        fun eta(bps: Double) = if (size <= 0) "sofort" else "~" + Jobs.dur((size / bps).toLong().coerceAtLeast(1))
        val l = Pals.friends(ctx).map { f ->
            val id = f.optString("id")
            val st = Pals.presenceOf(ctx, id)
            val online = Pals.online(ctx, id)
            val (way, label) = when {
                saveOnly -> "ntfy" to (if (online) "kommt sofort an" else "liegt im Postfach")
                id in nearIds -> "lan" to "lokal · ${eta(30e6)}"
                inRomm && st?.optString("rs") == myRomm -> "romm" to "RomM · ${eta(10e6)}"
                online -> "tunnel" to "Tunnel · ${eta(3e6)}"
                else -> "wait" to "wartet $DAYS Tage"
            }
            JSONObject().put("id", id).put("name", f.optString("name")).put("online", online).put("near", id in nearIds)
                .put("seen", maxOf(f.optLong("seen"), st?.optLong("ts") ?: 0)).put("way", way).put("label", label)
        }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("near") }.thenByDescending { it.optBoolean("online") }.thenByDescending { it.optLong("seen") })
        return JSONObject().put("friends", JSONArray(l)).put("size", size).put("inRomm", inRomm)
    }

    // ---------- upkeep ----------

    /** a friend came online: gifts older than ntfy's 12 h go to them again */
    private fun onStatus(ctx: Context, friend: JSONObject, st: JSONObject) {
        if (!st.optBoolean("on")) return
        resend(ctx, friend.optString("id"))
    }

    @Synchronized
    private fun resend(ctx: Context, only: String? = null) {
        val l = outgoing(ctx)
        var changed = false
        l.filter { it.optLong("exp") > now() }.forEach { o ->
            val to = o.optJSONObject("to") ?: return@forEach
            to.keys().asSequence().toList().forEach { id ->
                if (only != null && id != only) return@forEach
                val r = to.getJSONObject(id)
                val stale = r.optString("state") == "queued" || (r.optString("state") == "sent" && now() - r.optLong("at") > 11 * 3600_000L)
                if (!stale || !Pals.online(ctx, id) && r.optString("state") != "queued") return@forEach
                val f = Pals.friends(ctx).firstOrNull { it.optString("id") == id } ?: return@forEach
                val msg = o.optJSONObject("msg") ?: return@forEach
                msg.optJSONObject("ways")?.put("lan", JSONArray(lanAddrs()))
                if (Pals.send(ctx, f.optString("pub"), msg)) { r.put("state", "sent").put("at", now()); changed = true }
            }
        }
        if (changed) write(ctx, "gifts.out", l)
    }

    /** expired gifts: files and tunnels away */
    private fun sweep(ctx: Context) {
        val (keep, gone) = outgoing(ctx).partition { it.optLong("exp") > now() }
        gone.forEach { o -> closeOffer(o.optString("gid")); outDir(ctx, o.optString("gid")).deleteRecursively() }
        if (gone.isNotEmpty()) synchronized(this) { write(ctx, "gifts.out", keep) }
        offers.entries.filter { now() - it.value.used > 2 * 3600_000L }.forEach { closeOffer(it.key) }
        // left-overs (not while a gift is still being packed: those are younger than an hour)
        File(ctx.filesDir, "gifts/out").listFiles()?.filter { d -> keep.none { it.optString("gid") == d.name } && now() - d.lastModified() > 3600_000 }?.forEach { it.deleteRecursively() }
    }

    @Volatile private var started = false
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Pals.handlers["gift"] = ::onGift
        Pals.handlers["gift-ok"] = ::onOk
        Pals.handlers["gift-net"] = ::onNet
        Pals.handlers["gift-no"] = ::onNo
        Pals.handlers["gift-done"] = ::onDone
        Pals.handlers["gift-want"] = ::onWant
        Pals.onStatus += ::onStatus
        // a download that was running when ThorBro stopped is offered again
        synchronized(this) { incoming(app).let { l -> l.filter { it.optString("state") == "loading" }.forEach { it.put("state", "failed").put("error", "unterbrochen – nochmal annehmen setzt fort") }; write(app, "gifts.in", l) } }
        thread(name = "gifts-upkeep", isDaemon = true) {
            while (true) {
                Thread.sleep(10 * 60_000)
                runCatching { resend(app); sweep(app) }.onFailure { Logs.i("gifts", "Pflege: ${it.message}") }
            }
        }
    }
}
