package wtf.lennard.thorbro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** paired Bluetooth controllers: list and remove the pairing (so a pad can go back into pairing mode) */
@SuppressLint("MissingPermission")
object BtPads {
    class Paired(val address: String, val name: String)

    private val padName = Regex("(?i)controller|gamepad|pad|xbox|dualsense|dualshock|wireless|8bitdo|joy-?con|pro controller|switch|stadia|gamesir|flydigi")

    fun list(ctx: Context): List<Paired> {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { bt.bondedDevices }.getOrDefault(emptySet()).filter { d ->
            d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL || padName.containsMatchIn(d.name ?: "")
        }.map { Paired(it.address, it.name ?: it.address) }.sortedBy { it.name.lowercase() }
    }

    /** removes the pairing; false when Android does not let ThorBro do it (then use the Bluetooth settings) */
    fun unpair(ctx: Context, address: String): Boolean {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val d: BluetoothDevice = runCatching { bt.getRemoteDevice(address) }.getOrNull() ?: return false
        val ok = runCatching { d.javaClass.getMethod("removeBond").invoke(d) as Boolean }.getOrDefault(false)
        if (!ok) return false
        // removal is asynchronous: wait briefly until it is gone
        repeat(20) { if (runCatching { d.bondState }.getOrNull() == BluetoothDevice.BOND_NONE) return true; Thread.sleep(150) }
        return runCatching { d.bondState == BluetoothDevice.BOND_NONE }.getOrDefault(false)
    }
}
