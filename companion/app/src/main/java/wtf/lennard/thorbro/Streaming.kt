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
        return out
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

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ThorBro")
    }

    private fun get(url: String): String = open(url).run {
        if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    }
}
