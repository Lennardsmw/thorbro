package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 3DS online play between Thors, without a game server and without a VPN app.
 *
 * The host's room ([Room]) stays as it is; [Tunnel.Host] lets guests from the internet into it. The guest's ThorBro
 * opens a [Tunnel.Guest], and [Relay] puts it behind 127.0.0.1 — Azahar on both Thors always joins 127.0.0.1.
 *
 * Finding each other: the host shows a short button code (8 presses like ↓ ↓ A B X X → Y), the guest presses it.
 * The code only lets the two Thors meet for a key exchange (ECDH) — the room key itself never depends on it, so a
 * code seen over a shoulder or guessed later is worthless. Codes live 15 minutes. After that the guest knows the
 * host ("Wieder zu …") and needs no code any more.
 *
 * Messages between the Thors go through public message services (ntfy.sh for now), always encrypted, under topics
 * derived from secrets — the service sees neither addresses nor names. No game data goes through them, and none of
 * it is the author's server: the app works the same for everyone who builds it.
 */
object Online {
    const val TUNNEL_PORT = 24873
    private const val NTFY = "https://ntfy.sh"
    private const val CODE_LEN = 8
    private const val CODE_MINUTES = 15L
    /** what the code is made of; as digits 1–8 in this order (for keyboards and the web panel) */
    val SYMBOLS = listOf("↑", "↓", "←", "→", "A", "B", "X", "Y")

    @Volatile var host: Tunnel.Host? = null
        private set
    @Volatile private var secret: ByteArray? = null
    @Volatile private var listening: ByteArray? = null
    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L
    @Volatile private var appCtx: Context? = null
    private val rnd = SecureRandom()

    init { Tunnel.log = { Logs.i("online", it) } }

    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)
    private fun hmac(key: ByteArray, label: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(label.toByteArray()) }
    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString("") { "%02x".format(it) }

    class Invite(val name: String, val secret: ByteArray, val candidates: List<String>)

    // ---------- codes ----------

    /** "47120563" (digits 0–7) → "A ↓ ↑ ← …" */
    fun codeText(c: String) = c.map { SYMBOLS[it - '0'] }.joinToString(" ")
    fun codeDigits(c: String) = c.map { (it - '0' + 1).toString() }.joinToString("")

    /** whatever the guest typed — digits 1–8, letters U D L R A B X Y, or arrows — as the internal form, or null */
    fun normalize(input: String): String? {
        val out = StringBuilder()
        for (ch in input.uppercase()) {
            val i = when (ch) {
                in '1'..'8' -> ch - '1'
                '↑', 'U' -> 0; '↓', 'D' -> 1; '←', 'L' -> 2; '→', 'R' -> 3
                'A' -> 4; 'B' -> 5; 'X' -> 6; 'Y' -> 7
                else -> -1
            }
            if (i >= 0) out.append(i)
        }
        return out.toString().takeIf { it.length == CODE_LEN }
    }

    /** slow on purpose: someone who records the traffic has to pay for every guess */
    private fun codeKey(c: String): ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(c.toCharArray(), "thorbro code v1".toByteArray(), 100_000, 256)).encoded

    // ---------- signalling (ntfy.sh) ----------

    private class Channel(val topic: String, val key: SecretKeySpec)

    private fun roomChannel(s: ByteArray) = Channel("tb-" + hex(hmac(s, "thorbro signal topic"), 12), SecretKeySpec(hmac(s, "thorbro signal key"), "AES"))
    private fun codeChannel(c: String) = codeKey(c).let { k -> Channel("tb-c-" + hex(hmac(k, "topic"), 12), SecretKeySpec(hmac(k, "key"), "AES")) }

    private fun publish(ch: Channel, msg: JSONObject) = runCatching {
        val body = b64(Tunnel.seal(ch.key, 0, 0, System.currentTimeMillis(), msg.toString().toByteArray()))
        (URL("$NTFY/${ch.topic}").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Cache", "no") // nothing stays on the server
            outputStream.use { it.write(body.toByteArray()) }
            responseCode.also { disconnect() }
        }
    }.onFailure { Logs.i("online", "ntfy nicht erreichbar: ${it.message}") }

    internal fun publish(secret: ByteArray, msg: JSONObject) = publish(roomChannel(secret), msg)

    /** streams the topic until [alive] says stop; calls [on] with every message we can decrypt */
    private fun subscribe(ch: Channel, alive: () -> Boolean, on: (JSONObject) -> Unit) = thread(name = "online-signal", isDaemon = true) {
        while (alive()) {
            runCatching {
                (URL("$NTFY/${ch.topic}/json").openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 90_000 // ntfy sends a keepalive every ~45 s
                    inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!alive()) break
                            val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (ev.optString("event") != "message") continue
                            val raw = runCatching { unb64(ev.optString("message")) }.getOrNull() ?: continue
                            val m = Tunnel.open(ch.key, raw, 0, raw.size) ?: continue
                            runCatching { on(JSONObject(String(m.data))) }
                        }
                    }
                    disconnect()
                }
            }
            if (alive()) Thread.sleep(3000)
        }
    }

    internal fun subscribe(secret: ByteArray, alive: () -> Boolean, on: (JSONObject) -> Unit) = subscribe(roomChannel(secret), alive, on)

    // ---------- key exchange ----------

    private fun ecdhPair() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun boxKey(mine: java.security.KeyPair, theirs: String): SecretKeySpec {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(theirs)))
        val shared = KeyAgreement.getInstance("ECDH").run { init(mine.private); doPhase(pub, true); generateSecret() }
        return SecretKeySpec(hmac(shared, "thorbro code box"), "AES")
    }

    // ---------- host ----------

    /** the room key: stays across restarts, so friends who joined once can come back without a code */
    private fun roomSecret(ctx: Context): ByteArray {
        val p = Prefs.of(ctx)
        p.getString("online.secret", null)?.let { runCatching { unb64(it) }.getOrNull()?.takeIf { it.size == 16 }?.let { s -> return s } }
        return Tunnel.newSecret().also { p.edit().putString("online.secret", b64(it)).apply() }
    }

    /** opens the room to the internet (starting the game server if needed) with a fresh code for 15 minutes */
    fun startHost(ctx: Context): JSONObject {
        if (!Netplay.serverOn) {
            Netplay.startServer(ctx).optString("roomError").ifEmpty { null }?.let { return JSONObject().put("error", it) }
        }
        val r = open(ctx)
        if (r.has("error")) return r
        if (code == null || System.currentTimeMillis() >= codeUntil) newCode(ctx) // keep a code that still runs
        return hostStatus(ctx)
    }

    /** the game server is on: be reachable online too (called by [Netplay.startServer]) */
    @Synchronized
    fun open(ctx: Context): JSONObject {
        if (!Room.running()) Room.start(ctx, "")?.let { return JSONObject().put("error", it) }
        host?.let { return hostStatus(ctx) }
        val s = roomSecret(ctx)
        val h = runCatching { Tunnel.Host(s, Azahar.ROOM_PORT, TUNNEL_PORT) }.getOrElse { runCatching { Tunnel.Host(s, Azahar.ROOM_PORT) }.getOrNull() }
            ?: return JSONObject().put("error", "Kein UDP-Port frei")
        host = h; secret = s
        Logs.i("online", "Online-Raum offen, erreichbar über ${h.candidates.size} Adressen" +
            (if (h.candidates.none { isPublic(it) }) " (keine öffentliche Adresse gefunden)" else ""))
        return hostStatus(ctx)
    }

    /** a new code (the old one stops working); guests who used it keep their access */
    @Synchronized
    fun newCode(ctx: Context): String {
        val c = (1..CODE_LEN).map { rnd.nextInt(SYMBOLS.size) }.joinToString("")
        val until = System.currentTimeMillis() + CODE_MINUTES * 60_000
        // stored, so a restart (update, crash) does not kill a code a friend is just typing
        Prefs.of(ctx).edit().putString("online.code", c).putLong("online.codeUntil", until).apply()
        listenCode(ctx.applicationContext, c, until)
        Logs.i("online", "neuer Code, gilt $CODE_MINUTES Minuten")
        return c
    }

    private fun listenCode(app: Context, c: String, until: Long) {
        code = c; codeUntil = until
        val ch = codeChannel(c)
        subscribe(ch, { code == c && System.currentTimeMillis() < codeUntil }) { m ->
            if (m.optString("t") != "code") return@subscribe
            val theirs = m.optString("pub").ifEmpty { return@subscribe }
            Logs.i("online", "${m.optString("n", "Gast").take(32)} hat den Code eingegeben")
            val h = host ?: run { // the room may be closed (restart, game server off): open it for the friend
                if (!Netplay.serverOn) Netplay.startServer(app)
                open(app); host
            } ?: return@subscribe
            val mine = ecdhPair()
            val box = JSONObject().put("n", Me.name(app)).put("s", b64(secret ?: return@subscribe)).put("c", JSONArray(h.candidates))
            publish(ch, JSONObject().put("t", "ok").put("id", m.optString("id")).put("pub", b64(mine.public.encoded))
                .put("box", b64(Tunnel.seal(boxKey(mine, theirs), 0, 0, 0, box.toString().toByteArray()))))
        }
    }

    /**
     * Always on while ThorBro runs: listens for guests who know this room (they had a code once). A knock starts room
     * and tunnel when they are not up yet, opens the router for the guest and sends the current addresses back.
     */
    @Synchronized
    fun listen(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val s = roomSecret(app)
        if (listening?.contentEquals(s) == true) return
        listening = s
        // a code shown before a restart keeps working until it runs out
        val p = Prefs.of(app)
        val c = p.getString("online.code", null); val until = p.getLong("online.codeUntil", 0)
        if (c != null && until > System.currentTimeMillis() && code != c) listenCode(app, c, until)
        subscribe(s, { listening === s }) { m ->
            if (m.optString("t") != "guest") return@subscribe
            val c = m.optJSONArray("c") ?: return@subscribe
            val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }
            Logs.i("online", "${m.optString("n", "Gast").take(32)} klopft an")
            val h = host ?: run {
                val r = startHost(app)
                if (r.has("error")) { Logs.i("online", "Raum geht nicht auf: ${r.optString("error")}"); return@subscribe }
                host
            } ?: return@subscribe
            h.punch(cands)
            h.refreshPublic(); Thread.sleep(600) // the router may have re-mapped the port meanwhile
            publish(s, JSONObject().put("t", "host").put("c", JSONArray(h.candidates)))
        }
        Logs.i("online", "erreichbar für Freunde")
    }

    /** locks out everyone who joined before (new room key) */
    fun resetAccess(ctx: Context): JSONObject {
        Prefs.of(ctx).edit().remove("online.secret").apply()
        val wasOpen = host != null
        stopHost(); code = null
        listen(ctx)
        return if (wasOpen) startHost(ctx) else JSONObject().put("open", false)
    }

    @Synchronized
    fun stopHost() {
        val h = host ?: return
        host = null; secret = null; code = null
        appCtx?.let { Prefs.of(it).edit().remove("online.code").remove("online.codeUntil").apply() }
        h.close()
        Logs.i("online", "Online-Raum zu")
    }

    fun hostStatus(ctx: Context): JSONObject {
        val h = host ?: return JSONObject().put("open", false).put("known", knownHosts(ctx))
        val c = code?.takeIf { System.currentTimeMillis() < codeUntil }
        return JSONObject().put("open", true).put("guests", h.guests).put("public", h.candidates.any { isPublic(it) })
            .put("code", c?.let { codeText(it) }).put("digits", c?.let { codeDigits(it) })
            .put("codeMinutes", if (c == null) 0 else ((codeUntil - System.currentTimeMillis()) / 60_000 + 1))
            .put("known", knownHosts(ctx))
    }

    private fun isPublic(c: String) = Tunnel.parse(c)?.address?.let { !it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
        !(it.address.size == 4 && it.address[0].toInt() == 100 && (it.address[1].toInt() and 0xC0) == 64) } == true // 100.64/10: CGNAT, NetBird, Tailscale

    // ---------- guest ----------

    /** hosts this Thor joined before, newest first: name and room key, so the next join needs no code */
    fun knownHosts(ctx: Context): JSONArray = runCatching { JSONArray(Prefs.of(ctx).getString("online.known", "[]")) }.getOrDefault(JSONArray())
        .let { a -> JSONArray((0 until a.length()).map { a.getJSONObject(it) }.map { JSONObject().put("name", it.optString("name")).put("id", it.optString("id")) }) }

    private fun remember(ctx: Context, inv: Invite) {
        val p = Prefs.of(ctx)
        val all = runCatching { JSONArray(p.getString("online.known", "[]")) }.getOrDefault(JSONArray())
        val id = hex(hmac(inv.secret, "id"), 4)
        val list = listOf(JSONObject().put("id", id).put("name", inv.name).put("s", b64(inv.secret)).put("c", JSONArray(inv.candidates))) +
            (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("id") != id }
        p.edit().putString("online.known", JSONArray(list.take(10)).toString()).apply()
    }

    fun forget(ctx: Context, id: String) {
        val p = Prefs.of(ctx)
        val all = runCatching { JSONArray(p.getString("online.known", "[]")) }.getOrDefault(JSONArray())
        p.edit().putString("online.known", JSONArray((0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("id") != id }).toString()).apply()
    }

    /** turns a button code into the host's room key and addresses (through the key exchange), or throws */
    fun redeem(myName: String, input: String, job: Jobs.Job? = null): Invite {
        val c = normalize(input) ?: throw IllegalArgumentException("Der Code hat $CODE_LEN Tasten")
        job?.let { it.message = "Suche den Thor mit diesem Code …"; it.progress = 0.1 }
        val ch = codeChannel(c)
        val mine = ecdhPair()
        val id = b64(ByteArray(8).also { rnd.nextBytes(it) })
        var got: Invite? = null
        var done = false
        subscribe(ch, { !done && got == null }) { m ->
            if (m.optString("t") != "ok" || m.optString("id") != id) return@subscribe
            val raw = unb64(m.optString("box"))
            val box = Tunnel.open(boxKey(mine, m.optString("pub")), raw, 0, raw.size) ?: return@subscribe
            val j = JSONObject(String(box.data))
            val cs = j.getJSONArray("c")
            got = Invite(j.optString("n", "Thor").take(32), unb64(j.getString("s")).also { require(it.size == 16) },
                (0 until minOf(cs.length(), 12)).map { cs.getString(it) }.filter { Tunnel.parse(it) != null })
        }
        Thread.sleep(800) // let the subscription stand before asking
        val ask = JSONObject().put("t", "code").put("id", id).put("n", myName).put("pub", b64(mine.public.encoded))
        val end = System.currentTimeMillis() + 20_000
        var next = 0L
        while (got == null && System.currentTimeMillis() < end) {
            if (System.currentTimeMillis() >= next) { publish(ch, ask); next = System.currentTimeMillis() + 4000 }
            Thread.sleep(200)
        }
        done = true
        return got ?: throw IllegalStateException("Kein Thor mit diesem Code gefunden. Stimmt der Code, und ist er noch keine $CODE_MINUTES Minuten alt?")
    }

    /** button code → in the friend's room: when it returns without error, Azahar's 127.0.0.1 is that room */
    fun joinCode(ctx: Context, input: String, job: Jobs.Job? = null): JSONObject {
        val inv = redeem(Me.name(ctx), input, job)
        remember(ctx, inv)
        return join(ctx, inv, job)
    }

    /** back to a host joined before (no code) */
    fun joinKnown(ctx: Context, id: String, job: Jobs.Job? = null): JSONObject {
        val all = runCatching { JSONArray(Prefs.of(ctx).getString("online.known", "[]")) }.getOrDefault(JSONArray())
        val k = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            ?: throw IllegalArgumentException("Unbekannt")
        val cs = k.optJSONArray("c") ?: JSONArray()
        val inv = Invite(k.optString("name"), unb64(k.getString("s")), (0 until cs.length()).map { cs.getString(it) })
        return join(ctx, inv, job)
    }

    fun join(ctx: Context, inv: Invite, job: Jobs.Job? = null): JSONObject {
        stopHost()
        job?.let { it.message = "Verbinde mit ${inv.name} …"; it.progress = 0.4 }
        val g = Tunnel.Guest(inv.secret, inv.candidates)
        // tell the host our addresses until it answers, so its router lets us in; it answers with its current ones
        var done = false
        val hello = JSONObject().put("t", "guest").put("n", Me.name(ctx)).put("c", JSONArray(g.candidates))
        subscribe(inv.secret, { !done }) { m ->
            if (m.optString("t") == "host") m.optJSONArray("c")?.let { c -> g.addTargets((0 until minOf(c.length(), 12)).map { c.getString(it) }) }
        }
        thread(name = "online-knock", isDaemon = true) {
            Thread.sleep(800)
            var i = 0
            while (!done && i++ < 8) { publish(inv.secret, hello); Thread.sleep(3000) }
        }
        val ok = try { g.await(25_000) } finally { done = true }
        if (!ok) {
            g.close()
            throw IllegalStateException(if (g.full) "Der Raum von ${inv.name} ist voll" else
                "${inv.name} nicht erreichbar. Läuft ThorBro dort? Manche Router (oft Mobilfunk auf beiden Seiten) lassen keine Direktverbindung zu.")
        }
        job?.let { it.message = "Verbunden"; it.progress = 0.9 }
        Relay.startTunnel(g, inv.name)?.let { throw IllegalStateException(it) }
        Netplay.joinedRoom = JSONObject().put("game", "3DS-Raum").put("host", inv.name).put("address", "online").put("mode", "azahar")
            .put("roomIp", "127.0.0.1").put("hint", Azahar.guestSteps(inv.name)).put("at", System.currentTimeMillis())
        Logs.i("online", "Online mit ${inv.name} verbunden")
        return JSONObject().put("mode", "azahar").put("host", inv.name).put("hint", Azahar.guestSteps(inv.name))
    }
}
