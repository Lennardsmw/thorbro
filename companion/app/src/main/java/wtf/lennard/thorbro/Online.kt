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
    @Volatile private var listening: ByteArray? = null

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
    internal fun subscribe(secret: ByteArray, alive: () -> Boolean, on: (JSONObject) -> Unit) = thread(name = "online-signal", isDaemon = true) {
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

    /**
     * The room secret stays the same across restarts, so an invite link keeps working as long as the game server
     * runs (and after a reboot). [newLink] makes a new one, which locks out everyone who had the old link.
     */
    private fun roomSecret(ctx: Context): ByteArray {
        val p = Prefs.of(ctx)
        p.getString("online.secret", null)?.let { runCatching { unb64(it) }.getOrNull()?.takeIf { it.size == 16 }?.let { s -> return s } }
        return Tunnel.newSecret().also { p.edit().putString("online.secret", b64(it)).apply() }
    }

    /** opens the room to the internet (starting the game server if needed) and returns the invite link */
    fun startHost(ctx: Context): JSONObject {
        if (!Netplay.serverOn) {
            Netplay.startServer(ctx).optString("roomError").ifEmpty { null }?.let { return JSONObject().put("error", it) }
        }
        return open(ctx)
    }

    /** the game server is on: be reachable online too (called by [Netplay.startServer]) */
    @Synchronized
    fun open(ctx: Context): JSONObject {
        if (!Room.running()) Room.start(ctx, "")?.let { return JSONObject().put("error", it) }
        host?.let { return hostStatus(ctx) }
        val s = roomSecret(ctx)
        val h = runCatching { Tunnel.Host(s, Azahar.PORT, TUNNEL_PORT) }.getOrElse { runCatching { Tunnel.Host(s, Azahar.PORT) }.getOrNull() }
            ?: return JSONObject().put("error", "Kein UDP-Port frei")
        host = h; secret = s
        Logs.i("online", "Online-Raum offen, erreichbar über ${h.candidates.size} Adressen" +
            (if (h.candidates.none { isPublic(it) }) " (keine öffentliche Adresse gefunden)" else ""))
        return hostStatus(ctx)
    }

    /**
     * Always on while ThorBro runs: listens for guests knocking with this Thor's link. Being found has nothing to do
     * with the game server — a knock starts room and tunnel when they are not up yet, then opens the router for the
     * guest and sends the current addresses back.
     */
    @Synchronized
    fun listen(ctx: Context) {
        val app = ctx.applicationContext
        val s = roomSecret(app)
        if (listening?.contentEquals(s) == true) return
        listening = s
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
            h.refreshPublic(); Thread.sleep(600) // the router may have re-mapped the port since the invite
            publish(s, JSONObject().put("t", "host").put("c", JSONArray(h.candidates)))
        }
        Logs.i("online", "erreichbar für Einladungen")
    }

    /** a new invite link; the old one stops working */
    fun newLink(ctx: Context): JSONObject {
        Prefs.of(ctx).edit().remove("online.secret").apply()
        val wasOpen = host != null
        stopHost()
        listen(ctx) // now under the new secret; the old topic goes quiet
        return if (wasOpen) open(ctx) else JSONObject().put("open", false)
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
        subscribe(inv.secret, { !done }) { m -> // the host answers with its current addresses
            if (m.optString("t") == "host") m.optJSONArray("c")?.let { c -> g.addTargets((0 until minOf(c.length(), 12)).map { c.getString(it) }) }
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
