package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Party: a group of friends that plays together (protocol: companion/FRIENDS.md, "Party").
 *
 * The leader holds the party; its member list is part of the leader's status ([Pals.publishStatus]), which every
 * friend reads along — so a change costs one post, not one per member. Only the leader invites (members are always
 * friends of the leader, so they can read its status). Messages: `inv` leader → friend, `join` / `leave` → leader.
 * An open party ([Pals.partyOpen]) can be joined by the leader's friends without an invite.
 */
object Party {
    private const val HEARTBEAT_MS = 3L * 3600_000

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** this device's party: {id, leader, members:[{id,name}], invited:[id]} as leader, {id, leader, since} as member */
    fun mine(ctx: Context): JSONObject? = Prefs.of(ctx).getString("party.mine", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun save(ctx: Context, p: JSONObject?) {
        Prefs.of(ctx).edit().apply { if (p == null) remove("party.mine") else putString("party.mine", p.toString()) }.apply()
        Pals.bump()
    }

    fun isLeader(ctx: Context) = mine(ctx)?.optString("leader") == Pals.myId(ctx)

    /** leading a party with at least one more member: a game started anywhere opens a lobby for them ([AutoPlay]) */
    fun leading(ctx: Context) = mine(ctx)?.let { it.optString("leader") == Pals.myId(ctx) && (it.optJSONArray("members")?.length() ?: 0) > 1 } == true

    // ---------- playing together ----------

    /** the lobby opened or closed: as leader, tell the party (one status post) */
    fun lobbyChanged(ctx: Context) { if (leading(ctx)) publish(ctx) }

    /** the open lobby as the party sees it: game, PIN and where to reach us */
    private fun gameInfo(): JSONObject? {
        val l = Netplay.lobby ?: return null
        val addrs = listOfNotNull(Device.ip(), Direct.ownIp().takeIf { Direct.active() }).distinct()
        return JSONObject().put("system", l.system).put("title", l.rom.name.substringBeforeLast('.')).put("mode", l.mode)
            .put("pin", l.pin).put("addrs", JSONArray(addrs))
    }

    /** the own 3DS room (always on): members point their 127.0.0.1 at it */
    private fun roomInfo(): JSONObject? {
        if (!Room.running()) return null
        val addrs = listOfNotNull(Device.ip(), Direct.ownIp().takeIf { Direct.active() }).distinct()
        return JSONObject().put("port", Azahar.ROOM_PORT).put("addrs", JSONArray(addrs))
    }

    @Volatile private var roomKey = ""

    /** as member: 127.0.0.1 leads to the leader's room (null = back to the own room) */
    private fun followRoom(ctx: Context, leader: JSONObject?, r: JSONObject?) {
        val addrs = r?.optJSONArray("addrs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val key = leader?.optString("id").orEmpty() + addrs.joinToString()
        if (key == roomKey) return
        roomKey = key
        if (leader == null || addrs.isEmpty()) { Relay.partyRoom = null; Relay.route(ctx); return }
        thread(name = "party-room", isDaemon = true) {
            val reach = addrs.firstOrNull { a -> runCatching { Netplay.peer(a).optBoolean("reachable") }.getOrDefault(false) } ?: addrs.first()
            Relay.partyRoom = reach to leader.optString("name")
            Relay.route(ctx)
            Logs.i("party", "3DS: 127.0.0.1 → Raum von ${leader.optString("name")} ($reach)")
        }
    }

    @Volatile private var lastJoin = ""

    /** the leader started a game: join it (ROM from the leader if missing, emulator as guest) */
    private fun followGame(ctx: Context, leader: JSONObject, g: JSONObject) {
        val key = g.optString("pin") + g.optString("title")
        if (key == lastJoin || Netplay.lobby != null) return
        lastJoin = key
        val title = g.optString("title")
        thread(name = "party-join", isDaemon = true) {
            val addrs = g.optJSONArray("addrs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            // the first address that answers (same WLAN, or the leader's Wi-Fi Direct net)
            val reach = addrs.firstOrNull { a -> runCatching { Netplay.peer(a).optBoolean("reachable") }.getOrDefault(false) }
            if (reach == null) {
                Pals.notify(ctx, "${leader.optString("name").take(24)} spielt $title", "Nicht im gleichen Netz – übers Internet kommt noch")
                return@thread
            }
            Pals.notify(ctx, "Party: ${leader.optString("name").take(24)} startet $title", "Du trittst bei …")
            val job = Jobs.start("Party: $title") { j -> Netplay.join(ctx, reach, g.optString("pin"), j) }
            Logs.i("party", "Trete $title bei (${reach})")
            while (!job.done) Thread.sleep(500)
            job.error?.let { Pals.notify(ctx, "Party: $title ging nicht", it.take(80)); Logs.i("party", "Beitritt: $it") }
        }
    }

    /** what this device tells its friends */
    fun status(ctx: Context): JSONObject {
        val st = JSONObject().put("on", true).put("app", "ThorBro")
        val p = mine(ctx)
        if (p != null) st.put("party", if (p.optString("leader") == Pals.myId(ctx))
            JSONObject().put("id", p.optString("id")).put("leader", Pals.myId(ctx)).put("open", Pals.partyOpen(ctx)).put("members", p.optJSONArray("members") ?: JSONArray())
                .apply { gameInfo()?.let { put("game", it) }; roomInfo()?.let { put("room", it) } }
            else JSONObject().put("id", p.optString("id")).put("leader", p.optString("leader")))
        else if (Pals.partyOpen(ctx)) st.put("open", true) // alone, but friends may join
        return st
    }

    fun publish(ctx: Context) = thread(name = "party-status", isDaemon = true) { Pals.publishStatus(ctx.applicationContext, status(ctx)) }

    /** the members as everyone sees them: own list as leader, the leader's status as member */
    fun members(ctx: Context): List<JSONObject> {
        val p = mine(ctx) ?: return emptyList()
        if (p.optString("leader") == Pals.myId(ctx)) return p.optJSONArray("members").objs()
        return Pals.presenceOf(ctx, p.optString("leader"))?.optJSONObject("party")?.optJSONArray("members").objs()
            .ifEmpty { listOf(JSONObject().put("id", p.optString("leader")).put("name", p.optString("leaderName"))) }
    }

    // ---------- leader ----------

    private fun newParty(ctx: Context): JSONObject {
        val p = JSONObject().put("id", Pals.b64(ByteArray(6).also { java.security.SecureRandom().nextBytes(it) })).put("leader", Pals.myId(ctx))
            .put("members", JSONArray().put(JSONObject().put("id", Pals.myId(ctx)).put("name", Pals.myName(ctx)))).put("invited", JSONArray())
        save(ctx, p)
        return p
    }

    /** invites a friend; starts a party with this device as leader when there is none. null = sent */
    fun invite(ctx: Context, friendId: String): String? {
        val f = Pals.friends(ctx).firstOrNull { it.optString("id") == friendId } ?: return "Kein Freund"
        var p = mine(ctx)
        if (p != null && p.optString("leader") != Pals.myId(ctx)) return "Nur der Partyleiter lädt ein"
        if (p == null) { p = newParty(ctx); publish(ctx) }
        if (p.optJSONArray("members").objs().any { it.optString("id") == friendId }) return "Ist schon in der Party"
        val inv = (p.optJSONArray("invited").objs().map { it.optString("id") } + friendId).distinct()
        p.put("invited", JSONArray(inv.map { JSONObject().put("id", it) }))
        save(ctx, p)
        return if (Pals.send(ctx, f.optString("pub"), JSONObject().put("t", "inv").put("party", p.optString("id"))
                .put("members", p.optJSONArray("members")))) null else "ntfy.sh nicht erreichbar"
    }

    private fun onJoin(ctx: Context, from: JSONObject, m: JSONObject) {
        val p = mine(ctx) ?: return refuse(ctx, from, "Keine Party mehr")
        if (p.optString("leader") != Pals.myId(ctx) || p.optString("id") != m.optString("party")) return refuse(ctx, from, "Party gibt es nicht mehr")
        val id = from.optString("id")
        val invited = p.optJSONArray("invited").objs().any { it.optString("id") == id }
        if (!invited && !Pals.partyOpen(ctx)) return refuse(ctx, from, "Nur auf Einladung")
        val members = p.optJSONArray("members").objs().filter { it.optString("id") != id } + JSONObject().put("id", id).put("name", from.optString("name"))
        p.put("members", JSONArray(members)).put("invited", JSONArray(p.optJSONArray("invited").objs().filter { it.optString("id") != id }))
        save(ctx, p)
        Logs.i("party", "${from.optString("name")} ist in der Party")
        Pals.notify(ctx, "${from.optString("name").take(24)} ist in deiner Party", "")
        publish(ctx)
    }

    private fun refuse(ctx: Context, from: JSONObject, why: String) {
        Pals.send(ctx, from.optString("pub"), JSONObject().put("t", "nope").put("why", why))
    }

    private fun onLeave(ctx: Context, from: JSONObject, m: JSONObject) {
        val p = mine(ctx) ?: return
        if (p.optString("leader") != Pals.myId(ctx) || p.optString("id") != m.optString("party")) return
        p.put("members", JSONArray(p.optJSONArray("members").objs().filter { it.optString("id") != from.optString("id") }))
        save(ctx, p)
        publish(ctx)
    }

    // ---------- member ----------

    /** invites waiting for an answer: {party, from:{id,name}, members, at} */
    fun invites(ctx: Context): List<JSONObject> = runCatching { JSONArray(Prefs.of(ctx).getString("party.invites", "[]")) }.getOrDefault(JSONArray()).objs()
        .filter { System.currentTimeMillis() - it.optLong("at") < 6 * 3600_000L }
    private fun saveInvites(ctx: Context, l: List<JSONObject>) { Prefs.of(ctx).edit().putString("party.invites", JSONArray(l).toString()).apply(); Pals.bump() }

    private fun onInvite(ctx: Context, from: JSONObject, m: JSONObject) {
        val inv = JSONObject().put("party", m.optString("party")).put("from", JSONObject().put("id", from.optString("id")).put("name", from.optString("name")))
            .put("members", m.optJSONArray("members") ?: JSONArray()).put("at", System.currentTimeMillis())
        saveInvites(ctx, listOf(inv) + invites(ctx).filter { it.optJSONObject("from")?.optString("id") != from.optString("id") })
        Pals.notify(ctx, "${from.optString("name").take(24)} lädt dich in die Party ein", "Im Freunde-Menü (L2) beitreten")
    }

    /** answers an invite (or joins a friend's open party): sends `join` to the leader. null = sent */
    fun join(ctx: Context, leaderId: String, partyId: String): String? {
        val leader = Pals.friends(ctx).firstOrNull { it.optString("id") == leaderId } ?: return "Der Partyleiter ist nicht dein Freund"
        if (mine(ctx) != null) leave(ctx)
        save(ctx, JSONObject().put("id", partyId).put("leader", leaderId).put("leaderName", leader.optString("name")).put("since", System.currentTimeMillis()))
        saveInvites(ctx, invites(ctx).filter { it.optJSONObject("from")?.optString("id") != leaderId })
        val ok = Pals.send(ctx, leader.optString("pub"), JSONObject().put("t", "join").put("party", partyId))
        publish(ctx)
        return if (ok) null else "ntfy.sh nicht erreichbar"
    }

    fun decline(ctx: Context, leaderId: String) = saveInvites(ctx, invites(ctx).filter { it.optJSONObject("from")?.optString("id") != leaderId })

    /** a friend's open party (or the friend alone with "Party offen"): join it */
    fun joinOpen(ctx: Context, friendId: String): String? {
        val st = Pals.presenceOf(ctx, friendId) ?: return "Kein Status"
        val p = st.optJSONObject("party")
        return when {
            p != null && p.optString("leader") == friendId && p.optBoolean("open") -> join(ctx, friendId, p.optString("id"))
            p == null && st.optBoolean("open") -> "Noch keine Party – lade ihn ein, dann bist du Leiter"
            else -> "Party ist nicht offen"
        }
    }

    fun leave(ctx: Context) {
        val p = mine(ctx) ?: return
        save(ctx, null)
        followRoom(ctx, null, null)
        if (p.optString("leader") != Pals.myId(ctx))
            Pals.friends(ctx).firstOrNull { it.optString("id") == p.optString("leader") }?.let { Pals.send(ctx, it.optString("pub"), JSONObject().put("t", "leave").put("party", p.optString("id"))) }
        publish(ctx) // as leader: the members see the party is gone
    }

    /** a friend's status came in: as member, notice when the party is gone or we are not in it any more */
    private fun onStatus(ctx: Context, friend: JSONObject, st: JSONObject) {
        val p = mine(ctx) ?: return
        if (p.optString("leader") != friend.optString("id") || p.optString("leader") == Pals.myId(ctx)) return
        val lp = st.optJSONObject("party")
        val inIt = lp != null && lp.optString("id") == p.optString("id") && lp.optJSONArray("members").objs().any { it.optString("id") == Pals.myId(ctx) }
        if (inIt) {
            if (!p.optBoolean("in")) { p.put("in", true); save(ctx, p) }
            followRoom(ctx, friend, lp!!.optJSONObject("room"))
            lp.optJSONObject("game")?.let { followGame(ctx, friend, it) }
            return
        }
        // not in it (yet): a join just sent may not be in the leader's status yet
        if (p.optBoolean("in") || System.currentTimeMillis() - p.optLong("since") > 90_000) {
            save(ctx, null); publish(ctx); followRoom(ctx, null, null)
            Pals.notify(ctx, "Party von ${friend.optString("name").take(24)} ist vorbei", "")
        }
    }

    private fun onNope(ctx: Context, from: JSONObject, m: JSONObject) {
        val p = mine(ctx) ?: return
        if (p.optString("leader") == from.optString("id")) { save(ctx, null); publish(ctx) }
        Pals.notify(ctx, "Party von ${from.optString("name").take(24)}: ${m.optString("why").take(60)}", "")
    }

    @Volatile private var started = false

    /** hooks into Pals and sends the status now and every 3 h */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Pals.handlers["inv"] = ::onInvite
        Pals.handlers["join"] = ::onJoin
        Pals.handlers["leave"] = ::onLeave
        Pals.handlers["nope"] = ::onNope
        Pals.onStatus += ::onStatus
        thread(name = "party-heartbeat", isDaemon = true) {
            Thread.sleep(5000)
            while (true) { runCatching { Pals.publishStatus(app, status(app)) }; Thread.sleep(HEARTBEAT_MS) }
        }
    }

    /** for the friends menu */
    fun json(ctx: Context): JSONObject {
        val p = mine(ctx)
        return JSONObject().put("party", p?.let {
            JSONObject().put("id", it.optString("id")).put("leader", it.optString("leader")).put("leaderMe", it.optString("leader") == Pals.myId(ctx))
                .put("members", JSONArray(members(ctx).map { m -> JSONObject().put("id", m.optString("id")).put("name", m.optString("name")).put("me", m.optString("id") == Pals.myId(ctx)) }))
                .put("pending", it.optString("leader") != Pals.myId(ctx) && !it.optBoolean("in"))
        } ?: JSONObject.NULL).put("invites", JSONArray(invites(ctx).map { JSONObject().put("id", it.optJSONObject("from")?.optString("id")).put("name", it.optJSONObject("from")?.optString("name")).put("party", it.optString("party")).put("members", it.optJSONArray("members")?.length() ?: 0) }))
    }
}
