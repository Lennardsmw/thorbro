package wtf.lennard.thorbro

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The friend network (protocol: companion/FRIENDS.md). Every ThorBro / RomBro has a key pair; its public half is its
 * "card" (id, name, key). Friends are cards we trust. Messages go to the receiver's own inbox topic on ntfy.sh,
 * encrypted for its key and signed with ours — ntfy sees neither names nor content, and the same code runs on the Mac.
 *
 * Becoming friends:
 *  - Code: one side shows an 8-button code (15 min), the other presses it; both swap cards and are friends at once.
 *  - File: "Teilen" makes `<Name>.thorfriend` (card + a one-time token) for LocalSend & co.; whoever imports it sends a
 *    request with the token, which is accepted by itself.
 *  - Nearby: devices in the same WLAN/hotspot show up as suggestions; adding one sends a request the other accepts.
 */
object Pals {
    private const val NTFY = "https://ntfy.sh"
    private const val CODE_LEN = 8
    private const val CODE_MINUTES = 15L
    private const val TOKEN_DAYS = 7L
    private val rnd = SecureRandom()

    /** bumped on every change, the UI redraws on it */
    @Volatile var version = 0
        private set
    private fun changed() { version++ }
    /** something the friends menu shows changed (party, invites) */
    internal fun bump() = changed()

    // ---------- small crypto helpers (plain JVM, the Mac uses the same) ----------

    fun b64(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)
    private fun sha(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    private fun hmac(key: ByteArray, label: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(label.toByteArray()) }
    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString("") { "%02x".format(it) }
    private fun pub(s: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(s)))
    private fun ecdh(mine: PrivateKey, theirs: PublicKey) = KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(theirs, true); generateSecret() }

    private fun gcm(key: ByteArray, encrypt: Boolean, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); doFinal(data) }

    /** id of a key: first 8 bytes of its SHA-256, hex */
    fun idOf(pubB64: String) = hex(sha(unb64(pubB64)), 8)

    /** the inbox topic of a key on ntfy.sh */
    fun inboxOf(pubB64: String) = "tbf-" + hex(sha("thorbro inbox v1".toByteArray(), unb64(pubB64)), 12)

    // ---------- me ----------

    @Volatile private var keys: KeyPair? = null

    @Synchronized
    fun keys(ctx: Context): KeyPair {
        keys?.let { return it }
        val p = Prefs.of(ctx)
        val kf = KeyFactory.getInstance("EC")
        val loaded = runCatching {
            KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(p.getString("pals.pub", null)!!))),
                kf.generatePrivate(PKCS8EncodedKeySpec(unb64(p.getString("pals.priv", null)!!))))
        }.getOrNull()
        val k = loaded ?: KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().also {
            p.edit().putString("pals.pub", b64(it.public.encoded)).putString("pals.priv", b64(it.private.encoded)).apply()
        }
        keys = k
        return k
    }

    fun myPub(ctx: Context) = b64(keys(ctx).public.encoded)
    fun myId(ctx: Context) = idOf(myPub(ctx))

    /** own name: set in the friends menu, else the device name */
    fun myName(ctx: Context) = Prefs.of(ctx).getString("pals.name", null)?.takeIf { it.isNotBlank() } ?: Me.name(ctx)
    fun setName(ctx: Context, name: String) {
        Prefs.of(ctx).edit().putString("pals.name", name.trim().take(24)).apply(); changed()
        friends(ctx).forEach { send(ctx, it.optString("pub"), JSONObject().put("t", "card")) } // friends see the new name
    }

    fun card(ctx: Context): JSONObject = JSONObject().put("v", 1).put("id", myId(ctx)).put("name", myName(ctx)).put("pub", myPub(ctx))
        .put("icon", JSONObject.NULL).put("app", "ThorBro")

    /** party: open (friends may join without invite) or invite only */
    fun partyOpen(ctx: Context) = Prefs.of(ctx).getBoolean("party.open", false)
    fun setPartyOpen(ctx: Context, on: Boolean) { Prefs.of(ctx).edit().putBoolean("party.open", on).apply(); changed(); Party.publish(ctx) }

    // ---------- lists (friends, requests) ----------

    private fun readList(ctx: Context, key: String): List<JSONObject> =
        runCatching { JSONArray(Prefs.of(ctx).getString(key, "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun writeList(ctx: Context, key: String, l: List<JSONObject>) { Prefs.of(ctx).edit().putString(key, JSONArray(l).toString()).apply(); changed() }

    fun friends(ctx: Context) = readList(ctx, "pals.friends")
    fun requests(ctx: Context) = readList(ctx, "pals.requests")
    fun isFriend(ctx: Context, id: String?) = id != null && friends(ctx).any { it.optString("id") == id }

    /** a checked card → a friend (or its name/key refreshed) */
    @Synchronized
    private fun addFriend(ctx: Context, card: JSONObject, via: String) {
        val id = card.optString("id")
        val old = friends(ctx).firstOrNull { it.optString("id") == id }
        val f = JSONObject().put("id", id).put("name", card.optString("name").take(24).ifEmpty { "Freund" }).put("pub", card.optString("pub"))
            .put("app", card.optString("app")).put("via", old?.optString("via") ?: via).put("since", old?.optLong("since") ?: System.currentTimeMillis())
            .put("seen", System.currentTimeMillis())
        writeList(ctx, "pals.friends", listOf(f) + friends(ctx).filter { it.optString("id") != id })
        writeList(ctx, "pals.requests", requests(ctx).filter { it.optString("id") != id })
        if (old == null) Logs.i("pals", "neuer Freund: ${f.optString("name")} ($via)")
    }

    /** removed or declined ids with the time: older messages from them (ntfy replays 12 h after a restart) are ignored */
    private fun tombstone(ctx: Context, id: String) {
        val o = runCatching { JSONObject(Prefs.of(ctx).getString("pals.removed", "{}")) }.getOrDefault(JSONObject())
        o.put(id, System.currentTimeMillis())
        Prefs.of(ctx).edit().putString("pals.removed", o.toString()).apply()
    }
    private fun removedAt(ctx: Context, id: String) = runCatching { JSONObject(Prefs.of(ctx).getString("pals.removed", "{}")).optLong(id) }.getOrDefault(0L)

    fun remove(ctx: Context, id: String) {
        tombstone(ctx, id)
        friends(ctx).firstOrNull { it.optString("id") == id }?.let { send(ctx, it.optString("pub"), JSONObject().put("t", "bye")) }
        writeList(ctx, "pals.friends", friends(ctx).filter { it.optString("id") != id })
    }

    fun accept(ctx: Context, id: String): Boolean {
        val r = requests(ctx).firstOrNull { it.optString("id") == id } ?: return false
        addFriend(ctx, r, r.optString("via", "Anfrage"))
        send(ctx, r.optString("pub"), JSONObject().put("t", "acc"))
        return true
    }

    fun decline(ctx: Context, id: String) = tombstone(ctx, id).let { writeList(ctx, "pals.requests", requests(ctx).filter { it.optString("id") != id }) }

    /** asks someone whose card we have (nearby, imported file) to be friends; [token] from a .thorfriend file */
    fun request(ctx: Context, card: JSONObject, token: String? = null, via: String = "Anfrage"): String? {
        if (!validCard(card)) return "Ungültige Karte"
        if (card.optString("id") == myId(ctx)) return "Das bist du selbst"
        val pending = readList(ctx, "pals.asked").filter { it.optString("id") != card.optString("id") }
        writeList(ctx, "pals.asked", listOf(JSONObject(card.toString()).put("via", via).put("at", System.currentTimeMillis())) + pending.take(30))
        return if (send(ctx, card.optString("pub"), JSONObject().put("t", "req").put("via", via).put("tok", token ?: JSONObject.NULL))) null
            else "ntfy.sh nicht erreichbar"
    }

    /** ids we sent a request to and have no answer yet */
    fun asked(ctx: Context) = readList(ctx, "pals.asked").map { it.optString("id") }.toSet()

    private fun validCard(c: JSONObject) = runCatching { pub(c.getString("pub")); idOf(c.getString("pub")) == c.getString("id") }.getOrDefault(false)

    // ---------- messages ----------

    /**
     * Wire format (one ntfy message on the receiver's inbox topic), all base64url:
     *   "tb1." + eph public key (X.509) + "." + nonce (12) + "." + AES-256-GCM(sealed)
     * key = HMAC-SHA256(ECDH(eph, receiver), "thorbro msg v1"); sealed = {"m": <json string>, "s": ECDSA(sender, m)}
     * and m = {"from": card, "to": receiver id, "ts": ms, "mid": random, "t": type, ...}.
     */
    fun seal(ctx: Context, toPub: String, msg: JSONObject): String {
        val m = JSONObject(msg.toString()).put("from", card(ctx)).put("to", idOf(toPub)).put("ts", System.currentTimeMillis())
            .put("mid", hex(ByteArray(8).also { rnd.nextBytes(it) }, 8)).toString()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys(ctx).private); update(m.toByteArray()); sign() }
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val key = hmac(ecdh(eph.private, pub(toPub)), "thorbro msg v1")
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val body = JSONObject().put("m", m).put("s", b64(sig)).toString().toByteArray()
        return "tb1." + b64(eph.public.encoded) + "." + b64(nonce) + "." + b64(gcm(key, true, nonce, body))
    }

    /** a message for us, checked (signature, ids, age), or null */
    fun unseal(ctx: Context, wire: String): JSONObject? = runCatching {
        val p = wire.trim().split('.')
        if (p.size != 4 || p[0] != "tb1") return null
        val key = hmac(ecdh(keys(ctx).private, KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(p[1])))), "thorbro msg v1")
        val body = JSONObject(String(gcm(key, false, unb64(p[2]), unb64(p[3]))))
        val mStr = body.getString("m")
        val m = JSONObject(mStr)
        val from = m.getJSONObject("from")
        if (!validCard(from) || m.optString("to") != myId(ctx)) return null
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub(from.getString("pub"))); update(mStr.toByteArray()); verify(unb64(body.getString("s"))) }
        if (!ok) return null
        if (Math.abs(System.currentTimeMillis() - m.optLong("ts")) > 24 * 3600_000L) return null // ntfy keeps 12 h
        m
    }.getOrNull()

    private fun post(topic: String, body: String, cache: Boolean = true) = runCatching {
        (URL("$NTFY/$topic").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
            if (!cache) setRequestProperty("Cache", "no")
            outputStream.use { it.write(body.toByteArray()) }
            (responseCode in 200..299).also { disconnect() }
        }
    }.onFailure { Logs.i("pals", "ntfy nicht erreichbar: ${it.message}") }.getOrDefault(false)

    /** to someone's inbox; kept 12 h on ntfy.sh, so a friend who is offline right now still gets it */
    fun send(ctx: Context, toPub: String, msg: JSONObject): Boolean = runCatching { post(inboxOf(toPub), seal(ctx, toPub, msg)) }.getOrDefault(false)

    /** handlers of other parts (party, chat) by message type */
    val handlers = java.util.concurrent.ConcurrentHashMap<String, (Context, JSONObject, JSONObject) -> Unit>()

    private val seenMids = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun handle(ctx: Context, m: JSONObject) {
        val mid = m.optString("mid")
        if (!seenMids.add(mid)) return
        if (seenMids.size > 500) synchronized(seenMids) { seenMids.iterator().let { it.next(); it.remove() } }
        val from = m.getJSONObject("from")
        val id = from.optString("id")
        val t = m.optString("t")
        val friend = isFriend(ctx, id)
        if (!friend && m.optLong("ts") <= removedAt(ctx, id)) return // from before we removed / declined them
        when {
            t == "req" -> when {
                friend -> { addFriend(ctx, from, "Anfrage"); send(ctx, from.optString("pub"), JSONObject().put("t", "acc")) }
                takeToken(ctx, m.optString("tok")) -> { addFriend(ctx, from, "Datei"); send(ctx, from.optString("pub"), JSONObject().put("t", "acc")) }
                id in asked(ctx) -> { addFriend(ctx, from, "In der Nähe"); send(ctx, from.optString("pub"), JSONObject().put("t", "acc")) } // both asked
                else -> {
                    val r = JSONObject(from.toString()).put("via", m.optString("via", "Anfrage")).put("at", System.currentTimeMillis())
                    writeList(ctx, "pals.requests", listOf(r) + requests(ctx).filter { it.optString("id") != id }.take(30))
                    Logs.i("pals", "Freundschaftsanfrage von ${from.optString("name")}")
                    notify(ctx, "${from.optString("name").take(24)} möchte dein Freund sein", "Im Freunde-Menü (L2) annehmen")
                }
            }
            t == "acc" && (id in asked(ctx) || friend) -> {
                writeList(ctx, "pals.asked", readList(ctx, "pals.asked").filter { it.optString("id") != id })
                addFriend(ctx, from, "Anfrage")
                notify(ctx, "${from.optString("name").take(24)} ist jetzt dein Freund", "")
            }
            !friend -> return // everything else only from friends
            t == "card" -> addFriend(ctx, from, "")
            t == "bye" -> writeList(ctx, "pals.friends", friends(ctx).filter { it.optString("id") != id })
            else -> {
                touch(ctx, id)
                handlers[t]?.let { h -> runCatching { h(ctx, from, m) }.onFailure { Logs.i("pals", "$t: ${it.message}") } }
            }
        }
    }

    private fun touch(ctx: Context, id: String) {
        val l = friends(ctx)
        l.firstOrNull { it.optString("id") == id }?.put("seen", System.currentTimeMillis()) ?: return
        Prefs.of(ctx).edit().putString("pals.friends", JSONArray(l).toString()).apply()
    }

    // ---------- status (online, party) ----------

    /**
     * Every device posts its status to its own status topic — only on changes and every 3 h — and its friends just read
     * it along (reading costs nothing; ntfy.sh allows 250 posts a day per IP). Encrypted with a key derived from the
     * card, so only who has the card can read it; signed like messages.
     */
    fun statusTopicOf(pubB64: String) = "tbf-s-" + hex(sha("thorbro status v1".toByteArray(), unb64(pubB64)), 12)
    private fun statusKey(pubB64: String) = hmac(sha(unb64(pubB64)), "thorbro status key v1")

    /** a status counts as "online" this long (heartbeat every 3 h) */
    const val ONLINE_MS = 3L * 3600_000 + 30 * 60_000

    fun publishStatus(ctx: Context, st: JSONObject): Boolean = runCatching {
        val m = JSONObject(st.toString()).put("id", myId(ctx)).put("name", myName(ctx)).put("ts", System.currentTimeMillis()).toString()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys(ctx).private); update(m.toByteArray()); sign() }
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val body = JSONObject().put("m", m).put("s", b64(sig)).toString().toByteArray()
        post(statusTopicOf(myPub(ctx)), "ts1." + b64(nonce) + "." + b64(gcm(statusKey(myPub(ctx)), true, nonce, body)))
    }.getOrDefault(false)

    private fun openStatus(friend: JSONObject, wire: String): JSONObject? = runCatching {
        val p = wire.trim().split('.')
        if (p.size != 3 || p[0] != "ts1") return null
        val pubS = friend.getString("pub")
        val body = JSONObject(String(gcm(statusKey(pubS), false, unb64(p[1]), unb64(p[2]))))
        val mStr = body.getString("m")
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub(pubS)); update(mStr.toByteArray()); verify(unb64(body.getString("s"))) }
        if (!ok) return null
        JSONObject(mStr).takeIf { it.optString("id") == friend.optString("id") }
    }.getOrNull()

    /** last status of every friend (id → status), kept across restarts */
    private val presence = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    @Volatile private var presenceLoaded = false
    private fun loadPresence(ctx: Context) {
        if (presenceLoaded) return
        presenceLoaded = true
        runCatching { JSONObject(Prefs.of(ctx).getString("pals.presence", "{}")) }.getOrNull()?.let { o -> o.keys().forEach { k -> presence[k] = o.getJSONObject(k) } }
    }
    fun presenceOf(ctx: Context, id: String): JSONObject? { loadPresence(ctx); return presence[id] }
    fun online(ctx: Context, id: String) = presenceOf(ctx, id)?.let { it.optBoolean("on") && System.currentTimeMillis() - it.optLong("ts") < ONLINE_MS } == true

    /** called when a friend's status arrives (party logic hooks in here) */
    val onStatus = java.util.concurrent.CopyOnWriteArrayList<(Context, JSONObject, JSONObject) -> Unit>()

    private fun gotStatus(ctx: Context, friend: JSONObject, st: JSONObject) {
        loadPresence(ctx)
        val id = friend.optString("id")
        if ((presence[id]?.optLong("ts") ?: 0) > st.optLong("ts")) return // older than what we have
        presence[id] = st
        Prefs.of(ctx).edit().putString("pals.presence", JSONObject(presence as Map<*, *>).toString()).apply()
        changed()
        onStatus.forEach { runCatching { it(ctx, friend, st) } }
    }

    // ---------- listening ----------

    @Volatile private var listening = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var topicsKey = ""

    /** the friend list changed: reconnect with the new set of status topics */
    private fun resubscribe() { runCatching { conn?.disconnect() } }

    /**
     * Always on while ThorBro runs: one stream for our inbox and the status topics of all friends. After a restart it
     * fetches what came meanwhile (ntfy keeps 12 h).
     */
    @Synchronized
    fun listen(ctx: Context) {
        if (listening) return
        listening = true
        val app = ctx.applicationContext
        val inbox = inboxOf(myPub(app))
        thread(name = "pals-inbox", isDaemon = true) {
            while (true) {
                runCatching {
                    val fr = friends(app)
                    val byTopic = fr.associateBy { statusTopicOf(it.optString("pub")) }
                    val topics = listOf(inbox) + byTopic.keys
                    topicsKey = topics.joinToString(",")
                    val since = Prefs.of(app).getLong("pals.sinceT", 0).takeIf { it > 0 }?.let { (it - 2).toString() } ?: "12h"
                    (URL("$NTFY/${topics.joinToString(",")}/json?since=$since").openConnection() as HttpURLConnection).run {
                        conn = this
                        connectTimeout = 8000; readTimeout = 90_000 // ntfy sends a keepalive every ~45 s
                        inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                                if (ev.optString("event") != "message") continue
                                if (!seenNtfy(app, ev.optString("id"))) continue
                                val topic = ev.optString("topic")
                                if (topic == inbox) unseal(app, ev.optString("message"))?.let { handle(app, it) }
                                else byTopic[topic]?.let { f -> openStatus(f, ev.optString("message"))?.let { gotStatus(app, f, it) } }
                                Prefs.of(app).edit().putLong("pals.sinceT", ev.optLong("time")).apply()
                            }
                        }
                        disconnect()
                    }
                }
                conn = null
                Thread.sleep(2000)
            }
        }
        // the friend list decides which status topics we read
        thread(name = "pals-watch", isDaemon = true) {
            var last = ""
            while (true) {
                Thread.sleep(2000)
                val k = friends(app).joinToString(",") { it.optString("id") }
                if (k != last) { if (last.isNotEmpty() || k.isNotEmpty()) resubscribe(); last = k }
            }
        }
        Logs.i("pals", "Postfach offen")
    }

    /** ntfy ids handled lately (the since-time overlaps by 2 s): true = new */
    private val ntfySeen = LinkedHashSet<String>()
    @Synchronized
    private fun seenNtfy(ctx: Context, id: String): Boolean {
        if (ntfySeen.isEmpty()) Prefs.of(ctx).getString("pals.seenIds", "")!!.split(',').filter { it.isNotEmpty() }.forEach { ntfySeen += it }
        if (!ntfySeen.add(id)) return false
        while (ntfySeen.size > 80) ntfySeen.remove(ntfySeen.first())
        Prefs.of(ctx).edit().putString("pals.seenIds", ntfySeen.joinToString(",")).apply()
        return true
    }

    internal fun notify(ctx: Context, title: String, text: String) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("friends", "Freunde", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(ctx, 7, Intent(ctx, wtf.lennard.thorbro.ui.ThorActivity::class.java)
            .putExtra("friends", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(title.hashCode(), android.app.Notification.Builder(ctx, "friends").setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }

    // ---------- code ----------

    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L

    private fun codeKey(c: String): ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(c.toCharArray(), "thorbro friend code v1".toByteArray(), 100_000, 256)).encoded
    private fun codeTopic(k: ByteArray) = "tbf-c-" + hex(hmac(k, "topic"), 12)
    private fun codeSeal(k: ByteArray, o: JSONObject): String {
        val n = ByteArray(12).also { rnd.nextBytes(it) }
        return b64(n) + "." + b64(gcm(hmac(k, "key"), true, n, o.toString().toByteArray()))
    }
    private fun codeOpen(k: ByteArray, s: String) = runCatching { s.split('.').let { JSONObject(String(gcm(hmac(k, "key"), false, unb64(it[0]), unb64(it[1])))) } }.getOrNull()

    /** the code currently shown (a new one when none runs); digits 0–7 → Online.SYMBOLS */
    @Synchronized
    fun myCode(ctx: Context, fresh: Boolean = false): JSONObject {
        val app = ctx.applicationContext
        if (fresh || code == null || System.currentTimeMillis() >= codeUntil) {
            val c = (1..CODE_LEN).map { rnd.nextInt(Online.SYMBOLS.size) }.joinToString("")
            code = c; codeUntil = System.currentTimeMillis() + CODE_MINUTES * 60_000
            val k = codeKey(c); val topic = codeTopic(k); val until = codeUntil
            thread(name = "pals-code", isDaemon = true) {
                while (code == c && System.currentTimeMillis() < until) {
                    runCatching {
                        (URL("$NTFY/$topic/json").openConnection() as HttpURLConnection).run {
                            connectTimeout = 8000; readTimeout = 60_000
                            inputStream.bufferedReader().useLines { lines ->
                                for (line in lines) {
                                    if (code != c) break
                                    val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                                    if (ev.optString("event") != "message") continue
                                    val m = codeOpen(k, ev.optString("message")) ?: continue
                                    val theirs = m.optJSONObject("card") ?: continue
                                    if (m.optString("t") != "pair" || !validCard(theirs) || theirs.optString("id") == myId(app)) continue
                                    addFriend(app, theirs, "Code")
                                    post(topic, codeSeal(k, JSONObject().put("t", "ok").put("re", m.optString("n")).put("card", card(app))), cache = false)
                                    notify(app, "${theirs.optString("name").take(24)} ist jetzt dein Freund", "per Freundes-Code")
                                }
                            }
                            disconnect()
                        }
                    }
                    if (code == c) Thread.sleep(3000)
                }
            }
        }
        val c = code!!
        return JSONObject().put("code", Online.codeText(c)).put("digits", Online.codeDigits(c))
            .put("minutes", (codeUntil - System.currentTimeMillis()) / 60_000 + 1)
    }

    fun stopCode() { code = null }

    /** the other side's code pressed here → friends with it; returns its name or throws */
    fun redeem(ctx: Context, input: String, job: Jobs.Job? = null): String {
        val c = Online.normalize(input) ?: throw IllegalArgumentException("Der Code hat $CODE_LEN Tasten")
        if (c == code) throw IllegalArgumentException("Das ist dein eigener Code")
        job?.let { it.message = "Suche den Code …"; it.progress = 0.2 }
        val k = codeKey(c); val topic = codeTopic(k)
        val n = hex(ByteArray(8).also { rnd.nextBytes(it) }, 8)
        var got: JSONObject? = null
        val end = System.currentTimeMillis() + 25_000
        thread(name = "pals-redeem", isDaemon = true) {
            runCatching {
                (URL("$NTFY/$topic/json").openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 30_000
                    inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (got != null || System.currentTimeMillis() > end) break
                            val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            val m = codeOpen(k, ev.optString("message")) ?: continue
                            if (m.optString("t") == "ok" && m.optString("re") == n) m.optJSONObject("card")?.takeIf { validCard(it) }?.let { got = it }
                        }
                    }
                    disconnect()
                }
            }
        }
        Thread.sleep(800) // let the subscription stand before asking
        val ask = codeSeal(k, JSONObject().put("t", "pair").put("n", n).put("card", card(ctx)))
        var next = 0L
        while (got == null && System.currentTimeMillis() < end) {
            if (System.currentTimeMillis() >= next) { post(topic, ask, cache = false); next = System.currentTimeMillis() + 4000 }
            Thread.sleep(200)
        }
        val theirs = got ?: throw IllegalStateException("Niemand mit diesem Code gefunden. Stimmt er, und ist das Freunde-Menü dort offen?")
        addFriend(ctx, theirs, "Code")
        return theirs.optString("name")
    }

    // ---------- file (LocalSend & co.) ----------

    private fun takeToken(ctx: Context, tok: String?): Boolean {
        if (tok.isNullOrEmpty()) return false
        val all = readList(ctx, "pals.tokens").filter { it.optLong("until") > System.currentTimeMillis() }
        val hit = all.any { it.optString("tok") == tok }
        if (hit) writeList(ctx, "pals.tokens", all.filter { it.optString("tok") != tok })
        return hit
    }

    /** `<Name>.thorfriend`: card + one-time token (7 days) — whoever imports it becomes a friend without asking */
    fun shareFile(ctx: Context): File {
        val tok = hex(ByteArray(12).also { rnd.nextBytes(it) }, 12)
        val l = readList(ctx, "pals.tokens").filter { it.optLong("until") > System.currentTimeMillis() }
        writeList(ctx, "pals.tokens", (l + JSONObject().put("tok", tok).put("until", System.currentTimeMillis() + TOKEN_DAYS * 86_400_000)).takeLast(20))
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val safe = myName(ctx).replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "").trim().ifEmpty { "ThorBro" }
        return File(dir, "$safe.thorfriend").apply { writeText(card(ctx).put("tok", tok).toString()) }
    }

    /** Android share sheet (LocalSend, Quick Share, Messenger …) with the friend file */
    fun shareIntent(ctx: Context): Intent {
        val f = shareFile(ctx)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Mein ThorBro-Freundes-Code").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Freundes-Code teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** a .thorfriend file's text → request sent (friends as soon as the other side sees it); null = ok, else why not */
    fun import(ctx: Context, text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return "Keine Freundes-Datei"
        if (isFriend(ctx, o.optString("id"))) return null
        return request(ctx, o, o.optString("tok").ifEmpty { null }, "Datei")
    }

    /** .thorfriend files LocalSend (or anything else) put into Download: imported once, then deleted */
    fun importDownloads(ctx: Context): Int {
        val root = File("/storage/emulated/0/Download")
        val files = root.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".thorfriend") }.toList()
        var n = 0
        files.forEach { f ->
            val err = runCatching { import(ctx, f.readText()) }.getOrElse { it.message }
            if (err == null || err == "Das bist du selbst") { f.delete(); if (err == null) n++ }
            else Logs.i("pals", "${f.name}: $err")
        }
        return n
    }

    // ---------- nearby ----------

    @Volatile var nearby: List<JSONObject> = emptyList()
        private set

    /** ThorBro/RomBro devices in this WLAN/hotspot that are no friends yet (their card comes with the lobby info) */
    fun scanNearby(ctx: Context): List<JSONObject> {
        val me = myId(ctx)
        val l = Netplay.peers(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.mapNotNull { p ->
            val c = p.optJSONObject("lobby")?.optJSONObject("card") ?: return@mapNotNull null
            if (!validCard(c) || c.optString("id") == me || isFriend(ctx, c.optString("id"))) return@mapNotNull null
            JSONObject(c.toString()).put("address", p.optString("address")).put("asked", c.optString("id") in asked(ctx))
        }.distinctBy { it.optString("id") }
        nearby = l; changed()
        return l
    }

    /** everything the friends menu shows */
    fun status(ctx: Context): JSONObject {
        val asked = asked(ctx)
        return JSONObject().put("me", card(ctx)).put("partyOpen", partyOpen(ctx))
            .put("friends", JSONArray(friends(ctx).map { f ->
                val id = f.optString("id")
                val st = presenceOf(ctx, id)
                val p = st?.optJSONObject("party")
                JSONObject(f.toString()).apply {
                    remove("pub")
                    put("online", online(ctx, id))
                    if (st != null) put("seen", maxOf(f.optLong("seen"), st.optLong("ts")))
                    // a party the friend leads and that is open (or the friend alone with "Party offen")
                    put("joinable", online(ctx, id) && ((p != null && p.optString("leader") == id && p.optBoolean("open")) || (p == null && st?.optBoolean("open") == true)))
                    put("inParty", p != null)
                    put("partySize", p?.optJSONArray("members")?.length() ?: 0)
                }
            }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("online") }.thenByDescending { it.optLong("seen") })))
            .put("party", Party.json(ctx).opt("party")).put("invites", Party.json(ctx).optJSONArray("invites"))
            .put("requests", JSONArray(requests(ctx).map { JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("via", it.optString("via")) }))
            .put("nearby", JSONArray(nearby.filter { !isFriend(ctx, it.optString("id")) }.map {
                JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("app", it.optString("app"))
                    .put("asked", it.optString("id") in asked)
            }))
    }

    fun addNearby(ctx: Context, id: String): String? {
        val c = nearby.firstOrNull { it.optString("id") == id } ?: return "Nicht mehr in der Nähe"
        return request(ctx, JSONObject().put("id", c.optString("id")).put("name", c.optString("name")).put("pub", c.optString("pub")), null, "In der Nähe")
    }
}
