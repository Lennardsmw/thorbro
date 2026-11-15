package wtf.lennard.thorbro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Installs an APK that is already on the device. Android shows its confirmation dialog on the Thor. */
object Installer {
    val status = ConcurrentHashMap<Int, String>()
    /** Android's "install?" dialog that still waits to be shown; opened again when ThorBro comes to the front. */
    @Volatile var pendingConfirm: Intent? = null

    fun showConfirm(ctx: Context, confirm: Intent) {
        pendingConfirm = confirm
        runCatching { ctx.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        // Fallback when Android does not bring the dialog to the front: a notification to tap.
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("install", "Installationen", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 7, Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, "install")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Installation bereit")
            .setContentText("Tippen, um die Installation zu bestätigen")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(7, n) }
    }

    fun clearConfirm(ctx: Context) {
        pendingConfirm = null
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(7) }
    }

    fun install(ctx: Context, apk: File): Int {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        status[id] = "writing"
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 1 shl 20)
                    s.fsync(out)
                }
            }
            val cb = Intent(ctx, InstallReceiver::class.java).setAction("wtf.lennard.thorbro.INSTALL_RESULT")
            val pending = PendingIntent.getBroadcast(
                ctx, id, cb, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pending.intentSender)
        }
        status[id] = "pending"
        return id
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    Installer.showConfirm(ctx, confirm)
                    Installer.status[id] = "waiting_for_user"
                } else {
                    Installer.status[id] = "failed: no confirmation dialog"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { Installer.status[id] = "success"; Installer.clearConfirm(ctx) }
            else -> { Installer.clearConfirm(ctx); Installer.status[id] =
                "failed ($code): " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "") }
        }
    }
}
