package wtf.lennard.thorbro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import wtf.lennard.thorbro.PadModel
import wtf.lennard.thorbro.Players

/**
 * System › Controller: the four player places as cards (who is P1–P4, assign with A; a connected pad shows its drawing),
 * the rarer things (pairing, LEDs, L+R order, Thor as gamepad) as one row of buttons. They open the old panels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllerScreenNew() {
    val ctx = LocalContext.current
    Pads.appCtx = ctx.applicationContext
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(800); tick++ } }
    val panel = ControllerNav.panel.value
    DisposableEffect(Unit) { onDispose { Pads.lrMode = false; ControllerNav.panel.value = null; Pads.assigning = 0; Pads.onAssigned = null } }
    if (panel != null) { ControllerPanels(panel); return }
    val pads = remember(tick) { livePads(ctx) }
    if (Pads.lrMode) { OrderScreen(pads); return }
    val players = remember(tick) { Players.overview(ctx).optJSONArray("players").objs() }
    var menu by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        Pads.onAssigned = { slot, d -> Players.assign(ctx, slot, d); tick++ }
        onDispose { }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (slot in 1..4) {
                val p = players.firstOrNull { it.optInt("slot") == slot }
                val live = pads.firstOrNull { it.slot == slot }
                PlayerCard(slot, p, live, Modifier.weight(1f).fillMaxHeight().then(if (slot == 1) Modifier.focusRequester(first) else Modifier)) {
                    if (p == null || p.isNull("name")) { Pads.assigning = slot; Pads.assignArmedAt = System.currentTimeMillis() } else menu = slot
                }
            }
        }
        // wraps instead of cutting the labels (the System rail takes some width)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Reihenfolge (L+R)", Icons.Filled.SwapHoriz) { Players.clearAll(ctx); Pads.lrMode = true }
            BigButton("Koppeln", Icons.Filled.BluetoothSearching) { ControllerNav.panel.value = "new" }
            BigButton("Gekoppelt", Icons.Filled.Bluetooth) { ControllerNav.panel.value = "paired" }
            BigButton("LEDs", Icons.Filled.Lightbulb) { ControllerNav.panel.value = "leds" }
            BigButton("Thor als Gamepad", Icons.Filled.Gamepad) { ctx.startActivity(Intent(ctx, GamepadActivity::class.java)) }
        }
    }

    menu?.let { slot ->
        val p = players.firstOrNull { it.optInt("slot") == slot }
        ConsoleMenu("Spieler $slot", p?.optString("name").orEmpty() + if (p?.optBoolean("connected") == true) "" else " · nicht verbunden",
            options = listOf(
                "Anderen Controller zuweisen" to { menu = null; Pads.assigning = slot; Pads.assignArmedAt = System.currentTimeMillis() },
                "Platz leeren" to { Players.clear(ctx, slot); tick++; menu = null },
            )) { menu = null }
    }
}

/** one player place: lamp row, the controller drawing, its name and battery; empty places say how to fill them */
@Composable
private fun PlayerCard(slot: Int, p: JSONObject?, live: LivePad?, modifier: Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    val name = p?.takeIf { !it.isNull("name") }?.optString("name")
    val waiting = Pads.assigning == slot
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fx(fx, shape, lift = 1.04f, onClick = onClick)
        .background(if (live != null) Color(0x26FFFFFF) else Color(0x0DFFFFFF)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("P$slot", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (name != null) Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.optBoolean("connected")) Ok else Color(0x40FFFFFF)))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                waiting -> Text("Taste auf dem Controller drücken …", fontSize = 14.sp, color = Accent, maxLines = 3)
                live != null -> PadArt(live.model, Modifier.fillMaxWidth(0.9f).aspectRatio(1.45f))
                name != null -> Icon(Icons.Filled.Gamepad, null, Modifier.size(52.dp), tint = Muted)
                else -> Text("frei", fontSize = 16.sp, color = Color(0x66FFFFFF))
            }
        }
        Text(when {
            live != null && live.model != PadModel.GENERIC -> live.model.label
            name != null -> name
            else -> "A = zuweisen"
        }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (name != null) Color.White else Muted)
        if (live != null && live.model == PadModel.THOR) Text("eingebaut", fontSize = 12.sp, color = Muted)
        else if (live != null) Battery(live.battery)
        else Text(if (name != null) "nicht verbunden" else " ", fontSize = 12.sp, color = Muted)
    }
}

