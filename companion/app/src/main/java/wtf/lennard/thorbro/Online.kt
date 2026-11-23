package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 3DS online play between Thors, without a game server and without a VPN app.
 *
 * The host's room ([Room]) stays as it is; [Tunnel.Host] lets guests from the internet into it. An invite (a link)
 * carries the room secret and the host's addresses. The guest's ThorBro opens a [Tunnel.Guest], and [Relay] puts it
 * behind 127.0.0.1 — Azahar on both Thors always joins 127.0.0.1.
 *
 * Both routers have to be opened from the inside, so the host also needs the guest's addresses: they travel through
 * public message services ("signalling", ntfy.sh for now), encrypted with the room secret under a topic derived from
 * it — the service sees neither addresses nor names. No game data ever goes through it, and none of it is the
 * author's server: the app works the same for everyone who builds it.
 */
object Online {
    const val TUNNEL_PORT = 24873
    const val LINK_PREFIX = "thorbro://join/"
    private const val NTFY = "https://ntfy.sh"

    @Volatile var host: Tunnel.Host? = null
        private set
    @Volatile private var secret: ByteArray? = null
    @Volatile private var hostListen: Thread? = null

    init { Tunnel.log = { Logs.i("online", it) } }

    // ---------- invite ----------

    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)

    fun link(name: String, secret: ByteArray, candidates: List<String>): String =
        LINK_PREFIX + b64(JSONObject().put("v", 1).put("n", name).put("s", b64(secret)).put("c", JSONArray(candidates)).toString().toByteArray())

    class Invite(val name: String, val secret: ByteArray, val candidates: List<String>)

    /** the invite from a link (thorbro://join/…, or any URL that ends in …join/<code> or #<code>), or null */
    fun parse(link: String): Invite? = runCatching {
        val code = link.trim().substringAfterLast("join/").substringAfterLast('#').trim()
        val j = JSONObject(String(unb64(code)))
        val c = j.getJSONArray("c")
        Invite(j.optString("n", "Thor").take(32), unb64(j.getString("s")).also { require(it.size == 16) },
            (0 until c.length()).map { c.getString(it) }.filter { Tunnel.parse(it) != null }.take(12))
    }.getOrNull()

    // ---------- signalling (ntfy.sh) ----------

    private fun topic(secret: ByteArray) = "tb-" + Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal("thorbro signal topic".toByteArray())
    }.take(12).joinToString("") { "%02x".format(it) }

    private fun signalKey(secret: ByteArray) = SecretKeySpec(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal("thorbro signal key".toByteArray())
    }, "AES")

    internal fun publish(secret: ByteArray, msg: JSONObject) = runCatching {
        val body = b64(Tunnel.seal(signalKey(secret), 0, 0, System.currentTimeMillis(), msg.toString().toByteArray()))
        (URL("$NTFY/${topic(secret)}").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Cache", "no") // nothing stays on the server
            outputStream.use { it.write(body.toByteArray()) }
            responseCode.also { disconnect() }
        }
    }.onFailure { Logs.i("online", "ntfy nicht erreichbar: ${it.message}") }

    /** streams the topic until [alive] says stop; calls [on] with every message we can decrypt */
    private fun subscribe(secret: ByteArray, alive: () -> Boolean, on: (JSONObject) -> Unit) = thread(name = "online-signal", isDaemon = true) {
        val key = signalKey(secret)
        while (alive()) {
            runCatching {
                (URL("$NTFY/${topic(secret)}/json").openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 90_000 // ntfy sends a keepalive every ~45 s
                    inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!alive()) break
                            val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (ev.optString("event") != "message") continue
                            val raw = runCatching { unb64(ev.optString("message")) }.getOrNull() ?: continue
                            val m = Tunnel.open(key, raw, 0, raw.size) ?: continue
                            runCatching { on(JSONObject(String(m.data))) }
                        }
                    }
                    disconnect()
                }
            }
            if (alive()) Thread.sleep(3000)
        }
    }

    // ---------- host ----------

    /** opens the room to the internet and returns the invite link (the same link while it stays open) */
    @Synchronized
    fun startHost(ctx: Context): JSONObject {
        if (!Room.running()) {
            val err = if (Netplay.serverOn) Room.start(ctx, "") else Netplay.startServer(ctx).optString("roomError").ifEmpty { null }
            if (err != null) return JSONObject().put("error", err)
        }
        host?.let { return hostStatus(ctx) }
        val s = Tunnel.newSecret()
        val h = runCatching { Tunnel.Host(s, Azahar.PORT, TUNNEL_PORT) }.getOrElse { runCatching { Tunnel.Host(s, Azahar.PORT) }.getOrNull() }
            ?: return JSONObject().put("error", "Kein UDP-Port frei")
        host = h; secret = s
        Logs.i("online", "Online-Raum offen, erreichbar über ${h.candidates.size} Adressen" +
            (if (h.candidates.none { isPublic(it) }) " (keine öffentliche Adresse gefunden)" else ""))
        hostListen = subscribe(s, { host === h }) { m ->
            if (m.optString("t") != "guest") return@subscribe
            val c = m.optJSONArray("c") ?: return@subscribe
            val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }
            Logs.i("online", "${m.optString("n", "Gast").take(32)} klopft an, öffne den Weg")
            h.punch(cands)
            publish(s, JSONObject().put("t", "host").put("c", JSONArray(h.candidates))) // fresh addresses, in case the invite is old
        }
        return hostStatus(ctx)
    }

    @Synchronized
    fun stopHost() {
        val h = host ?: return
        host = null; secret = null
        h.close()
        Logs.i("online", "Online-Raum zu")
    }

    fun hostStatus(ctx: Context): JSONObject {
        val h = host ?: return JSONObject().put("open", false)
        return JSONObject().put("open", true).put("link", link(Me.name(ctx), secret!!, h.candidates))
            .put("guests", h.guests).put("public", h.candidates.any { isPublic(it) })
    }

    private fun isPublic(c: String) = Tunnel.parse(c)?.address?.let { !it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
        !(it.address.size == 4 && it.address[0].toInt() == 100 && (it.address[1].toInt() and 0xC0) == 64) } == true // 100.64/10: CGNAT, NetBird, Tailscale

    // ---------- guest ----------

    /** joins a friend's room from an invite link: when it returns without error, Azahar's 127.0.0.1 is that room */
    fun join(ctx: Context, link: String, job: Jobs.Job? = null): JSONObject {
        val inv = parse(link) ?: throw IllegalArgumentException("Einladung kaputt oder unvollständig")
        stopHost()
        job?.let { it.message = "Suche den Weg zu ${inv.name} …"; it.progress = 0.2 }
        val g = Tunnel.Guest(inv.secret, inv.candidates)
        // tell the host our addresses until it answers, so its router lets us in
        var done = false
        val hello = JSONObject().put("t", "guest").put("n", Me.name(ctx)).put("c", JSONArray(g.candidates))
        thread(name = "online-knock", isDaemon = true) {
            var i = 0
            while (!done && i++ < 8) { publish(inv.secret, hello); Thread.sleep(3000) }
        }
        val ok = try { g.await(25_000) } finally { done = true }
        if (!ok) {
            g.close()
            throw IllegalStateException(if (g.full) "Der Raum von ${inv.name} ist voll" else
                "${inv.name} nicht erreichbar. Ist der Online-Raum dort noch offen? Manche Router (oft Mobilfunk) lassen keine Direktverbindung zu.")
        }
        job?.let { it.message = "Verbunden"; it.progress = 0.9 }
        Relay.startTunnel(g, inv.name)?.let { throw IllegalStateException(it) }
        Netplay.joinedRoom = JSONObject().put("game", "3DS-Raum").put("host", inv.name).put("address", "online").put("mode", "azahar")
            .put("roomIp", "127.0.0.1").put("hint", Azahar.guestSteps(inv.name)).put("at", System.currentTimeMillis())
        Logs.i("online", "Online mit ${inv.name} verbunden")
        return JSONObject().put("mode", "azahar").put("host", inv.name).put("hint", Azahar.guestSteps(inv.name))
    }
}
