package wtf.lennard.thorbro.ui

import androidx.compose.material3.Icon
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
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
    /** "Reihenfolge ändern": every pad that presses L + R together becomes the next player */
    var lrMode by mutableStateOf(false)
    var appCtx: Context? = null
    private val lr = HashMap<Int, Pair<Boolean, Boolean>>()

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
        if (lrMode && (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || e.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            val down = e.action == KeyEvent.ACTION_DOWN
            val (l, r) = lr[e.deviceId] ?: (false to false)
            val now = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) down to r else l to down
            lr[e.deviceId] = now
            if (now.first && now.second) { e.device?.let { d -> appCtx?.let { wtf.lennard.thorbro.Players.assignNext(it, d) } }; lr.remove(e.deviceId) }
        }
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
fun ControllerPanels(panel: String) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { Pads.refresh(ctx); delay(2000); tick++ } }

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

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (panel == "none") item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Verbunden")
                if (Pads.pads.isEmpty()) Line("Kein externer Controller verbunden (die Thor-Tasten zählen mit).", Muted)
                Pads.pads.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(true); Big(p.name); if (p.battery.isNotEmpty()) Line("  ·  Akku ${p.battery}", Muted) } }
            }
        }
        if (panel == "order") item {
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
        if (panel == "leds") item { LedCard(tick) { tick++ } }
        if (panel == "test") item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Tastentest – einfach drücken" + if (Pads.lastFrom.isNotEmpty()) " · ${Pads.lastFrom}" else "")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEach { (code, label) ->
                        val on = Pads.pressed[code] == true
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Panel).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(label, fontSize = 15.sp, color = if (on) OnAccent else androidx.compose.ui.graphics.Color.White)
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
        if (panel == "paired") item {
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
        if (panel == "new") item {
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
        if (panel == "order") item {
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
            val fx = rememberFx()
            Box(Modifier.size(40.dp).fx(fx, CircleShape, lift = 1.15f, restLine = false) { onPick(c) }
                .background(androidx.compose.ui.graphics.Color(0xFF000000.toInt() or c)))
        }
        Act(offLabel, onClick = onOff)
    }
}


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


/** which part of the controller page is open (null = overview); B goes back to the overview */
object ControllerNav { val panel = mutableStateOf<String?>(null) }

private class LivePad(val id: Int, val name: String, val model: wtf.lennard.thorbro.PadModel, val battery: Float, val slot: Int)

private fun livePads(ctx: Context): List<LivePad> {
    val slots = wtf.lennard.thorbro.Players.assigned(ctx).map { it?.optString("descriptor") }
    return InputDevice.getDeviceIds().toList().mapNotNull { id: Int -> InputDevice.getDevice(id) }
        .filter { d -> !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) }
        .distinctBy { it.descriptor }
        .map { d ->
            val bat = if (Build.VERSION.SDK_INT >= 31) d.batteryState.let { if (it.isPresent) it.capacity else Float.NaN } else Float.NaN
            LivePad(d.id, d.name, wtf.lennard.thorbro.PadModel.of(d), bat, slots.indexOf(d.descriptor) + 1)
        }
        .sortedWith(compareBy<LivePad> { if (it.slot == 0) 99 else it.slot }.thenBy { it.name })
}

@Composable
private fun PlayerDots(slot: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..4) Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (i == slot) Ok else androidx.compose.ui.graphics.Color(0xFF3A3F49)))
    }
}

@Composable
private fun Battery(level: Float) {
    if (level.isNaN()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp, 13.dp).border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF9AA0AE), RoundedCornerShape(3.dp)).padding(2.dp)) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0.05f, 1f)).height(9.dp).background(if (level < 0.2f) Bad else Ok))
        }
        Text(" ${(level * 100).toInt()} %", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PadCard(p: LivePad, big: Boolean = false) {
    FocusCard(Modifier.fillMaxWidth(), onClick = {}) {
        Box(Modifier.fillMaxWidth().height(if (big) 120.dp else 90.dp), contentAlignment = Alignment.Center) {
            PadArt(p.model, Modifier.size(if (big) 170.dp else 130.dp, if (big) 110.dp else 84.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PlayerDots(p.slot) }
        Spacer(Modifier.height(6.dp))
        Text(if (p.model == wtf.lennard.thorbro.PadModel.GENERIC) p.name else p.model.label, fontSize = 15.sp, maxLines = 1)
        if (p.model == wtf.lennard.thorbro.PadModel.GENERIC || p.model == wtf.lennard.thorbro.PadModel.THOR) Text(if (p.model == wtf.lennard.thorbro.PadModel.THOR) "eingebaut" else "", color = Muted, fontSize = 12.sp)
        Battery(p.battery)
    }
}

@Composable
fun ControllerScreen() {
    val ctx = LocalContext.current
    Pads.appCtx = ctx.applicationContext
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(800); tick++ } }
    val panel = ControllerNav.panel.value
    DisposableEffect(Unit) { onDispose { Pads.lrMode = false; ControllerNav.panel.value = null } }
    if (panel != null) { ControllerPanels(panel); return }
    val pads = remember(tick) { livePads(ctx) }
    if (Pads.lrMode) { OrderScreen(pads); return }

    val menu: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> = listOf(
        Triple("Reihenfolge ändern", Icons.Filled.SwapHoriz) {
            wtf.lennard.thorbro.Logs.i("controller", "Reihenfolge ändern gestartet")
            wtf.lennard.thorbro.Players.clearAll(ctx); Pads.lrMode = true
        },
        Triple("Controller koppeln", Icons.Filled.BluetoothSearching) { ControllerNav.panel.value = "new" },
        Triple("Gekoppelte Controller", Icons.Filled.Bluetooth) { ControllerNav.panel.value = "paired" },
        Triple("Tastentest", Icons.Filled.TouchApp) { ControllerNav.panel.value = "test" },
        Triple("LEDs", Icons.Filled.Lightbulb) { ControllerNav.panel.value = "leds" },
        Triple("Spieler & Emulatoren", Icons.Filled.Groups) { ControllerNav.panel.value = "order" },
        Triple("Thor als Gamepad", Icons.Filled.Gamepad) { ctx.startActivity(Intent(ctx, GamepadActivity::class.java)) },
    )
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth / maxHeight > 1.3f
        val bw = maxWidth; val bh = maxHeight
        @Composable fun Menu(mod: Modifier) {
            val (st, fl) = snapList()
            CenteredFocus {
                LazyColumn(mod, state = st, flingBehavior = fl, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    menu.forEachIndexed { i, (label, icon, act) ->
                        item { MenuPill(label, icon, Modifier.firstFocus(first, i == 0), act) }
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Menu(Modifier.width(bw * 0.34f).fillMaxHeight())
            Stage(pads, Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp))
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Stage(pads, Modifier.fillMaxWidth().height(bh * 0.45f))
            Menu(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** left menu entry: wide rounded pill, icon left, white when focused */
@Composable
private fun MenuPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    val focused = fx.active
    val shape = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth().height(62.dp).fx(fx, shape, lift = 1.03f, frame = false, onClick = onClick)
        .then(if (focused) Modifier.background(Accent) else Modifier.background(GlassFill))
        .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        val fg = if (focused) OnAccent else androidx.compose.ui.graphics.Color.White
        Icon(icon, null, Modifier.size(26.dp), tint = fg)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = fg, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** the bright stage with every connected controller standing side by side */
@Composable
private fun Stage(pads: List<LivePad>, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.clip(shape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0x2EFFFFFF), androidx.compose.ui.graphics.Color(0x0FFFFFFF))))
        .border(1.dp, GlassLine, shape), contentAlignment = Alignment.Center) {
        if (pads.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Gamepad, null, Modifier.size(72.dp), tint = Muted)
            Text("Kein Controller verbunden", fontSize = 18.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
        } else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            items(pads.size) { i -> PadOnStage(pads[i]) }
        }
    }
}

@Composable
private fun PadOnStage(p: LivePad) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(190.dp)) {
        PadArt(p.model, Modifier.size(180.dp, 125.dp))
        Spacer(Modifier.height(12.dp))
        PlayerLights(p.slot)
        Spacer(Modifier.height(8.dp))
        Text(if (p.model == wtf.lennard.thorbro.PadModel.GENERIC) p.name else p.model.label, fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (p.model == wtf.lennard.thorbro.PadModel.THOR) Text("eingebaut", fontSize = 12.sp, color = Muted) else Battery(p.battery)
    }
}

/** four small player lamps; the controller's slot is lit */
@Composable
private fun PlayerLights(slot: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..4) {
            val on = i <= slot && slot > 0 && (i == slot)
            Box(Modifier.size(14.dp, 6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (on) androidx.compose.ui.graphics.Color(0xFF7CFF9C) else androidx.compose.ui.graphics.Color(0x33FFFFFF)))
        }
        if (slot > 0) Text("  P$slot", fontSize = 13.sp, color = Muted)
    }
}

/** "Reihenfolge ändern": four places that fill as players press L + R */
@Composable
private fun OrderScreen(pads: List<LivePad>) {
    val done = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { done.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Drücke L + R auf dem Controller", fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("Die Reihenfolge, in der gedrückt wird, ergibt Spieler 1 bis 4", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (slot in 1..4) {
                val p = pads.firstOrNull { it.slot == slot }
                val shape = RoundedCornerShape(24.dp)
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(if (p != null) androidx.compose.ui.graphics.Color(0x26FFFFFF) else androidx.compose.ui.graphics.Color(0x0DFFFFFF))
                    .border(if (p != null) 3.dp else 1.dp, if (p != null) androidx.compose.ui.graphics.Color(0xFF7CFF9C) else GlassLine, shape),
                    contentAlignment = Alignment.Center) {
                    if (p == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$slot", fontSize = 64.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0x40FFFFFF))
                        Text("L + R", fontSize = 15.sp, color = Muted)
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                        PadArt(p.model, Modifier.fillMaxWidth().aspectRatio(1.4f))
                        Spacer(Modifier.height(12.dp)); PlayerLights(slot); Spacer(Modifier.height(6.dp))
                        Text(p.model.label, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        BigButton("Fertig", Icons.Filled.CheckCircle, primary = true, modifier = Modifier.width(260.dp).focusRequester(done)) { Pads.lrMode = false }
    }
}

@Composable
private fun MenuRow(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(modifier.fillMaxWidth(), onClick = onClick) { Text(text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
}
