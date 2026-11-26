package wtf.lennard.thorbro

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Game streaming PC/Mac → Thor with Moonlight. ThorBro installs and updates Moonlight from its
 * GitHub releases and finds Sunshine hosts in the LAN (mDNS _nvstream._tcp). Pairing stays
 * Moonlight's own: it shows a PIN on the Thor, the PIN goes into Sunshine's web UI (port 47990).
 */
object Streaming {
    const val MOONLIGHT = "com.limelight"
    private const val RELEASES = "https://api.github.com/repos/moonlight-stream/moonlight-android/releases/latest"

    fun status(ctx: Context): JSONObject {
        val info = runCatching { ctx.packageManager.getPackageInfo(MOONLIGHT, 0) }.getOrNull()
        val out = JSONObject().put("installed", info != null).put("version", info?.versionName)
        latest()?.let { (v, _) ->
            out.put("latest", v)
            if (info?.versionName != null) out.put("updateAvailable", Updater.newer(v, info.versionName!!))
        }
        out.put("package", moonlightPackage(ctx)).put("usb", usbAdb(ctx)).put("tunnel", MoonTunnel.status())
        return out
    }

    // ---- Play on Thor: based on catzeyea/ThorBro src/pcstream/thor.rs (MIT, (c) 2026 ThorBro contributors) ----
    // Moonlight's exported ShortcutTrampoline with the Sunshine host UUID and an app, the same intent its own pinned
    // shortcuts use. Every extra must be a *string* (Moonlight reads them with getStringExtra). No app → the host's app grid.

    const val MOONLIGHT_ROOT = "com.limelight.root"
    private const val TRAMPOLINE = "com.limelight.ShortcutTrampoline"
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** installed Moonlight package, normal build preferred (both flavors share the com.limelight namespace) */
    fun moonlightPackage(ctx: Context): String? = listOf(MOONLIGHT, MOONLIGHT_ROOT).firstOrNull { p ->
        runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
    }

    /** starts [app] (name as in Sunshine's list) or [appId] on host [uuid]; throws with a German message on bad input */
    fun moonlightPlay(ctx: Context, uuid: String, app: String?, appId: String?, hdr: Boolean): JSONObject {
        val pkg = moonlightPackage(ctx) ?: throw IllegalStateException("Moonlight ist nicht installiert")
        if (!UUID_RE.matches(uuid)) throw IllegalArgumentException("uuid ist keine Sunshine-Host-ID (uniqueid aus /serverinfo)")
        val name = app?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && (name.length > 512 || name.any { it.isISOControl() })) throw IllegalArgumentException("ungültiger App-Name")
        val id = appId?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && !id.all { it.isDigit() }) throw IllegalArgumentException("appId muss eine Zahl sein")
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setClassName(pkg, TRAMPOLINE)
            .putExtra("UUID", uuid)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (name != null) i.putExtra("AppName", name)
        if (id != null) i.putExtra("AppId", id)
        if (hdr) i.putExtra("HDR", "1")
        Emulators.startOnDisplay(ctx, i)
        Logs.i("stream", "Moonlight: ${name ?: id ?: "App-Liste"} auf $uuid" + if (usbAdb(ctx)) " (über USB)" else "")
        return JSONObject().put("ok", true).put("package", pkg).put("usb", usbAdb(ctx))
    }

    /** opens Moonlight's host list (PcView) */
    fun moonlightOpen(ctx: Context): JSONObject {
        val pkg = moonlightPackage(ctx) ?: throw IllegalStateException("Moonlight ist nicht installiert")
        if (!Apps.launch(ctx, pkg)) throw IllegalStateException("Moonlight lässt sich nicht starten")
        return JSONObject().put("ok", true).put("package", pkg)
    }

    @Volatile private var latestCache: Triple<Long, String, String>? = null

    /** (version, apk url) of the newest Moonlight release, cached for an hour */
    private fun latest(): Pair<String, String>? {
        latestCache?.let { if (System.currentTimeMillis() - it.first < 3600_000) return it.second to it.third }
        return runCatching {
            val j = JSONObject(get(RELEASES))
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").contains("nonRoot", true) && it.optString("name").endsWith(".apk") }
                ?: return null
            val v = j.getString("tag_name").removePrefix("v")
            latestCache = Triple(System.currentTimeMillis(), v, apk.getString("browser_download_url"))
            v to apk.getString("browser_download_url")
        }.getOrNull()
    }

    /** downloads the newest Moonlight and starts the install (confirm on the Thor) */
    fun install(ctx: Context, job: Jobs.Job): JSONObject {
        val (v, url) = latest() ?: throw IllegalStateException("Moonlight-Release nicht erreichbar")
        job.message = "Moonlight $v laden"
        val apk = File(ctx.cacheDir, "moonlight.apk")
        val c = open(url)
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { i -> apk.outputStream().use { o ->
            val buf = ByteArray(1 shl 16); var done = 0L
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                if (total > 0) job.progress = 0.9 * done / total
            }
        } }
        val pkg = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
        if (pkg != MOONLIGHT) { apk.delete(); throw IllegalStateException("Download ist keine Moonlight-APK") }
        job.message = "Installation auf dem Thor bestätigen"
        Logs.i("stream", "installiere Moonlight $v")
        val id = Installer.install(ctx, apk)
        return JSONObject().put("session", id).put("version", v)
    }

    /** Sunshine/GeForce hosts in the LAN; searches for [ms] milliseconds */
    fun hosts(ctx: Context, ms: Long = 3000): JSONArray {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { found[s.serviceName] = s }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        nsd.discoverServices("_nvstream._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        Thread.sleep(ms)
        runCatching { nsd.stopServiceDiscovery(l) }
        // resolve one after the other; older Android versions allow only one resolve at a time
        return JSONArray(found.values.mapNotNull { s ->
            val latch = CountDownLatch(1)
            var res: NsdServiceInfo? = null
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { latch.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) { res = i; latch.countDown() }
            })
            latch.await(3, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            val addr = res?.host?.hostAddress ?: return@mapNotNull null
            val ip = if (addr.contains(':')) "[$addr]" else addr
            JSONObject().put("name", s.serviceName).put("address", addr)
                .put("web", "https://$ip:47990/pin")
        })
    }

    // ---- scrcpy: Thor screen on the Mac, through wireless debugging ----
    // adbd is never restarted (no `adb tcpip`): Shizuku runs on the same adbd and would die with it.
    // The Mac pairs once (adb pair, code from the Thor's pairing dialog), then connects to the TLS port.

    /** wireless debugging state and the command for the Mac */
    fun scrcpy(ctx: Context): JSONObject {
        val wifi = runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled") == 1 }.getOrDefault(false)
        val port = prop("service.adb.tls.port")?.toIntOrNull()?.takeIf { it > 0 }
        val ip = wtf.lennard.thorbro.ui.ipv4().first
        val out = JSONObject().put("shell", Shell.state()).put("wifi", wifi).put("ip", ip).put("port", port)
            .put("usb", usbAdb(ctx)).put("usbCommand", "scrcpy -d")
        if (wifi && port != null && ip != null) {
            out.put("address", "$ip:$port")
            out.put("command", "scrcpy --tcpip=$ip:$port")
        }
        return out
    }

    /**
     * a computer is on the cable with USB debugging (sticky USB_STATE broadcast). Rule for every
     * Thor ↔ Mac path: a cable beats the air – RomBro uses USB then and falls back to Wi-Fi when it goes.
     */
    fun usbAdb(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE")) ?: return false
        return i.getBooleanExtra("connected", false) && i.getBooleanExtra("adb", false)
    }

    /** switches wireless debugging on (via Shizuku); the port appears a moment later */
    fun scrcpyWifi(ctx: Context): JSONObject {
        if (!Shell.run("settings", "put", "global", "adb_wifi_enabled", "1"))
            throw IllegalStateException("Shizuku nicht bereit – Kabelloses Debugging in den Entwickleroptionen einschalten")
        repeat(20) { if (prop("service.adb.tls.port")?.toIntOrNull()?.let { it > 0 } == true) return scrcpy(ctx); Thread.sleep(250) }
        return scrcpy(ctx)
    }

    /** opens the developer options at "Wireless debugging", where "Pair device with pairing code" lives */
    fun scrcpyPairing(ctx: Context) {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Wi-Fi power saving makes the stream come in bursts (ping 7–160 ms): hold HIGH_PERF (works with the
    // game in front) plus LOW_LATENCY while someone mirrors. Released when the keep-alive stops for 90 s.
    private val locks = mutableListOf<android.net.wifi.WifiManager.WifiLock>()
    @Volatile private var lastPing = 0L

    @Synchronized fun lowLatency(ctx: Context, on: Boolean): Boolean {
        if (!on) { releaseLocks(); return false }
        lastPing = System.currentTimeMillis()
        if (locks.isEmpty()) {
            val wm = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            listOf(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY).forEach { mode ->
                runCatching { wm.createWifiLock(mode, "thorbro:mirror").apply { setReferenceCounted(false); acquire() } }.getOrNull()?.let { locks += it }
            }
            Logs.i("stream", "WLAN ohne Stromsparen (Spiegeln)")
            Thread {
                while (true) {
                    Thread.sleep(15_000)
                    synchronized(this) {
                        if (locks.isEmpty()) return@Thread
                        if (System.currentTimeMillis() - lastPing > 90_000) { releaseLocks(); return@Thread }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        return locks.isNotEmpty()
    }

    @Synchronized private fun releaseLocks() {
        if (locks.isEmpty()) return
        locks.forEach { runCatching { if (it.isHeld) it.release() } }
        locks.clear()
        Logs.i("stream", "WLAN-Stromsparen wieder an")
    }

    /** a system property: through Shizuku if it runs, else plain getprop (may be hidden by SELinux) */
    private fun prop(name: String): String? =
        (Shell.out("getprop", name) ?: runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText() }
        }.getOrNull())?.trim()?.takeIf { it.isNotEmpty() }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ThorBro")
    }

    private fun get(url: String): String = open(url).run {
        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    }
}
