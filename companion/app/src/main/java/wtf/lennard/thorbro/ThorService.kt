package wtf.lennard.thorbro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.ktor.server.engine.EmbeddedServer
import kotlin.concurrent.thread

/** Foreground service that keeps the HTTP server running and announces it as _thorbro._tcp. */
class ThorService : Service() {
    private var server: EmbeddedServer<*, *>? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(capture = intent?.action == ACTION_CAPTURE)
        if (intent?.action == ACTION_CAPTURE) {
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")
            if (data != null) runCatching { Screen.start(applicationContext, intent.getIntExtra("code", 0), data) }
        }
        if (server == null) {
            val s = Server.create(applicationContext, PORT)
            server = s
            thread(name = "thorbro-server") { s.start(wait = false) }
            announce()
            Updater.startSchedule(applicationContext)
            SaveWatcher.start(applicationContext)
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Updater.stopSchedule()
        SaveWatcher.stop()
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        nsdListener = null
        val s = server
        server = null
        thread(name = "thorbro-stop") { s?.stop(500, 1500) }
        Transfers.releaseAll()
        Screen.stop()
        super.onDestroy()
    }

    private fun startInForeground(capture: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ThorBro server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, AppActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ThorBro läuft")
            .setContentText("http://${Device.ip() ?: "?"}:$PORT")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val proj = if (capture || Screen.running) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or proj)
            proj != 0 -> startForeground(1, n, proj)
            else -> startForeground(1, n)
        }
    }

    private fun announce() {
        val info = NsdServiceInfo().apply {
            serviceName = "ThorBro ${Build.MODEL}"
            serviceType = "_thorbro._tcp"
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        }
    }

    companion object {
        const val PORT = 7330
        const val ACTION_CAPTURE = "wtf.lennard.thorbro.CAPTURE"
        private const val CHANNEL = "server"
        @Volatile var running = false

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ThorService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ThorService::class.java))
    }
}

/** Keeps CPU and Wi-Fi awake while at least one upload is running. */
object Transfers {
    private var active = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    @Synchronized fun begin(ctx: Context) {
        if (active++ > 0) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thorbro:upload").apply { acquire(60 * 60 * 1000L) }
        val wm = ctx.getSystemService(WifiManager::class.java)
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "thorbro:upload").apply { acquire() }
    }

    @Synchronized fun end() {
        if (active == 0 || --active > 0) return
        releaseAll()
    }

    @Synchronized fun releaseAll() {
        active = 0
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        wake = null; wifi = null
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // after boot, and after ThorBro was updated (the update kills the running server)
        val start = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (start && Prefs.autostart(ctx)) ThorService.start(ctx)
    }
}
