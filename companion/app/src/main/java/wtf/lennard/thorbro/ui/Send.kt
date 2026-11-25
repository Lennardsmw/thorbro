package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Gifts
import wtf.lennard.thorbro.Jobs
import java.io.File

private val KINDS = listOf("save" to "Nur Spielstand", "game" to "Nur Spiel", "both" to "Spiel + Spielstand")

/**
 * "Verschicken": one menu for a save and a game. Opened from "Weitergeben" (game preselected) and from the save
 * sheet (save preselected). Per friend a chip shows the likely way and time; several friends can be picked.
 * "Thor in der Nähe" at the bottom is the old hand-over without friendship (Wi-Fi Direct / WLAN + PIN).
 */
@Composable
fun SendSheet(sys: String, path: String, title: String, kind: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rom = remember(path) { File(path) }
    // a folder game can go to friends (packed), only the nearby hand-over needs a single file
    val kinds = remember(sys) { if (sys == "ports") KINDS.take(1) else KINDS }
    var k by remember { mutableStateOf(kinds.indexOfFirst { it.first == kind }.coerceAtLeast(0)) }
    var routes by remember { mutableStateOf<JSONObject?>(null) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Jobs.Job?>(null) }
    val first = remember { FocusRequester() }
    val what = kinds[k].first
    LaunchedEffect(what) {
        routes = null
        routes = withContext(Dispatchers.IO) { runCatching { Gifts.routes(ctx, sys, rom, what) }.getOrElse { JSONObject().put("error", it.message) } }
        delay(150); runCatching { first.requestFocus() }
    }
    job?.let { j ->
        LaunchedEffect(j) {
            while (!j.done) { msg = j.message.ifEmpty { "Bereite vor …" } + (if (j.progress > 0) " · ${(j.progress * 100).toInt()} %" else ""); delay(300) }
            if (j.error != null) { msg = j.error!!; bad = true; job = null }
            else { msg = j.result?.optString("done") ?: "Verschickt"; bad = false; delay(1400); onClose() }
        }
    }
    val friends = routes?.optJSONArray("friends").objs()
    val size = routes?.optLong("size") ?: 0L
    fun send() {
        if (picked.isEmpty() || job != null) return
        bad = false; msg = "Bereite vor …"
        job = Jobs.start("Verschicken: $title") { j -> Gifts.send(ctx, picked.toList(), what, sys, rom, j).also { if (it.has("error")) error(it.getString("error")) } }
    }

    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.fillMaxSize().background(Bg).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.ButtonL1 -> { k = (k + kinds.size - 1) % kinds.size; true }
                Key.ButtonR1 -> { k = (k + 1) % kinds.size; true }
                Key.ButtonStart -> { send(); true }
                else -> false
            }
        }.padding(horizontal = 40.dp, vertical = 28.dp)) {
            Text("Verschicken", fontSize = 15.sp, color = Muted)
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            // what: L1 / R1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                kinds.forEachIndexed { i, (_, label) ->
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (i == k) OnAccent else Ink,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (i == k) Accent else Color(0x22FFFFFF))
                            .padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (kinds.size > 1) Text("L1 / R1", fontSize = 12.sp, color = Muted)
            }
            Text("An wen", fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    routes == null -> item { Text("Schaue, wer wie erreichbar ist …", color = Muted) }
                    routes!!.has("error") -> item { Text(routes!!.optString("error"), color = Bad) }
                    friends.isEmpty() -> item { Text("Noch keine Freunde. Im Freunde-Menü (L2) mit Y hinzufügen.", color = Muted) }
                }
                friends.forEachIndexed { i, f ->
                    item {
                        val on = f.optString("id") in picked
                        FocusCard(Modifier.fillMaxWidth().firstFocus(first, i == 0), onClick = {
                            picked = if (on) picked - f.optString("id") else picked + f.optString("id")
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Avatar(f.optString("name"), 40)
                                    if (f.optBoolean("online")) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(0xFF3FB950))
                                        .border(2.dp, Card, CircleShape))
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(f.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(when {
                                        f.optBoolean("near") -> "online · im selben Netz"
                                        f.optBoolean("online") -> "online"
                                        else -> "zuletzt ${agoText(f.optLong("seen"))}"
                                    }, fontSize = 13.sp, color = Muted)
                                }
                                val wayOn = f.optString("way") != "wait"
                                Text(f.optString("label"), fontSize = 13.sp, color = if (wayOn) Ink else Muted, maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (wayOn) Soft else Color(0x14FFFFFF))
                                        .padding(horizontal = 12.dp, vertical = 5.dp))
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Filled.CheckCircle, if (on) "ausgewählt" else null, Modifier.size(26.dp),
                                    tint = if (on) Ok else Soft)
                            }
                        }
                    }
                }
                // the old way: a Thor nearby without friendship (only a single game file)
                if (what == "game" && rom.isFile) item {
                    FocusCard(Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching { wtf.lennard.thorbro.Netplay.share(ctx, sys, rom) }.getOrElse { JSONObject().put("error", it.message) }
                            }
                            bad = res.has("error")
                            msg = when {
                                res.has("error") -> res.optString("error")
                                res.optBoolean("direct") -> "Wird angeboten – der Thor in der Nähe bekommt eine Einladung. Beenden unter Multiplayer."
                                else -> "Wird angeboten · PIN ${res.optString("pin")} – Wi-Fi Direct ist aus, der andere Thor braucht dasselbe WLAN."
                            }
                        }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Wifi, null, Modifier.size(26.dp)); Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Thor in der Nähe (ohne Freundschaft)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("wie bisher: Wi-Fi Direct oder WLAN + PIN", fontSize = 13.sp, color = Muted)
                            }
                            Text("›", fontSize = 22.sp, color = Muted)
                        }
                    }
                }
            }
            if (msg.isNotEmpty()) Text(msg, color = if (bad) Bad else if (job != null) Muted else Ok, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ButtonHints("A" to "An/abwählen", "B" to "Zurück")
                Spacer(Modifier.weight(1f))
                val names = friends.filter { it.optString("id") in picked }.map { it.optString("name") }
                BigButton(when {
                    job != null -> "Verschickt …"
                    names.isEmpty() -> "Freunde auswählen"
                    else -> "An ${if (names.size <= 2) names.joinToString(" & ") else "${names.size} Freunde"} schicken" + (if (size > 0) " · ${fmtBytes(size)}" else "")
                }, Icons.Filled.Send, primary = names.isNotEmpty(), enabled = names.isNotEmpty() && job == null) { send() }
            }
        }
    }
}

internal fun agoText(ms: Long): String {
    if (ms <= 0) return "unbekannt"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when { m < 2 -> "gerade eben"; m < 60 -> "vor $m Min."; m < 48 * 60 -> "vor ${m / 60} Std."; else -> "vor ${m / 1440} Tagen" }
}
