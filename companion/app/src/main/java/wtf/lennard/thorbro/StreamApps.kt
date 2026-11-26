package wtf.lennard.thorbro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Games→Streaming: the apps Sunshine offers (games with cover on the Mac), started through Moonlight.
 *
 * The list comes from Sunshine itself (`GET https://<host>:47990/api/apps`, needs the Sunshine web login, stored with
 * [setLogin]) or is pushed by RomBro (`POST /api/stream/apps`). It is cached, so it also shows while the Mac is off.
 * Covers: an icon that came with the push, else the libretro thumbnail from the scraping DB ([Media.coverByName]).
 * Shortcuts are favourites of this list (setting `stream.shortcuts`, same name as in RomBro).
 */
object StreamApps {
    private const val SHORTCUTS = "stream.shortcuts"
    private fun dir(ctx: Context) = File(ctx.filesDir, "stream").apply { mkdirs() }
    private fun store(ctx: Context) = File(dir(ctx), "apps.json")
    private fun key(name: String) = MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun read(ctx: Context): JSONObject =
        runCatching { JSONObject(store(ctx).readText()) }.getOrDefault(JSONObject().put("apps", JSONArray()))

    @Synchronized
    private fun write(ctx: Context, o: JSONObject) { store(ctx).writeText(o.toString()) }

    fun shortcuts(ctx: Context): Set<String> =
        runCatching { JSONArray(Prefs.of(ctx).getString(SHORTCUTS, "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
            .getOrDefault(emptySet())

    fun setShortcut(ctx: Context, name: String, on: Boolean) {
        val s = shortcuts(ctx).toMutableSet()
        if (on) s += name else s -= name
        Prefs.of(ctx).edit().putString(SHORTCUTS, JSONArray(s.toList()).toString()).apply()
    }

    fun setLogin(ctx: Context, user: String, pass: String) {
        Prefs.of(ctx).edit().putString("stream.sunshine.user", user).putString("stream.sunshine.pass", pass).apply()
    }

    fun hasLogin(ctx: Context) = !Prefs.of(ctx).getString("stream.sunshine.pass", null).isNullOrEmpty()

    /** cached list: {apps:[{name, system?, icon?}], host:{name, address, uuid}?, updated} plus shortcut flags */
    fun list(ctx: Context): JSONObject {
        val o = read(ctx)
        val sc = shortcuts(ctx)
        val apps = o.optJSONArray("apps") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            out.put(JSONObject().put("name", a.getString("name")).put("system", a.optString("system"))
                .put("shortcut", a.getString("name") in sc).put("hasIcon", File(dir(ctx), key(a.getString("name")) + ".png").length() > 0))
        }
        return JSONObject().put("apps", out).put("host", o.optJSONObject("host")).put("updated", o.optLong("updated"))
            .put("source", o.optString("source")).put("login", hasLogin(ctx))
    }

    /** RomBro (or anyone with the pairing code) hands over the list: [{name, system?, icon? (base64 PNG)}] */
    fun push(ctx: Context, body: JSONObject): JSONObject {
        val arr = body.optJSONArray("apps") ?: throw IllegalArgumentException("apps fehlt")
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.opt(i)
            val name = (if (a is JSONObject) a.optString("name") else a?.toString().orEmpty()).trim()
            if (name.isEmpty() || name.length > 512 || name.any { it.isISOControl() }) continue
            val e = JSONObject().put("name", name)
            if (a is JSONObject) {
                a.optString("system").takeIf { it in Media.libretro }?.let { e.put("system", it) }
                a.optString("icon").takeIf { it.isNotEmpty() }?.let { b64 ->
                    runCatching { File(dir(ctx), key(name) + ".png").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) }
                }
            }
            keep.put(e)
        }
        val o = read(ctx).put("apps", keep).put("updated", System.currentTimeMillis()).put("source", "push")
        body.optJSONObject("host")?.let { o.put("host", it) }
        write(ctx, o)
        return list(ctx)
    }

    /** asks Sunshine directly (needs the login) and finds the host's id; no login or no Mac → the cached list stays */
    fun refresh(ctx: Context): JSONObject {
        val hosts = runCatching { Streaming.hosts(ctx, 2500) }.getOrNull() ?: JSONArray()
        val h = (0 until hosts.length()).map { hosts.getJSONObject(it) }.firstOrNull()
        val o = read(ctx)
        if (h != null) {
            val uuid = runCatching { serverUuid(h.getString("address")) }.getOrNull()
            if (uuid != null) o.put("host", JSONObject().put("name", h.getString("name")).put("address", h.getString("address")).put("uuid", uuid))
            val user = Prefs.of(ctx).getString("stream.sunshine.user", "") ?: ""
            val pass = Prefs.of(ctx).getString("stream.sunshine.pass", "") ?: ""
            if (pass.isNotEmpty()) runCatching {
                val names = sunshineApps(h.getString("address"), user, pass)
                val keep = JSONArray()
                val old = o.optJSONArray("apps")
                names.forEach { n ->
                    val e = JSONObject().put("name", n)
                    if (old != null) for (i in 0 until old.length()) if (old.getJSONObject(i).getString("name") == n) old.getJSONObject(i).optString("system").takeIf { it.isNotEmpty() }?.let { e.put("system", it) }
                    keep.put(e)
                }
                o.put("apps", keep).put("updated", System.currentTimeMillis()).put("source", "sunshine")
            }.onFailure { Logs.i("stream", "Sunshine-Liste: ${it.message}") }
            write(ctx, o)
        }
        return list(ctx)
    }

    /** `uniqueid` of a Sunshine host (public, no pairing needed) */
    private fun serverUuid(addr: String): String? {
        val c = URL("http://${if (addr.contains(':')) "[$addr]" else addr}:47989/serverinfo").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            .let { Regex("<uniqueid>([^<]+)</uniqueid>").find(it)?.groupValues?.get(1) }
    }

    private fun sunshineApps(addr: String, user: String, pass: String): List<String> {
        val c = URL("https://${if (addr.contains(':')) "[$addr]" else addr}:47990/api/apps").openConnection() as HttpsURLConnection
        // Sunshine's certificate is self-signed; this one call goes to the LAN host found by mDNS only
        val trust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        })
        c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, trust, null) }.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        c.connectTimeout = 4000; c.readTimeout = 6000
        c.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP))
        if (c.responseCode != 200) throw IllegalStateException("Sunshine: HTTP ${c.responseCode}")
        val apps = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).getJSONArray("apps")
        return (0 until apps.length()).map { apps.getJSONObject(it).getString("name") }
    }

    /** picture of an app: the icon that came with the list, else the scraped libretro cover */
    fun icon(ctx: Context, name: String): File? {
        File(dir(ctx), key(name) + ".png").takeIf { it.length() > 0 }?.let { return it }
        val system = (read(ctx).optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("name") == name } })
            ?.optString("system")?.takeIf { it.isNotEmpty() }
        return runCatching { Media.coverByName(ctx, name, system) }.getOrNull()
    }

    fun play(ctx: Context, name: String, hdr: Boolean = false): JSONObject {
        var uuid = read(ctx).optJSONObject("host")?.optString("uuid").orEmpty()
        if (uuid.isEmpty()) { refresh(ctx); uuid = read(ctx).optJSONObject("host")?.optString("uuid").orEmpty() }
        if (uuid.isEmpty()) throw IllegalStateException("Kein Sunshine-Rechner gefunden. Läuft Sunshine und ist der Mac im selben WLAN?")
        return Streaming.moonlightPlay(ctx, uuid, name, null, hdr)
    }
}
