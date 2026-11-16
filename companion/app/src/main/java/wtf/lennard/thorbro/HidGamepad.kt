package wtf.lennard.thorbro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Turns the device into a Bluetooth game controller (HID gamepad: 16 buttons, d-pad, two sticks,
 * analog triggers). Hosts like PCs, Macs, phones or TVs see a standard gamepad.
 */
@SuppressLint("MissingPermission")
object HidGamepad {
    /** report 1: 16 buttons, hat (4 bit) + padding, X Y Z Rz sticks, Rx Ry triggers = 9 bytes */
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
        0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00, 0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
        0xC0.toByte(),
    )

    private val BUTTONS = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1, KeyEvent.KEYCODE_BUTTON_X to 2, KeyEvent.KEYCODE_BUTTON_Y to 3,
        KeyEvent.KEYCODE_BUTTON_L1 to 4, KeyEvent.KEYCODE_BUTTON_R1 to 5, KeyEvent.KEYCODE_BUTTON_L2 to 6, KeyEvent.KEYCODE_BUTTON_R2 to 7,
        KeyEvent.KEYCODE_BUTTON_SELECT to 8, KeyEvent.KEYCODE_BUTTON_START to 9, KeyEvent.KEYCODE_BUTTON_THUMBL to 10,
        KeyEvent.KEYCODE_BUTTON_THUMBR to 11, KeyEvent.KEYCODE_BUTTON_MODE to 12,
    )

    enum class State { OFF, NOT_SUPPORTED, REGISTERING, WAITING, CONNECTED }

    @Volatile var state = State.OFF; private set
    @Volatile var host: String = ""; private set
    var onChange: (() -> Unit)? = null

    private var hid: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private val exec = Executors.newSingleThreadExecutor()

    // current input state
    private var buttons = 0
    private var dUp = false; private var dDown = false; private var dLeft = false; private var dRight = false
    private var hatX = 0f; private var hatY = 0f
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f; private var lt = 0f; private var rt = 0f

    private fun set(s: State) { state = s; onChange?.invoke() }

    fun start(ctx: Context) {
        if (state != State.OFF && state != State.NOT_SUPPORTED) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return set(State.NOT_SUPPORTED)
        set(State.REGISTERING)
        val ok = adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("ThorBro Gamepad", "Thor als Controller", "ThorBro",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, DESCRIPTOR)
                h.registerApp(sdp, null, null, exec, object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                        if (!registered) { if (state != State.OFF) set(State.WAITING); return }
                        set(State.WAITING)
                        // reconnect to the last host, or to the one the system suggests
                        val last = Prefs.of(ctx).getString("gamepadHost", null)
                        val target = pluggedDevice ?: last?.let { a -> runCatching { adapter.getRemoteDevice(a) }.getOrNull() }
                        target?.let { runCatching { h.connect(it) } }
                    }
                    override fun onConnectionStateChanged(d: BluetoothDevice, st: Int) {
                        when (st) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                device = d; host = d.name ?: d.address
                                Prefs.of(ctx).edit().putString("gamepadHost", d.address).apply()
                                set(State.CONNECTED); send()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> { if (device == d) device = null; if (state != State.OFF) set(State.WAITING) }
                        }
                    }
                    override fun onGetReport(d: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                        hid?.replyReport(d, type, id, report())
                    }
                })
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; if (state != State.OFF) set(State.NOT_SUPPORTED) }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) set(State.NOT_SUPPORTED)
    }

    fun stop(ctx: Context) {
        val h = hid
        set(State.OFF)
        runCatching { device?.let { h?.disconnect(it) } }
        runCatching { h?.unregisterApp() }
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; device = null; host = ""
    }

    // ---- input from the activity ----

    /** returns true when the key belongs to the gamepad */
    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dUp = down
            KeyEvent.KEYCODE_DPAD_DOWN -> dDown = down
            KeyEvent.KEYCODE_DPAD_LEFT -> dLeft = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> dRight = down
            else -> {
                val bit = BUTTONS[e.keyCode] ?: return false
                buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
            }
        }
        send(); return true
    }

    fun onMotion(e: MotionEvent) {
        lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y)
        rx = e.getAxisValue(MotionEvent.AXIS_Z); ry = e.getAxisValue(MotionEvent.AXIS_RZ)
        lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        send()
    }

    fun pressed(bit: Int) = buttons and (1 shl bit) != 0

    private fun axis(v: Float) = (((v.coerceIn(-1f, 1f) + 1f) / 2f) * 255f).roundToInt().toByte()
    private fun trig(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

    private fun hat(): Int {
        val up = dUp || hatY < -0.5f; val down = dDown || hatY > 0.5f
        val left = dLeft || hatX < -0.5f; val right = dRight || hatX > 0.5f
        return when {
            up && right -> 1; down && right -> 3; down && left -> 5; up && left -> 7
            up -> 0; right -> 2; down -> 4; left -> 6; else -> 8 // 8 = centered (null state)
        }
    }

    private fun report(): ByteArray {
        val b = buttons or (if (lt > 0.5f) 1 shl 6 else 0) or (if (rt > 0.5f) 1 shl 7 else 0)
        return byteArrayOf(
            (b and 0xFF).toByte(), ((b shr 8) and 0xFF).toByte(), (hat() and 0x0F).toByte(),
            axis(lx), axis(ly), axis(rx), axis(ry), trig(lt), trig(rt),
        )
    }

    private fun send() {
        val h = hid ?: return; val d = device ?: return
        val r = report()
        exec.execute { runCatching { h.sendReport(d, 1, r) } }
    }
}
