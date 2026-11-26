package wtf.lennard.thorbro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import org.json.JSONArray
import android.os.Build
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi Direct: the Thor opens its own small network (no router, no internet needed).
 * The group owner looks like a normal Wi-Fi network (DIRECT-TB-…) to any phone or Thor, so the
 * other device joins it in its normal Wi-Fi settings — it does not need ThorBro for that.
 * Over it:
 *  - ThorBro itself: http://192.168.49.1:7330/get offers the APK (for a Thor that has no ThorBro yet),
 *  - games: the Multiplayer lobby works as in a normal WLAN (the guest also probes the group owner).
 *
 * Easy to type: the password is "thor" + 4 digits and stays the same on this Thor, so a device that joined once
 * reconnects by itself.
 *
 * Invites without any shared network: while the net runs, ThorBro announces itself over Wi-Fi Direct service discovery
 * (radio, like Bluetooth, ~10–30 m) with network name, password and the open lobby (game + PIN). Another Thor with
 * ThorBro sees "X lädt ein" in Multiplayer and joins with one button: it connects to the net (no Android dialog,
 * API 29+) and enters the lobby. Anyone nearby with a Wi-Fi Direct scanner could read that — fine for a game lobby.
 */
object Direct {
    const val OWNER_IP = "192.168.49.1"

    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var group: JSONObject? = null
    @Volatile private var advertising = false
    private const val SERVICE = "_thorbro._tcp"

    fun active() = group != null

    /** set while the permission dialog is open: what to do once it was allowed */
    @Volatile var afterPermission: (() -> Unit)? = null

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    fun permitted(ctx: Context) = ctx.checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED

    private fun init(ctx: Context): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val m = manager ?: ctx.getSystemService(WifiP2pManager::class.java)?.also { manager = it }
            ?: throw IllegalStateException("Dieses Gerät kann kein Wi-Fi Direct")
        val c = channel ?: m.initialize(ctx.applicationContext, Looper.getMainLooper(), null).also { channel = it }
        return m to c
    }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct wird nicht unterstützt"
        WifiP2pManager.BUSY -> "WLAN ist beschäftigt – WLAN einschalten und nochmal versuchen"
        else -> "Wi-Fi Direct-Fehler ($code) – ist das WLAN eingeschaltet?"
    }

    /** blocks until Android answered an action listener call */
    private fun call(block: (WifiP2pManager.ActionListener) -> Unit): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        block(object : WifiP2pManager.ActionListener {
            override fun onSuccess() { latch.countDown() }
            override fun onFailure(r: Int) { err = reason(r); latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) return "Keine Antwort vom WLAN-Dienst"
        return err
    }

    @Suppress("MissingPermission")
    private fun groupInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        var g: WifiP2pGroup? = null
        m.requestGroupInfo(c) { g = it; latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return g
    }

    /** starts the network (call off the main thread) */
    @Suppress("MissingPermission")
    fun start(ctx: Context): JSONObject {
        if (!permitted(ctx)) return status(ctx).put("error", "Berechtigung „Geräte in der Nähe“ fehlt – am Thor in ThorBro erlauben").put("code", "permission")
        val (m, c) = init(ctx)
        groupInfo(m, c)?.let { call { l -> m.removeGroup(c, l) }; Thread.sleep(500) }
        // own id in the name: two Thors of the same model must not open the same network
        val name = "DIRECT-TB-" + Me.name(ctx).filter { it.isLetterOrDigit() }.take(12) + "-" + Me.id(ctx)
        val pass = easyPass(ctx)
        val cfg = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).enablePersistentMode(false).build()
        call { l -> m.createGroup(c, cfg, l) }?.let { return status(ctx).put("error", it) }
        var g: WifiP2pGroup? = null
        repeat(10) { if (g == null) { Thread.sleep(500); g = groupInfo(m, c) } }
        val info = g ?: return status(ctx).put("error", "Netz wurde nicht aufgebaut")
        group = JSONObject().put("ssid", info.networkName).put("pass", info.passphrase).put("iface", info.`interface`)
        Logs.i("direct", "Wi-Fi Direct an: ${info.networkName}")
        refreshInvite(ctx)
        return status(ctx)
    }

    /** "thor" + 4 digits, kept per device (WPA2 needs at least 8 characters) */
    private fun easyPass(ctx: Context): String {
        val p = Prefs.of(ctx)
        p.getString("direct.pass", null)?.let { return it }
        return ("thor" + (1000..9999).random()).also { p.edit().putString("direct.pass", it).apply() }
    }

    fun stop(ctx: Context): JSONObject {
        val (m, c) = runCatching { init(ctx) }.getOrNull() ?: return status(ctx)
        advertising = false
        call { l -> m.clearLocalServices(c, l) }
        call { l -> m.removeGroup(c, l) }
        group = null
        Logs.i("direct", "Wi-Fi Direct aus")
        return status(ctx)
    }

    /** IPv4 of the p2p interface while the group runs */
    fun ownIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.name.startsWith("p2p") }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }.getOrNull()

    /** true when this device sits in someone else's Wi-Fi Direct network */
    fun inForeignGroup(): Boolean = group == null && (ownIp() ?: Device.ip())?.startsWith("192.168.49.") == true

    // ---------- invites over the air ----------

    /** (re)announces this Thor with the current net + lobby; call when the net or the lobby changes */
    @Suppress("MissingPermission")
    fun refreshInvite(ctx: Context) {
        val g = group ?: return
        if (!permitted(ctx)) return
        val (m, c) = runCatching { init(ctx) }.getOrNull() ?: return
        val l = Netplay.lobby
        val txt = mapOf(
            "n" to Me.name(ctx), "i" to Me.id(ctx),
            "s" to g.optString("ssid"), "p" to g.optString("pass"),
            "g" to (l?.public()?.optString("game")?.take(40) ?: ""),
            "m" to (l?.mode ?: ""), "k" to (l?.pin ?: ""),
        )
        call { cb -> m.clearLocalServices(c, cb) }
        call { cb -> m.addLocalService(c, WifiP2pDnsSdServiceInfo.newInstance("ThorBro", SERVICE, txt), cb) }
        if (!advertising) {
            advertising = true
            // others only see the service while this device is in "find" mode: keep it there while the net runs
            Thread({
                while (advertising && group != null) {
                    runCatching { m.discoverPeers(c, null) }
                    Thread.sleep(25_000)
                }
            }, "direct-announce").apply { isDaemon = true }.start()
        }
    }

    /** Thors nearby that announce a ThorBro net (radio scan, a few seconds; call off the main thread) */
    @Suppress("MissingPermission")
    @Synchronized // the screen and the background (AutoPlay) both scan
    fun invites(ctx: Context, ms: Long = 6000): JSONArray {
        val out = JSONArray()
        if (!permitted(ctx)) return out
        val (m, c) = runCatching { init(ctx) }.getOrNull() ?: return out
        val found = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        m.setDnsSdResponseListeners(c, null) { full, rec, dev ->
            if (full.contains(SERVICE.removePrefix("_").substringBefore('.'), true) && rec.isNotEmpty()) found[dev.deviceAddress] = HashMap(rec)
        }
        val req = WifiP2pDnsSdServiceRequest.newInstance(SERVICE)
        call { l -> m.clearServiceRequests(c, l) }
        call { l -> m.addServiceRequest(c, req, l) }
        val err = call { l -> m.discoverServices(c, l) }
        if (err != null) Logs.i("direct", "Suche: $err")
        Thread.sleep(ms)
        call { l -> m.removeServiceRequest(c, req, l) }
        val mine = group?.optString("ssid")
        val me = Me.id(ctx)
        found.forEach { (addr, r) ->
            if (r["s"].isNullOrEmpty() || r["s"] == mine || r["i"] == me) return@forEach
            out.put(JSONObject().put("device", addr).put("id", r["i"].orEmpty()).put("friend", Friends.isFriend(ctx, r["i"]))
                .put("name", r["n"] ?: "Thor").put("ssid", r["s"]).put("pass", r["p"])
                .put("game", r["g"].orEmpty()).put("mode", r["m"].orEmpty()).put("pin", r["k"].orEmpty()))
        }
        return out
    }

    /** joins another Thor's net without the Android dialog and waits for an address in it */
    @Suppress("MissingPermission")
    fun join(ctx: Context, ssid: String, pass: String): String {
        if (!permitted(ctx)) throw IllegalStateException("Berechtigung „Geräte in der Nähe“ fehlt")
        if (inForeignGroup()) return ownIp()!!
        val (m, c) = init(ctx)
        if (group != null) stop(ctx)
        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
        call { l -> m.connect(c, cfg, l) }?.let { throw IllegalStateException(it) }
        repeat(40) {
            ownIp()?.takeIf { it.startsWith("192.168.49.") }?.let { Logs.i("direct", "Mit $ssid verbunden ($it)"); return it }
            Thread.sleep(500)
        }
        throw IllegalStateException("Verbindung mit $ssid kam nicht zustande")
    }

    fun status(ctx: Context): JSONObject {
        val g = group
        val ip = ownIp() ?: OWNER_IP
        return JSONObject()
            .put("active", g != null)
            .put("permission", permitted(ctx))
            .put("ssid", g?.optString("ssid"))
            .put("pass", g?.optString("pass"))
            .put("ip", if (g != null) ip else null)
            .put("download", if (g != null) "http://$ip:${ThorService.PORT}/get" else null)
            .put("lanDownload", Device.ip()?.let { "http://$it:${ThorService.PORT}/get" })
    }

    // ---------- passing ThorBro on ----------

    /** the installed ThorBro APK (base.apk; ThorBro ships as a single APK) */
    fun apk(ctx: Context) = File(ctx.applicationInfo.sourceDir)

    /** Android share sheet (Quick Share, Bluetooth …) with ThorBro's APK */
    fun shareIntent(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val copy = File(dir, "ThorBro.apk")
        apk(ctx).copyTo(copy, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "ThorBro senden").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** tiny page for the other device's browser */
    fun page(ctx: Context): String {
        val v = Updater.current(ctx)
        return """<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ThorBro</title><body style="font-family:system-ui;background:#111;color:#eee;text-align:center;padding:40px 16px">
<h1>ThorBro</h1><p>Version $v · ${"%.1f".format(apk(ctx).length() / 1e6)} MB</p>
<p><a href="/thorbro.apk" style="display:inline-block;background:#fff;color:#000;padding:16px 28px;border-radius:14px;text-decoration:none;font-size:1.3em">Herunterladen</a></p>
<p style="color:#999">Danach die Datei öffnen und „Installieren“ tippen (einmal „Aus dieser Quelle zulassen“ erlauben).</p></body></html>"""
    }
}
