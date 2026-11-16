package wtf.lennard.thorbro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** a progress notification for long jobs (uploads to RomM) */
object Progress {
    private const val ID = 9
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel("progress", "Fortschritt", NotificationManager.IMPORTANCE_LOW))
    }

    fun show(ctx: Context, title: String, progress: Double, text: String) {
        val n = Notification.Builder(ctx, "progress").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$title · ${(progress * 100).toInt()} %").setContentText(text)
            .setProgress(1000, (progress * 1000).toInt(), false).setOngoing(true).setOnlyAlertOnce(true).build()
        runCatching { nm(ctx).notify(ID, n) }
    }

    fun done(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, "progress").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        runCatching { nm(ctx).notify(ID, n) }
    }
}
