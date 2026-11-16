package wtf.lennard.thorbro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
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
 */
object Direct {
    const val OWNER_IP = "192.168.49.1"

    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var group: JSONObject? = null

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
        val name = "DIRECT-TB-" + (Build.MODEL ?: "Thor").filter { it.isLetterOrDigit() }.take(12)
        val pass = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val cfg = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).enablePersistentMode(false).build()
        call { l -> m.createGroup(c, cfg, l) }?.let { return status(ctx).put("error", it) }
        var g: WifiP2pGroup? = null
        repeat(10) { if (g == null) { Thread.sleep(500); g = groupInfo(m, c) } }
        val info = g ?: return status(ctx).put("error", "Netz wurde nicht aufgebaut")
        group = JSONObject().put("ssid", info.networkName).put("pass", info.passphrase).put("iface", info.`interface`)
        Logs.i("direct", "Wi-Fi Direct an: ${info.networkName}")
        return status(ctx)
    }

    fun stop(ctx: Context): JSONObject {
        val (m, c) = runCatching { init(ctx) }.getOrNull() ?: return status(ctx)
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
    fun inForeignGroup(): Boolean = group == null && Device.ip()?.startsWith("192.168.49.") == true

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
