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
    /** stable per-device id (Android ID), to tell the same Thor over USB and Wi-Fi from another one */
    @android.annotation.SuppressLint("HardwareIds")
    fun uid(ctx: Context): String = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""

    /** this device's name for save uploads ("AYN Thor"), safe inside a file name */
    fun label(ctx: Context): String =
        (runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL)
            .replace(Regex("[\\\\/:*?\"<>|()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "Thor" }

    /** LAN address (normal WLAN first); never the Wi-Fi Direct one (p2p*, 192.168.49.x), that is [Direct.ownIp] */
    fun ip(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.hostAddress.orEmpty().startsWith("192.168.49.") }?.hostAddress

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
