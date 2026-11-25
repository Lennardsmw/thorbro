package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * How party members reach the leader's rooms, from anywhere (protocol: companion/FRIENDS.md, "Party-Netz").
 *
 * The leader runs one [Tunnel.Host] (UDP [Tunnel.PORT]) while it leads a party with members, with a secret that belongs
 * to the party. A member that is in the party sends `knock {party, c}` with its own addresses (LAN, NetBird, public from
 * STUN) through Pals (encrypted, signed, only to the leader); the leader opens its router towards them (hole punching)
 * and answers `net {party, s, c}` with the secret and its current addresses. The member's [Tunnel.Guest] tries all of
 * them at once and keeps the first that answers — same WLAN, NetBird or the internet, no address typed anywhere.
 * [Relay] then leads 127.0.0.1 (Azahar, Eden) through that tunnel into the leader's rooms.
 */
object PartyNet {
    /** knocks per party before giving up until the leader's status changes; ntfy.sh allows 250 posts a day per IP */
    private const val MAX_KNOCKS = 8
    private val rooms = setOf(Azahar.ROOM_PORT, EdenRoom.ROOM_PORT)

    init { Tunnel.log = { Logs.i("partynet", it) } }

    // ---------- leader ----------

    @Volatile var host: Tunnel.Host? = null
        private set
    @Volatile private var hostSecret: ByteArray? = null

    private fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)

    /** the party's tunnel secret (made once per party, kept in party.mine) */
    private fun secret(ctx: Context, p: JSONObject): ByteArray {
        p.optString("net").takeIf { it.isNotEmpty() }?.let { s -> runCatching { unb64(s) }.getOrNull()?.takeIf { it.size == 16 }?.let { return it } }
        val s = Tunnel.newSecret()
        p.put("net", Pals.b64(s))
        Prefs.of(ctx).edit().putString("party.mine", p.toString()).apply()
        return s
    }

    /** leading with members: tunnel up; else down. Called whenever the party changes. */
    @Synchronized
    fun sync(ctx: Context) {
        val p = Party.mine(ctx)
        val lead = p != null && Party.leading(ctx)
        if (!lead) { closeHost(); if (p == null) unfollow(ctx); return }
        val s = secret(ctx, p!!)
        if (host != null && hostSecret?.contentEquals(s) == true) return
        closeHost()
        val h = runCatching { Tunnel.Host(s, rooms, Tunnel.PORT) }.getOrElse { runCatching { Tunnel.Host(s, rooms) }.getOrNull() }
        if (h == null) { Logs.i("partynet", "Kein UDP-Port für den Party-Tunnel frei"); return }
        host = h; hostSecret = s
        Logs.i("partynet", "Party-Tunnel offen (Port ${h.sock.localPort}, ${h.candidates.size} Adressen)")
    }

    private fun closeHost() {
        val h = host ?: return
        host = null; hostSecret = null
        h.close()
        Logs.i("partynet", "Party-Tunnel zu")
    }

    /** a member knocks: open the router towards it and send secret + current addresses */
    private fun onKnock(ctx: Context, from: JSONObject, m: JSONObject) {
        if (System.currentTimeMillis() - m.optLong("ts") > 5 * 60_000) return // an old one, replayed after a restart
        val p = Party.mine(ctx) ?: return
        if (!Party.isLeader(ctx) || p.optString("id") != m.optString("party")) return
        val members = p.optJSONArray("members") ?: return
        if ((0 until members.length()).none { members.getJSONObject(it).optString("id") == from.optString("id") }) return
        sync(ctx)
        val h = host ?: return
        val c = m.optJSONArray("c") ?: JSONArray()
        val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }
        Logs.i("partynet", "${from.optString("name").take(24)} klopft an (${cands.size} Adressen)")
        h.punch(cands)
        h.refreshPublic(); Thread.sleep(600) // the router may have re-mapped the port meanwhile
        Pals.send(ctx, from.optString("pub"), JSONObject().put("t", "net").put("party", p.optString("id"))
            .put("s", Pals.b64(hostSecret ?: return)).put("c", JSONArray(h.candidates)))
    }

    // ---------- member ----------

    @Volatile var guest: Tunnel.Guest? = null
        private set
    @Volatile var leaderName = ""
        private set
    @Volatile private var following = "" // party id
    @Volatile private var pending: Pair<DatagramSocket, List<String>>? = null
    @Volatile private var knocks = 0
    @Volatile private var lastKnock = 0L
    @Volatile private var lastNet = 0L

    /** as member: the leader's status says it is in the party and offers the tunnel */
    fun follow(ctx: Context, leader: JSONObject, party: JSONObject) {
        val app = ctx.applicationContext
        val id = party.optString("id")
        leaderName = leader.optString("name")
        if (party.optJSONObject("room")?.optBoolean("net") != true) return
        if (following != id) { unfollow(app); following = id; knocks = 0 }
        val g = guest
        if (g != null && (g.connected() || System.currentTimeMillis() - lastNet < 30_000)) return
        knock(app, leader)
    }

    /**
     * Sends our addresses to the leader: at most every 20 s, after [MAX_KNOCKS] failed ones only every 5 min (ntfy.sh
     * allows 250 posts a day). Each knock uses a fresh socket with fresh addresses — after a network change (WLAN →
     * hotspot) the old ones lead nowhere; the leader's answer then moves the tunnel onto it ([onNet]).
     */
    @Synchronized
    private fun knock(ctx: Context, leader: JSONObject) {
        val now = System.currentTimeMillis()
        if (now - lastKnock < (if (knocks >= MAX_KNOCKS) 5 * 60_000 else 20_000)) return
        lastKnock = now; knocks++
        val party = following
        thread(name = "partynet-knock", isDaemon = true) {
            val fresh = Tunnel.socket()
            synchronized(this) { pending?.first?.close(); pending = fresh }
            val cands = fresh.second
            Logs.i("partynet", "Klopfe bei ${leader.optString("name").take(24)} an (${cands.size} Adressen)")
            Pals.send(ctx, leader.optString("pub"), JSONObject().put("t", "knock").put("party", party).put("c", JSONArray(cands)))
        }
    }

    /** the leader answered: tunnel with its secret, towards its addresses */
    private fun onNet(ctx: Context, from: JSONObject, m: JSONObject) {
        if (System.currentTimeMillis() - m.optLong("ts") > 5 * 60_000) return
        val p = Party.mine(ctx) ?: return
        if (p.optString("leader") != from.optString("id") || p.optString("id") != m.optString("party") || following != p.optString("id")) return
        val s = runCatching { unb64(m.optString("s")) }.getOrNull()?.takeIf { it.size == 16 } ?: return
        val c = m.optJSONArray("c") ?: JSONArray()
        val cands = (0 until minOf(c.length(), 12)).map { c.getString(it) }.filter { Tunnel.parse(it) != null }
        lastNet = System.currentTimeMillis()
        synchronized(this) {
            val g = guest
            val fresh = pending
            // still connected on the old socket: keep it, just learn the leader's addresses
            if (g != null && g.secret.contentEquals(s) && (g.connected() || fresh == null)) { g.addTargets(cands); return }
            g?.close()
            val (sock, mine) = fresh ?: Tunnel.socket()
            pending = null
            guest = Tunnel.Guest(s, cands, socket = sock, known = mine)
        }
        Logs.i("partynet", "Party-Tunnel zu ${from.optString("name").take(24)}: ${cands.size} Adressen")
        Relay.route(ctx)
        watch(ctx, from)
    }

    /** knocks again when the tunnel does not come up (or drops later), until it works or the knocks run out */
    private fun watch(ctx: Context, leader: JSONObject) = thread(name = "partynet-watch", isDaemon = true) {
        val g = guest ?: return@thread
        g.open(Azahar.ROOM_PORT) { } // one idle session: says hello until the leader answers, then keeps the link alive
        var down = 0L
        while (guest === g) {
            Thread.sleep(5000)
            if (g.connected()) { down = 0; knocks = 0; continue }
            if (down == 0L) down = System.currentTimeMillis()
            if (System.currentTimeMillis() - down > 25_000) { down = System.currentTimeMillis(); knock(ctx, leader) }
        }
    }

    /** out of the party: tunnel down, 127.0.0.1 back to the own rooms */
    @Synchronized
    fun unfollow(ctx: Context) {
        val had = guest != null
        following = ""
        guest?.close(); guest = null
        pending?.first?.close(); pending = null
        if (had) { Logs.i("partynet", "Party-Tunnel getrennt"); Relay.route(ctx) }
    }

    fun connected() = guest?.connected() == true

    fun status(): JSONObject = JSONObject()
        .put("hosting", host != null).put("guests", host?.guests ?: 0)
        .put("following", guest != null).put("connected", connected()).put("leader", leaderName)

    fun start() {
        Pals.handlers["knock"] = ::onKnock
        Pals.handlers["net"] = ::onNet
    }
}
