package wtf.lennard.thorbro

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/** Battery state, so background work can back off when nobody is looking or the battery is low. */
object Power {
    /** screen on (the Thor counts as "on" if any of its displays is awake) */
    fun interactive(ctx: Context): Boolean = ctx.getSystemService(PowerManager::class.java).isInteractive

    fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0
    }

    fun level(ctx: Context): Int = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** battery saver on, or below 20 % and not plugged in: only do what was asked for */
    fun low(ctx: Context): Boolean {
        if (ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return true
        return !charging(ctx) && level(ctx) in 0..20
    }
}
