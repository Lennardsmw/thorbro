package wtf.lennard.thorbro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.TransferQueue

/**
 * Store › Downloads: the transfer queue. What runs (with speed and time left), what waits, what was kept "für später",
 * what failed. Downloads and uploads to RomM, one after another. A starts / opens an entry; B goes back to the store.
 */
@Composable
fun TransfersScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    WhileVisible { while (true) { items = withContext(Dispatchers.IO) { TransferQueue.list(ctx).objs() }; delay(700) } }
    val first = remember { FocusRequester() }
    FocusOnStart(first, items.isEmpty())
    val list = rememberLazyListState()
    HeaderFollows(list)
    fun act(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; items = withContext(Dispatchers.IO) { TransferQueue.list(ctx).objs() } }

    CenteredFocus {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (items.isEmpty()) "Nichts in der Warteschlange" else "${items.size} in der Warteschlange · eins nach dem anderen",
                        fontSize = 15.sp, color = Muted, modifier = Modifier.weight(1f))
                    val later = items.count { it.optString("state") in listOf("later", "error") }
                    if (later > 0) SmallButton("Alle starten ($later)", Icons.Filled.PlayArrow, Modifier.focusRequester(first)) { act { TransferQueue.start(ctx, null) } }
                    else SmallButton("Zum Store", Icons.Filled.CloudDownload, Modifier.focusRequester(first)) { Nav.go(Screen.STORE) }
                }
            }
            listOf(
                "Läuft" to items.filter { it.optString("state") == "running" },
                "Wartet" to items.filter { it.optString("state") == "waiting" },
                "Für später" to items.filter { it.optString("state") == "later" },
                "Fehlgeschlagen" to items.filter { it.optString("state") == "error" },
            ).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                item(key = "g-$title") {
                    SettingsGroup(title) {
                        group.forEach { e ->
                            val up = e.optString("kind") == "upload"
                            val state = e.optString("state")
                            val sub = when (state) {
                                "running" -> listOfNotNull("${(e.optDouble("progress", 0.0) * 100).toInt()} %", e.optString("transfer").takeIf { e.has("transfer") && it.isNotEmpty() }).joinToString(" · ")
                                "error" -> e.optString("error")
                                "later" -> fmtBytes(e.optLong("size")) + " · A = jetzt starten"
                                else -> fmtBytes(e.optLong("size"))
                            }
                            SettingRow((if (up) "⬆ RomM · " else "⬇ ") + e.optString("name"), sub,
                                when { state == "error" -> Icons.Filled.ErrorOutline; state == "later" -> Icons.Filled.WatchLater; up -> Icons.Filled.CloudUpload; else -> Icons.Filled.CloudDownload },
                                warn = state == "error", value = if (state == "running") "" else "Optionen") { menu = e }
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(4.dp)) }
        }
    }

    menu?.let { e ->
        val id = e.optString("id")
        val state = e.optString("state")
        ConsoleMenu(e.optString("name"), when (state) {
            "running" -> "Läuft gerade. Ein abgebrochener Download macht beim nächsten Start dort weiter, wo er war."
            "error" -> "Fehler: ${e.optString("error")}"
            else -> fmtBytes(e.optLong("size"))
        }, options = buildList {
            if (state == "later" || state == "error") add("Jetzt starten" to { act { TransferQueue.start(ctx, id) }; menu = null })
            if (state != "running") add("Aus der Liste nehmen" to { act { TransferQueue.remove(ctx, id) }; menu = null })
        }) { menu = null }
    }
}
