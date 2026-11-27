package wtf.lennard.thorbro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var linkOpen by remember { mutableStateOf(false) }
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
                    val later = items.count { it.optString("state") in listOf("later", "error", "cancelled") }
                    if (later > 0) SmallButton("Alle starten ($later)", Icons.Filled.PlayArrow, Modifier.focusRequester(first)) { act { TransferQueue.start(ctx, null) } }
                    else SmallButton("Zum Store", Icons.Filled.CloudDownload, Modifier.focusRequester(first)) { Nav.go(Screen.STORE) }
                    Spacer(Modifier.padding(4.dp))
                    SmallButton("Per Link laden", Icons.Filled.Link) { linkOpen = true }
                }
            }
            listOf(
                "Läuft" to items.filter { it.optString("state") == "running" },
                "Wartet" to items.filter { it.optString("state") == "waiting" },
                "Für später" to items.filter { it.optString("state") == "later" },
                "Fehlgeschlagen" to items.filter { it.optString("state") == "error" },
                "Abgebrochen" to items.filter { it.optString("state") == "cancelled" },
            ).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                item(key = "g-$title") {
                    SettingsGroup(title) {
                        group.forEach { e ->
                            val up = e.optString("kind") == "upload"
                            val link = e.optString("kind") == "url"
                            val move = e.optString("kind") == "move"
                            val state = e.optString("state")
                            val sub = when (state) {
                                "running" -> listOfNotNull("${(e.optDouble("progress", 0.0) * 100).toInt()} %", e.optString("transfer").takeIf { e.has("transfer") && it.isNotEmpty() }).joinToString(" · ")
                                "error" -> e.optString("error")
                                "later" -> (if (link) e.optString("url").take(60) else fmtBytes(e.optLong("size"))) + " · A = jetzt starten"
                                "cancelled" -> "abgebrochen · A = neu starten oder entfernen"
                                else -> if (link) e.optString("url").take(80) else if (move) "→ " + e.optString("dest") else fmtBytes(e.optLong("size"))
                            }
                            val canCancel = state in listOf("running", "waiting", "later")
                            SettingRow((if (up) "⬆ RomM · " else if (link) "🔗 " else if (move) "📦 Einspielen · " else "⬇ ") + e.optString("name"), sub,
                                when { state == "error" -> Icons.Filled.ErrorOutline; state == "later" -> Icons.Filled.WatchLater; up -> Icons.Filled.CloudUpload; else -> Icons.Filled.CloudDownload },
                                warn = state == "error", value = if (canCancel) "X Abbrechen" else "Optionen",
                                // X on a row = cancel it right away (also the running one)
                                modifier = Modifier.hintA("Optionen").onPreviewKeyEvent { k ->
                                    if (canCancel && k.type == KeyEventType.KeyDown && k.key == Key.ButtonX) { act { TransferQueue.cancel(ctx, e.optString("id")) }; true } else false
                                }) { menu = e }
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
            "running" -> "Läuft gerade. Abbrechen stoppt die Übertragung sofort und löscht den halb geladenen Teil."
            "error" -> "Fehler: ${e.optString("error")}"
            "cancelled" -> "Abgebrochen."
            else -> fmtBytes(e.optLong("size"))
        }, options = buildList {
            if (state in listOf("running", "waiting", "later")) add("Abbrechen" to { act { TransferQueue.cancel(ctx, id) }; menu = null })
            if (state == "later" || state == "error" || state == "cancelled") add((if (state == "cancelled") "Neu starten" else "Jetzt starten") to { act { TransferQueue.start(ctx, id) }; menu = null })
            if (state != "running") add("Aus der Liste nehmen" to { act { TransferQueue.remove(ctx, id) }; menu = null })
        }) { menu = null }
    }

    if (linkOpen) LinkDialog(onClose = { linkOpen = false }) { url -> act { TransferQueue.addUrl(ctx, url, "", "", null) }; linkOpen = false }
}

/**
 * "Per Link laden": paste or type an http(s) link, the Thor loads the file itself into the transfer queue.
 * Console folder from the file extension, archives unpacked like a store download, an APK is installed afterwards.
 * Prefilled from the clipboard when it holds a link.
 */
@Composable
private fun LinkDialog(onClose: () -> Unit, onLoad: (String) -> Unit) {
    val ctx = LocalContext.current
    val clip = remember {
        runCatching {
            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        }.getOrNull()?.takeIf { wtf.lennard.thorbro.UrlDownload.validUrl(it) } ?: ""
    }
    var url by remember { mutableStateOf(clip) }
    val ok = wtf.lennard.thorbro.UrlDownload.validUrl(url.trim())
    val field = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    DialogOpen()
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        androidx.compose.foundation.layout.Column(Modifier.width(720.dp).clip(tileShape(22)).background(Sheet)
            .border(Theme.p.lineW, if (Theme.p.mascot) GlassLine else androidx.compose.ui.graphics.Color.Transparent, tileShape(22)).padding(24.dp)) {
            Text("Per Link laden", fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = HeadFont)
            Spacer(Modifier.padding(6.dp))
            Text("Der Thor lädt die Datei selbst. Spiele landen im ROM-Ordner ihrer Konsole (nach Dateiendung), Archive werden wie im Store entpackt, eine APK danach installiert.",
                fontSize = 14.sp, color = Muted)
            Spacer(Modifier.padding(6.dp))
            androidx.compose.material3.OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Link (http/https)") },
                isError = url.isNotBlank() && !ok, modifier = Modifier.fillMaxWidth().focusRequester(field))
            Spacer(Modifier.padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Laden", Icons.Filled.CloudDownload, primary = true, enabled = ok, modifier = Modifier.weight(1f)) { onLoad(url.trim()) }
                BigButton("Abbrechen", modifier = Modifier.weight(1f)) { onClose() }
            }
        }
    }
}
