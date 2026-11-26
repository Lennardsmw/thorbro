package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
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
import wtf.lennard.thorbro.Updater

/** "Dev-Versionen": test builds from GitHub, the home network or an own URL (same list as the web panel) */
object DevUi { var open by mutableStateOf(false) }

/** small muted "v<versionName>" fixed bottom left; tapping it opens the Dev dialog (touch only, the pad never lands on it) */
@Composable
fun VersionTag(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val v = remember { Updater.current(ctx) }
    Text("v$v", fontSize = 11.sp, color = Muted.copy(alpha = 0.7f), maxLines = 1,
        modifier = modifier.padding(start = 10.dp, bottom = 6.dp).focusProperties { canFocus = false }
            .clickable(remember { MutableInteractionSource() }, null) { DevUi.open = true }.padding(4.dp))
}

private fun ago(iso: String): String = runCatching {
    val m = (System.currentTimeMillis() - java.time.Instant.parse(iso).toEpochMilli()) / 60000
    when { m < 60 -> "vor ${maxOf(m, 1)} Min."; m < 1440 -> "vor ${m / 60} Std."; m < 2880 -> "gestern"; else -> "vor ${m / 1440} Tagen" }
}.getOrDefault("")

@Composable
fun DevDialog(onClose: () -> Unit = { DevUi.open = false }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(Updater.devSource(ctx)) }
    var custom by remember { mutableStateOf(Updater.devCustom(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var openFeatures by remember { mutableStateOf(setOf<Int>()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    LaunchedEffect(source, reload) {
        data = null; msg = "Lade …"; openFeatures = emptySet()
        val r = withContext(Dispatchers.IO) { Updater.setDevSource(ctx, source, custom); Updater.devVersions(ctx) }
        data = r
        val n = r.optJSONArray("versions")?.length() ?: 0
        msg = r.optString("error").ifEmpty { if (n == 0) "Keine Test-Builds in dieser Quelle." else "Installiert: ${r.optString("current")}" }
    }

    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(760.dp).heightIn(max = 620.dp).clip(tileShape(22)).background(Sheet)
            .border(Theme.p.lineW, if (Theme.p.mascot) GlassLine else Color.Transparent, tileShape(22)).padding(24.dp)) {
            Text("Dev-Versionen", fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Updater.DEV_SOURCES.forEachIndexed { i, (id, label) ->
                    BigButton(label, primary = source == id, modifier = Modifier.weight(1f).firstFocus(first, i == 0)) {
                        if (source == id) reload++ else source = id
                    }
                }
            }
            if (source == "custom") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(custom, { custom = it.trim() }, singleLine = true, label = { Text("URL (index.json)") }, modifier = Modifier.weight(1f))
                    BigButton("Laden", modifier = Modifier.width(140.dp)) { reload++ }
                }
            }
            Text(msg, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val vs = data?.optJSONArray("versions")
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vs?.length() ?: 0) { i ->
                    val v = vs!!.getJSONObject(i)
                    val cur = v.optBoolean("current")
                    val feats = v.optJSONArray("features")
                    val nf = feats?.length() ?: 0
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GlassFill)
                        .border(if (cur) 2.dp else 0.dp, if (cur) Accent else Color.Transparent, RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.optString("versionName") + if (cur) "  · installiert" else "", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (cur) Accent else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(v.optString("branch"), ago(v.optString("published")),
                                    v.optLong("size").takeIf { it > 0 }?.let { "${it / 1_000_000} MB" } ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                                    fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (nf > 0) {
                                BigButton(if (i in openFeatures) "Weniger" else "$nf Features", modifier = Modifier.width(150.dp)) {
                                    openFeatures = if (i in openFeatures) openFeatures - i else openFeatures + i
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            when {
                                cur -> {}
                                v.optBoolean("older") -> Text("älter – nur nach\nDeinstallation", fontSize = 12.sp, color = Muted)
                                else -> BigButton(if (busy) "…" else "Installieren", Icons.Filled.SystemUpdate, primary = true, enabled = !busy,
                                    modifier = Modifier.width(190.dp)) {
                                    busy = true; msg = "Lade ${v.optString("versionName")} …"
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { Updater.installDev(ctx, v.optString("url")) }
                                        busy = false
                                        msg = r.optString("error").ifEmpty { "Installiere ${r.optString("latest")} – falls Android fragt: „Aktualisieren“. ThorBro startet neu." }
                                    }
                                }
                            }
                        }
                        if (i in openFeatures) Column(Modifier.padding(top = 8.dp)) {
                            for (k in 0 until nf) Text("• " + feats!!.optString(k), fontSize = 13.sp, color = Ink)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BigButton("Schließen", modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}
