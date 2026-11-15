package wtf.lennard.thorbro

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

object Device {
    fun ip(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress

    fun permissions(ctx: Context) = JSONObject()
        .put("allFiles", Environment.isExternalStorageManager())
        .put("overlay", Settings.canDrawOverlays(ctx))
        .put("install", ctx.packageManager.canRequestPackageInstalls())

    fun info(ctx: Context): JSONObject {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return JSONObject()
            .put("name", "ThorBro")
            .put("appVersion", pkg.versionName)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("ip", ip())
            .put("port", ThorService.PORT)
            .put("displays", dm.displays.size)
            .put("battery", JSONObject()
                .put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging))
            .put("storage", Storage.volumes(ctx))
            .put("permissions", permissions(ctx))
    }
}
