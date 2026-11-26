package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.History
import wtf.lennard.thorbro.Media
import wtf.lennard.thorbro.Romm
import wtf.lennard.thorbro.RommSync
import wtf.lennard.thorbro.Saves
import wtf.lennard.thorbro.SwitchFirmware

/** a game that has a save on the Thor, for the Spielstände shelf */
private class SaveGame(val sys: String, val rom: JSONObject)

/**
 * Sync: state + the one button on top, the settings as rows, then the games with saves (→ their Spielstände page),
 * profiles and the last downloads. Resetting a save happens per game on its Spielstände page, not per emulator here.
 */
@Composable
fun SyncScreenNew() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(4000); tick++ } }
    var auto by remember { mutableStateOf(RommSync.auto(ctx)) }
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(org.json.JSONArray()) }
    var profiles by remember { mutableStateOf(emptyList<JSONObject>()) }
    var profileOpen by remember { mutableStateOf<JSONObject?>(null) }
    var profileAdd by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var savesOf by remember { mutableStateOf<SaveGame?>(null) }
    LaunchedEffect(tick) { profiles = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Profiles.json(ctx).optJSONArray("profiles").objs() } }
    LaunchedEffect(tick) { history = withContext(Dispatchers.IO) { History.list(ctx, 60) } }
    val withSaves by produceState<List<SaveGame>?>(null, tick / 8) {
        value = withContext(Dispatchers.IO) {
            runCatching { RommSync.localSaves(ctx).distinctBy { it.rom.optString("path") }.map { SaveGame(it.system, it.rom) } }.getOrDefault(emptyList())
        }
    }
    val st = remember(tick) { Romm.status(ctx) }
    val connected = st.optBoolean("connected")
    val uploading = RommSync.current?.takeIf { !it.done } != null
    val entries = history.objs()
    val lastSave = entries.firstOrNull { it.optString("kind").startsWith("save") }
    LaunchedEffect(Unit) { delay(250); runCatching { first.requestFocus() } }

    fun syncNow() {
        if (busy.isNotEmpty() || !connected) return
        busy = "Synchronisiert …"
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { RommSync.pullSaves(ctx) }.getOrElse { JSONObject().put("error", it.message) } }
            val r = withContext(Dispatchers.IO) { runCatching { RommSync.syncSaves(ctx, null) }.getOrElse { JSONObject().put("error", it.message) } }
            busy = ""
            note = when {
                r.has("error") -> "Fehler: ${r.optString("error")}"
                r.optBoolean("off") -> "„Nur lokal“ ist an – System › Daten sparen"
                else -> "${r.optInt("uploaded")} hoch · ${d.optInt("downloaded")} runter"
            }
            tick++
        }
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeaderFollows(list)
    CenteredFocus {
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // state + the one main action
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassFill).border(1.dp, GlassLine, RoundedCornerShape(20.dp))
                    .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Ok else Bad))
                            Spacer(Modifier.width(10.dp))
                            Text(if (connected) st.optString("url").removePrefix("https://").removePrefix("http://") else "RomM nicht verbunden",
                                fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(note.ifEmpty {
                            listOfNotNull(
                                if (connected && st.optBoolean("viaLan")) "über Heimnetz" else null,
                                if (connected) (if (auto) "Auto-Sync an" else "Auto-Sync aus") else "unten RomM-Server hinzufügen",
                                lastSave?.let { "letzter Spielstand-Sync ${agoShort(it.optLong("t"))}" },
                            ).joinToString(" · ")
                        }, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 20.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    BigButton(busy.ifEmpty { "Jetzt synchronisieren" }, Icons.Filled.Sync, primary = true, enabled = connected && busy.isEmpty(),
                        modifier = Modifier.width(300.dp).focusRequester(first)) { syncNow() }
                }
            }
            // games with saves → their Spielstände page (backups, restore, send to friends)
            item {
                Column {
                    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Spielstände", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("  Spiel wählen: Backups, zurücksetzen, an Freunde", fontSize = 14.sp, color = Muted, maxLines = 1)
                    }
                    val list = withSaves
                    when {
                        list == null -> Text("Suche Spielstände …", color = Muted, modifier = Modifier.padding(8.dp))
                        list.isEmpty() -> Text("Noch keine Spielstände auf dem Thor.", color = Muted, modifier = Modifier.padding(8.dp))
                        else -> LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(list, key = { it.rom.optString("path") }) { g -> SaveGameTile(g) { savesOf = g } }
                        }
                    }
                }
            }
            item {
                SettingsGroup("Abgleich mit RomM") {
                    SettingRow("Auto-Sync", "nach jedem Speichern hochladen", Icons.Filled.CloudDone, on = auto) { auto = !auto; RommSync.setAuto(ctx, auto) }
                    SettingRow(if (uploading) "Lädt hoch …" else "Alle Spiele hochladen", "was RomM noch fehlt", Icons.Filled.CloudUpload,
                        value = if (uploading) "Fortschritt: Select" else "") {
                        if (!uploading && connected) scope.launch { RommSync.start(ctx, null, true); tick++ }
                    }
                    SettingRow("Alle Spielstände sichern", "Backup auf dem Thor", Icons.Filled.History) {
                        scope.launch { withContext(Dispatchers.IO) { runCatching { Saves.backup(ctx, null) } }; note = "Alle Spielstände gesichert"; tick += 8 }
                    }
                    SettingRow(if (connected) "RomM-Server" else "RomM-Server hinzufügen", if (connected) "" else "Adresse eingeben, Code in RomM bestätigen",
                        if (connected) Icons.Filled.Dns else Icons.Filled.AddLink,
                        value = if (connected) st.optString("url").removePrefix("https://").removePrefix("http://") else "") { serverOpen = true }
                }
            }
            item {
                SettingsGroup("Profile · jedes Profil hat pro Spiel seinen eigenen Spielstand") {
                    profiles.forEach { p ->
                        SettingRow(p.optString("name"), if (p.optString("id") == wtf.lennard.thorbro.Profiles.MAIN) "erstes Profil" else "Slot „${p.optString("slot")}“ in RomM",
                            value = "Ändern") { profileOpen = p }
                    }
                    if (profiles.size < 8) SettingRow("Neues Profil", icon = Icons.Filled.Add) { profileAdd = true }
                }
            }
            val transfers = entries.filter { it.optString("kind") in listOf("download", "rom_up") }.take(6)
            if (transfers.isNotEmpty()) {
                item { Text("Zuletzt geladen", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(start = 6.dp)) }
                transfers.forEach { e -> item { TransferRow(e) } }
            }
        }
    }

    if (serverOpen) RommServerDialog(onDone = { note = it; serverOpen = false; tick++ }) { serverOpen = false; tick++ }
    ProfileDialogs(profileOpen, profileAdd, onDone = { note = it; tick++ }) { profileOpen = null; profileAdd = false }
    savesOf?.let { g -> GameSaves(g.sys, g.rom.getString("path"), g.rom.optString("name").substringBefore(" (").substringBefore(" [")) { savesOf = null; tick += 8 } }
}

@Composable
private fun SaveGameTile(g: SaveGame, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val m by produceState(JSONObject(), g.rom.optString("path")) {
        value = withContext(Dispatchers.IO) { runCatching { Media.metaFor(ctx, g.sys, listOf(g.rom)).optJSONObject(g.rom.getString("path")) }.getOrNull() ?: JSONObject() }
    }
    val sysName = remember(g.sys) { g.sys }
    ArtTile(gameTitle(g.rom, m), sysName, cover(g.sys, g.rom, m), Modifier.width(ART), fit = g.sys == "ports", onClick = onClick)
}
