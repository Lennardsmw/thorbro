package wtf.lennard.thorbro.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Live state of connected gamepads, fed by ThorActivity's key and motion events. */
object Pads {
    class Pad(val id: Int, val name: String, val battery: String)
    val pads = mutableStateListOf<Pad>()
    val pressed = mutableStateMapOf<Int, Boolean>()
    var lx by mutableFloatStateOf(0f); var ly by mutableFloatStateOf(0f)
    var rx by mutableFloatStateOf(0f); var ry by mutableFloatStateOf(0f)
    var lt by mutableFloatStateOf(0f); var rt by mutableFloatStateOf(0f)
    var lastFrom by mutableStateOf("")
    /** set while ThorBro waits for "press a button on the controller for player N" */
    var assigning by mutableStateOf(0)
    var assignArmedAt = 0L
    var onAssigned: ((Int, InputDevice) -> Unit)? = null

    private fun isPad(d: InputDevice?) = d != null && !d.isVirtual &&
        (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    fun refresh(ctx: Context) {
        pads.clear()
        val devs: List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { id: Int -> InputDevice.getDevice(id) }
        devs.filter { isPad(it) }.distinctBy { it.descriptor }.forEach { d: InputDevice ->
            val bat = if (Build.VERSION.SDK_INT >= 31) d.batteryState.let { b -> if (b.isPresent) "${(b.capacity * 100).toInt()} %" else "" } else ""
            pads += Pad(d.id, d.name, bat)
        }
    }

    fun onKey(e: KeyEvent) {
        if (!isPad(e.device)) return
        if (assigning > 0 && e.action == KeyEvent.ACTION_DOWN && System.currentTimeMillis() - assignArmedAt > 400) {
            val slot = assigning; assigning = 0
            e.device?.let { d -> onAssigned?.invoke(slot, d) }
        }
        pressed[e.keyCode] = e.action == KeyEvent.ACTION_DOWN
        lastFrom = e.device?.name ?: ""
    }

    fun onMotion(e: MotionEvent) {
        lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y)
        rx = e.getAxisValue(MotionEvent.AXIS_Z); ry = e.getAxisValue(MotionEvent.AXIS_RZ)
        lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        // the d-pad of many pads comes as hat axes
        pressed[KeyEvent.KEYCODE_DPAD_LEFT] = e.getAxisValue(MotionEvent.AXIS_HAT_X) < -0.5f
        pressed[KeyEvent.KEYCODE_DPAD_RIGHT] = e.getAxisValue(MotionEvent.AXIS_HAT_X) > 0.5f
        pressed[KeyEvent.KEYCODE_DPAD_UP] = e.getAxisValue(MotionEvent.AXIS_HAT_Y) < -0.5f
        pressed[KeyEvent.KEYCODE_DPAD_DOWN] = e.getAxisValue(MotionEvent.AXIS_HAT_Y) > 0.5f
        lastFrom = e.device?.name ?: ""
    }
}

private val buttons = listOf(
    KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
    KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3", KeyEvent.KEYCODE_BUTTON_START to "Start",
    KeyEvent.KEYCODE_BUTTON_SELECT to "Select", KeyEvent.KEYCODE_BUTTON_MODE to "Home",
    KeyEvent.KEYCODE_DPAD_UP to "↑", KeyEvent.KEYCODE_DPAD_DOWN to "↓", KeyEvent.KEYCODE_DPAD_LEFT to "←", KeyEvent.KEYCODE_DPAD_RIGHT to "→",
)

@SuppressLint("MissingPermission")
@Composable
fun ControllerScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { Pads.refresh(ctx); delay(2000); tick++ } }

    val bt = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val needPerms = Build.VERSION.SDK_INT >= 31 &&
        (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val major = d.bluetoothClass?.majorDeviceClass
                        if ((major == BluetoothClass.Device.Major.PERIPHERAL || d.name?.contains(Regex("(?i)controller|pad|xbox|dualsense|wireless|8bitdo|joy")) == true)
                            && found.none { it.address == d.address } && d.bondState != BluetoothDevice.BOND_BONDED) found += d
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> { note = "Kopplung: " + when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0)) {
                        BluetoothDevice.BOND_BONDED -> "verbunden ✓"; BluetoothDevice.BOND_BONDING -> "läuft …"; else -> "abgebrochen" }; tick++ }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        onDispose { runCatching { ctx.unregisterReceiver(r) }; runCatching { if (!needPerms) bt?.cancelDiscovery() } }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Verbunden")
                if (Pads.pads.isEmpty()) Line("Kein externer Controller verbunden (die Thor-Tasten zählen mit).", Muted)
                Pads.pads.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(true); Big(p.name); if (p.battery.isNotEmpty()) Line("  ·  Akku ${p.battery}", Muted) } }
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Spieler – gilt automatisch in Dolphin, auch nach Neuverbinden")
                val players = remember(tick) { wtf.lennard.thorbro.Players.overview(ctx) }
                DisposableEffect(Unit) {
                    Pads.onAssigned = { slot, d -> wtf.lennard.thorbro.Players.assign(ctx, slot, d); tick++ }
                    onDispose { Pads.onAssigned = null; Pads.assigning = 0 }
                }
                val arr = players.getJSONArray("players")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i); val slot = p.getInt("slot")
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Spieler $slot", Modifier.width(100.dp), fontSize = 16.sp)
                        if (Pads.assigning == slot) Text("Jetzt eine Taste auf dem Controller drücken …", Modifier.weight(1f), color = Accent)
                        else {
                            if (!p.isNull("name")) StatusDot(p.optBoolean("connected"))
                            Text(if (p.isNull("name")) "—" else p.optString("name") + if (p.optBoolean("connected")) "" else " (nicht verbunden)", Modifier.weight(1f), color = if (p.isNull("name")) Muted else androidx.compose.ui.graphics.Color.White)
                        }
                        Act("Zuweisen") { Pads.assigning = slot; Pads.assignArmedAt = System.currentTimeMillis() }
                        Spacer(Modifier.width(8.dp))
                        if (!p.isNull("name")) Act("Leeren") { wtf.lennard.thorbro.Players.clear(ctx, slot); tick++ }
                    }
                }
                if (players.optString("lastApply").isNotEmpty()) Line("Zuletzt übernommen: " + players.optString("lastApply"), Muted)
            }
        }
        item { LedCard(tick) { tick++ } }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Tastentest – einfach drücken" + if (Pads.lastFrom.isNotEmpty()) " · ${Pads.lastFrom}" else "")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEach { (code, label) ->
                        val on = Pads.pressed[code] == true
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Accent else Bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(label, fontSize = 15.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stick("Linker Stick", Pads.lx, Pads.ly); Stick("Rechter Stick", Pads.rx, Pads.ry)
                    Column { Trigger("L2", Pads.lt); Spacer(Modifier.height(6.dp)); Trigger("R2", Pads.rt) }
                }
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Gekoppelte Controller")
                val paired = remember(tick) { if (needPerms) emptyList() else wtf.lennard.thorbro.BtPads.list(ctx) }
                var confirmAll by remember { mutableStateOf(false) }
                if (paired.isEmpty()) Line(if (needPerms) "Erst Bluetooth erlauben (unten)." else "Keine Bluetooth-Controller gekoppelt.", Muted)
                paired.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), fontSize = 16.sp)
                        Act("Entkoppeln") {
                            kotlin.concurrent.thread {
                                val ok = wtf.lennard.thorbro.BtPads.unpair(ctx, p.address)
                                note = if (ok) "${p.name} entkoppelt – jetzt kann er neu in den Kopplungsmodus" else "Android lässt das nicht zu – in den Bluetooth-Einstellungen entfernen"
                                if (!ok) ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                tick++
                            }
                        }
                    }
                }
                if (paired.size > 1) Acts {
                    if (!confirmAll) Act("Alle Controller entkoppeln") { confirmAll = true }
                    else {
                        Text("Wirklich alle ${paired.size} entkoppeln?", color = Accent)
                        Act("Ja, alle", primary = true) {
                            confirmAll = false
                            kotlin.concurrent.thread {
                                val done = paired.count { wtf.lennard.thorbro.BtPads.unpair(ctx, it.address) }
                                note = if (done == paired.size) "Alle Controller entkoppelt" else "$done von ${paired.size} entkoppelt – Rest in den Bluetooth-Einstellungen"
                                if (done < paired.size) ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                tick++
                            }
                        }
                        Act("Abbrechen") { confirmAll = false }
                    }
                }
                if (note.isNotEmpty()) Line(note, Muted)
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Neuen Controller koppeln")
                Line("Controller in den Kopplungsmodus versetzen (z. B. Share+PS, Sync-Taste, Start+Y), dann suchen.", Muted)
                Acts {
                    if (needPerms) Act("Bluetooth erlauben", primary = true) {
                        (ctx as? Activity)?.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 7)
                    } else Act(if (scanning) "Suche läuft …" else "Suchen", primary = true, enabled = bt != null && !scanning) {
                        found.clear(); scanning = bt?.startDiscovery() == true; note = if (scanning) "" else "Bluetooth ist aus?"
                    }
                    Act("Bluetooth-Einstellungen") { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    if (note.isNotEmpty()) Text(note, color = Muted)
                }
                found.forEach { d ->
                    FocusCard(Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { runCatching { bt?.cancelDiscovery(); d.createBond() }; note = "Kopplung: läuft …" }) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Big(d.name ?: d.address); Spacer(Modifier.weight(1f)); Line("A = koppeln", Accent) }
                    }
                }
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("In den Emulatoren")
                Line("Dolphin: Die Spieler oben werden bei jedem Verbinden automatisch eingetragen, die Tastenbelegung bleibt. " +
                    "RetroArch erkennt bekannte Controller selbst. Für Eden, Azahar und die anderen legst du die Belegung einmal im Emulator fest.", Muted)
            }
        }
    }
}

@Composable
private fun Stick(label: String, x: Float, y: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(CircleShape).background(Bg), contentAlignment = Alignment.Center) {
            Box(Modifier.offset((x * 30).dp, (y * 30).dp).size(18.dp).clip(CircleShape).background(Accent))
        }
        Label(label)
    }
}

@Composable
private fun Trigger(label: String, v: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(28.dp))
        Box(Modifier.width(120.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Bg)) {
            Box(Modifier.width((120 * v.coerceIn(0f, 1f)).dp).height(10.dp).background(Accent))
        }
    }
}

private val swatches = listOf(0xFF2020, 0xFF7A00, 0xFFD000, 0x20E040, 0x00D0FF, 0x2050FF, 0x9040FF, 0xFF40B0, 0xFFFFFF)

@Composable
private fun Swatches(onPick: (Int) -> Unit, offLabel: String, onOff: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { c ->
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val focused by src.collectIsFocusedAsState()
            Box(Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF000000.toInt() or c))
                .then(if (focused) Modifier.background(androidx.compose.ui.graphics.Color.Transparent) else Modifier)
                .androidx_border(focused)
                .clickable(interactionSource = src, indication = null) { onPick(c) }
                .focusable(interactionSource = src))
        }
        Act(offLabel, onClick = onOff)
    }
}

private fun Modifier.androidx_border(on: Boolean) =
    if (on) this.then(Modifier.border(3.dp, androidx.compose.ui.graphics.Color.White, CircleShape)) else this

@Composable
private fun LedCard(tick: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val st = remember(tick) { wtf.lennard.thorbro.Leds.thorState(ctx) }
    val pads = remember(tick) { wtf.lennard.thorbro.Leds.pads() }
    var msg by remember { mutableStateOf("") }
    FocusCard(Modifier.fillMaxWidth()) {
        Label("LEDs")
        if (st.optBoolean("supported")) {
            Big("Thor-Sticks: " + if (st.optBoolean("on")) "an" else "aus")
            when (st.optString("shizuku")) {
                "not_running" -> {
                    Line("Für die Thor-LEDs braucht ThorBro Shizuku. Shizuku öffnen und starten, dann hierher zurück.", Muted)
                    Acts { Act("Shizuku öffnen", primary = true) {
                        ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) }
                    } }
                }
                "needs_permission" -> {
                    Line("Einmal erlauben: Shizuku fragt, ob ThorBro es nutzen darf.", Muted)
                    Acts { Act("ThorBro in Shizuku erlauben", primary = true) { wtf.lennard.thorbro.Leds.requestShizuku(); changed() } }
                }
                else -> {
                    Spacer(Modifier.height(6.dp))
                    Swatches({ c -> val r = wtf.lennard.thorbro.Leds.setThor(ctx, true, c); msg = if (r.optBoolean("ok")) "" else "Konnte die LEDs nicht setzen"; changed() },
                        if (st.optBoolean("on")) "Ausschalten" else "Einschalten") {
                        val r = wtf.lennard.thorbro.Leds.setThor(ctx, !st.optBoolean("on"), null); msg = if (r.optBoolean("ok")) "" else "Konnte die LEDs nicht schalten"; changed()
                    }
                }
            }
        }
        for (i in 0 until pads.length()) {
            val p = pads.getJSONObject(i)
            Spacer(Modifier.height(10.dp))
            Big(p.getString("name"))
            Swatches({ c -> msg = if (wtf.lennard.thorbro.Leds.setPad(p.getInt("id"), c)) "" else "Dieser Controller nimmt keine Farbe an" }, "Aus") {
                wtf.lennard.thorbro.Leds.setPad(p.getInt("id"), 0)
            }
        }
        if (!st.optBoolean("supported") && pads.length() == 0) Line("Kein Controller mit einstellbaren LEDs verbunden.", Muted)
        if (msg.isNotEmpty()) Line(msg, Bad)
    }
}
