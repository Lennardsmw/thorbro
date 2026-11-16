package wtf.lennard.thorbro

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Controller LEDs.
 * - The Thor's own stick LEDs: AYN keeps them in the system settings "joystick_light_enabled" ("1,1") and
 *   "joystick_led_light_picker_color" ("#ffRRGGBB,#ffRRGGBB", left,right). Apps may not write vendor
 *   settings, so ThorBro writes them through Shizuku (shell rights, granted once in Shizuku).
 * - External controllers (DualSense, DualShock 4 …): Android's LightsManager per input device.
 */
object Leds {
    // ---------- Thor stick LEDs ----------

    fun thorState(ctx: Context): JSONObject {
        val en = Settings.System.getString(ctx.contentResolver, "joystick_light_enabled") ?: ""
        val col = Settings.System.getString(ctx.contentResolver, "joystick_led_light_picker_color") ?: ""
        return JSONObject().put("supported", col.isNotEmpty() || en.isNotEmpty())
            .put("on", en.split(',').any { it.trim() == "1" })
            .put("colors", JSONArray(col.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            .put("shizuku", shizukuState())
    }

    /** "ready", "needs_permission" or "not_running" */
    fun shizukuState(): String = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "not_running"
        Shizuku.isPreV11() -> "not_running"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "needs_permission"
        else -> "ready"
    }

    fun requestShizuku() { runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(77) } }

    /** runs a command with Shizuku's shell rights */
    private fun shell(vararg cmd: String): Boolean {
        if (shizukuState() != "ready") return false
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun put(key: String, value: String) = shell("settings", "put", "system", key, value)

    /** color as 0xRRGGBB for both sticks (null keeps the colors), on = LEDs on/off */
    fun setThor(ctx: Context, on: Boolean?, rgb: Int?): JSONObject {
        var ok = true
        if (rgb != null) {
            val c = "#ff%06x".format(rgb and 0xFFFFFF)
            ok = put("joystick_led_light_picker_color", "$c,$c") && ok
        }
        if (on != null) ok = put("joystick_light_enabled", if (on) "1,1" else "0,0") && ok
        return thorState(ctx).put("ok", ok)
    }

    // ---------- external controllers ----------

    private val sessions = HashMap<Int, LightsManager.LightsSession>()

    private fun rgbLights(d: InputDevice): List<Light> =
        if (Build.VERSION.SDK_INT >= 31) d.lightsManager.lights.filter { it.hasRgbControl() } else emptyList()

    fun pads(): JSONArray = JSONArray(
        InputDevice.getDeviceIds().toList().mapNotNull { id: Int -> InputDevice.getDevice(id) }
            .filter { it.isExternal && rgbLights(it).isNotEmpty() }
            .map { JSONObject().put("id", it.id).put("name", it.name) }
    )

    /** rgb = 0 switches the light bar off */
    fun setPad(id: Int, rgb: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val d = InputDevice.getDevice(id) ?: return false
        val lights = rgbLights(d).ifEmpty { return false }
        val session = sessions.getOrPut(id) { d.lightsManager.openSession() } // closing the session resets the LEDs
        val req = LightsRequest.Builder().apply {
            lights.forEach { addLight(it, LightState.Builder().setColor(0xFF000000.toInt() or (rgb and 0xFFFFFF)).build()) }
        }.build()
        return runCatching { session.requestLights(req); true }.getOrDefault(false)
    }
}
