package wtf.lennard.thorbro

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object Apps {
    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rows = pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != ctx.packageName }
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                val info = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { return@mapNotNull null }
                val ai = ri.activityInfo.applicationInfo
                JSONObject()
                    .put("package", pkg)
                    .put("label", ri.loadLabel(pm).toString())
                    .put("version", info.versionName ?: "")
                    .put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("updated", info.lastUpdateTime)
            }
            .sortedBy { it.getString("label").lowercase() }
        return JSONArray(rows)
    }

    fun icon(ctx: Context, pkg: String, size: Int = 96): ByteArray {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Needs "display over other apps", otherwise Android blocks starts from the background. */
    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        Emulators.startOnDisplay(ctx, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Opens the system uninstall dialog on the Thor; the user confirms there. */
    fun uninstall(ctx: Context, pkg: String) {
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
