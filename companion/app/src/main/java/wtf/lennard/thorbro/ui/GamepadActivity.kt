package wtf.lennard.thorbro.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wtf.lennard.thorbro.HidGamepad

/** The Thor as a Bluetooth controller for another device. */
class GamepadActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private var dimmed by mutableStateOf(false)
    private var comboSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        HidGamepad.onChange = { runOnUiThread { tick++ } }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN), 3)
        } else HidGamepad.start(this)
        setContent { ThorTheme { Screen() } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HidGamepad.start(this); tick++
    }

    override fun onDestroy() {
        HidGamepad.onChange = null
        HidGamepad.stop(this)
        super.onDestroy()
    }

    private fun setDim(on: Boolean) {
        dimmed = on
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    // every button goes to the host; Start + Select for 2 s (or Back) leaves the mode
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (HidGamepad.onKey(event)) {
            val combo = HidGamepad.pressed(8) && HidGamepad.pressed(9)
            if (combo) { if (comboSince == 0L) comboSince = System.currentTimeMillis() else if (System.currentTimeMillis() - comboSince > 2000) finish() }
            else comboSince = 0L
            tick++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) { HidGamepad.onMotion(event); return true }
        return super.dispatchGenericMotionEvent(event)
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        @Suppress("UNUSED_EXPRESSION") tick
        val st = HidGamepad.state
        Column(Modifier.fillMaxSize().then(if (dimmed) Modifier.background(androidx.compose.ui.graphics.Color.Black) else Modifier.glassBackground())) {
            StatusBar("Gamepad-Modus")
            Column(Modifier.weight(1f).padding(horizontal = 40.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val (title, sub, ok) = when (st) {
                    HidGamepad.State.CONNECTED -> Triple("Verbunden mit ${HidGamepad.host}", "Alle Tasten gehen jetzt an dieses Gerät.", true)
                    HidGamepad.State.WAITING -> Triple("Bereit – wartet auf ein Gerät", "Beim ersten Mal: „Sichtbar machen“ tippen und auf dem PC, Mac oder TV nach „${btName()}“ suchen und koppeln.", false)
                    HidGamepad.State.REGISTERING -> Triple("Startet …", "", false)
                    HidGamepad.State.NOT_SUPPORTED -> Triple("Nicht verfügbar", "Bluetooth ist aus oder dieses Android erlaubt keinen Controller-Modus.", false)
                    HidGamepad.State.OFF -> Triple("Aus", "", false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(ok); Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 16.sp)
                Acts {
                    Act("Sichtbar machen", primary = st != HidGamepad.State.CONNECTED) {
                        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                    }
                    Act(if (dimmed) "Bildschirm hell" else "Bildschirm dimmen") { setDim(!dimmed) }
                    Act("Beenden") { finish() }
                }
                Spacer(Modifier.height(10.dp))
                Label("Gedrückt")
                val names = listOf("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start", "L3", "R3", "Home")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEachIndexed { i, n ->
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (HidGamepad.pressed(i)) Accent else Panel).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(n, color = if (HidGamepad.pressed(i)) OnAccent else Ink) }
                    }
                }
            }
            ButtonHints("Start+Select 2 s" to "Beenden")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun btName(): String = runCatching { getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.name }.getOrNull() ?: "Thor"
}
