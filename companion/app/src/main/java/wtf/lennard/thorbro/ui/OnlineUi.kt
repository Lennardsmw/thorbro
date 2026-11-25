package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject
import wtf.lennard.thorbro.ButtonCode

// The Thor's screen is 960 × 540 dp: both dialogs fit in its height without scrolling.

/** one button of a code as it looks on the controller: D-pad arrows, A red, B yellow, X blue, Y green; null = still empty */
@Composable
internal fun KeyCap(sym: String?, size: Int = 64, onClick: (() -> Unit)? = null) {
    val face = when (sym) { "A" -> Color(0xFFE5484D); "B" -> Color(0xFFF2C230); "X" -> Color(0xFF3E8EF7); "Y" -> Color(0xFF3FB950); else -> null }
    val arrow = when (sym) {
        "↑" -> Icons.Filled.KeyboardArrowUp; "↓" -> Icons.Filled.KeyboardArrowDown
        "←" -> Icons.Filled.KeyboardArrowLeft; "→" -> Icons.Filled.KeyboardArrowRight; else -> null
    }
    val shape = if (face != null) CircleShape else RoundedCornerShape(14.dp)
    Box(Modifier.size(size.dp).clip(shape)
        .background(face ?: if (sym != null) Color(0xFF3A3D45) else Color(0xFF1C1E23))
        .border(2.dp, if (sym == null) Color(0xFF33363E) else Color.White.copy(alpha = 0.18f), shape)
        .let { if (onClick != null) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center) {
        when {
            arrow != null -> Icon(arrow, null, Modifier.size((size * 0.75).dp), tint = Color.White)
            face != null -> Text(sym!!, fontSize = (size * 0.46).sp, fontWeight = FontWeight.Black, color = if (sym == "B") Color(0xFF2A2200) else Color.White)
        }
    }
}

@Composable
internal fun CodeRow(code: List<String?>, size: Int = 64) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, s ->
            if (i == 4) Spacer(Modifier.width(10.dp)) // two groups of four read easier
            KeyCap(s, size)
        }
    }
}

@Composable
private fun Panel(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(modifier.width(780.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF0141519)).padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** host side: just the friend code, big */
@Composable
fun CodeDialog(o: JSONObject, onNew: () -> Unit, onStop: () -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(o) { delay(150); runCatching { first.requestFocus() } }
    val code = o.optString("code").split(' ').filter { it.isNotEmpty() }
    Panel(onClose) {
        Text("Freundes-Code", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 18.dp))
        if (code.isNotEmpty()) CodeRow(code, 70)
        Text(if (code.isEmpty()) "kein Code aktiv" else "gilt noch ${o.optInt("codeMinutes")} Min.", fontSize = 14.sp, color = Muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Fertig", primary = true, modifier = Modifier.firstFocus(first, true)) { onClose() }
            BigButton("Neuer Code") { onNew() }
            BigButton("Online aus") { onStop() }
        }
    }
}

/**
 * Guest side: the code is pressed on the controller (D-pad and A B X Y), so every key goes into the code here and
 * none moves the focus. L1 deletes, Select/Start cancels. The buttons on screen are for touch.
 */
@Composable
fun CodePad(onClose: () -> Unit, onDone: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { box.requestFocus() } }
    // handed on as digits 1–8 (what ButtonCode.normalize reads); the internal 0–7 lost every ↑
    fun press(i: Int) { if (code.length < 8) { code += i; if (code.length == 8) onDone(code.map { (it - '0' + 1).toString() }.joinToString("")) } }
    Panel(onClose, Modifier.focusRequester(box).focusable().onPreviewKeyEvent { e ->
        val i = when (e.key) {
            Key.DirectionUp -> 0; Key.DirectionDown -> 1; Key.DirectionLeft -> 2; Key.DirectionRight -> 3
            Key.ButtonA -> 4; Key.ButtonB -> 5; Key.ButtonX -> 6; Key.ButtonY -> 7
            Key.ButtonL1, Key.Backspace -> 8
            Key.ButtonSelect, Key.ButtonStart, Key.Back, Key.Escape -> 9
            else -> -1
        }
        if (i < 0) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) when (i) { 8 -> code = code.dropLast(1); 9 -> onClose(); else -> press(i) }
        true
    }) {
        Text("Freundes-Code eingeben", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 18.dp))
        CodeRow(List(8) { i -> code.getOrNull(i)?.let { ButtonCode.SYMBOLS[it - '0'] } })
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ButtonCode.SYMBOLS.forEachIndexed { i, sym -> KeyCap(sym, 52) { press(i) } }
            Spacer(Modifier.width(10.dp))
            BigButton("⌫") { code = code.dropLast(1) }
            BigButton("✕") { onClose() }
        }
    }
}

/** everyone this Thor knows: online friends (joined once with a code) and Thors nearby (invites over the air) */
@Composable
fun FriendsDialog(
    online: List<JSONObject>, nearby: List<JSONObject>,
    onPlay: (JSONObject) -> Unit, onRemoveOnline: (JSONObject) -> Unit, onRemoveNearby: (JSONObject) -> Unit,
    onCode: () -> Unit, onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Panel(onClose) {
        Text("Freunde", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (online.isEmpty() && nearby.isEmpty())
                Text("Noch keine. Lass dir einen Freundes-Code zeigen und gib ihn ein – danach findet ihr euch immer wieder.",
                    fontSize = 15.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
            online.forEachIndexed { i, k ->
                FriendRow(k.optString("name"), "online") {
                    BigButton("Mitspielen", primary = true, modifier = Modifier.firstFocus(first, i == 0)) { onPlay(k) }
                    BigButton("Entfernen") { onRemoveOnline(k) }
                }
            }
            nearby.forEach { f ->
                FriendRow(f.optString("name"), "in der Nähe · lädt per Benachrichtigung ein") {
                    BigButton("Entfernen", modifier = Modifier.firstFocus(first, online.isEmpty() && f === nearby.first())) { onRemoveNearby(f) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Code eingeben", modifier = Modifier.firstFocus(first, online.isEmpty() && nearby.isEmpty())) { onCode() }
            BigButton("Schließen") { onClose() }
        }
    }
}

@Composable
private fun FriendRow(name: String, sub: String, buttons: @Composable () -> Unit) {
    Row(Modifier.width(720.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1E23)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3A3D45)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 13.sp, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}
