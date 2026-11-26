package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import wtf.lennard.thorbro.SwitchFirmware
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
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
import wtf.lennard.thorbro.Romm
import wtf.lennard.thorbro.RommSync
import wtf.lennard.thorbro.Saves
import java.io.File

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "gerade eben"; m < 60 -> "vor $m min"; m < 24 * 60 -> "vor ${m / 60} h"
        else -> java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(t))
    }
}

/** "Ordner „Saves“ (SD)" means nothing to a player; name it after the console/emulator instead */
private fun friendly(l: JSONObject): String {
    val emu = l.optString("emulator"); val name = l.optString("name"); val id = l.optString("id")
    return when {
        id.startsWith("port-") -> name
        id.startsWith("saves") -> "Switch" + (if (id.endsWith("sd")) " (SD)" else "")
        id.startsWith("retroarch-states") || id.startsWith("ppsspp-states") -> emu.substringBefore("/") + " Savestates"
        id.startsWith("melonds") -> "Nintendo DS"
        id.startsWith("gba") -> "Game Boy Advance"
        id.startsWith("azahar") -> "3DS (Azahar)"
        id.startsWith("dolphin-gc") -> "GameCube"
        id.startsWith("dolphin-wii") -> "Wii"
        id == "nethersx2" -> "PlayStation 2"
        id == "duckstation" -> "PlayStation"
        id == "cemu" -> "Wii U"
        id.startsWith("ppsspp") -> "PSP"
        id.startsWith("retroarch") -> "RetroArch"
        else -> emu.substringBefore("/").ifEmpty { name }
    }
}

/** Sync with RomM: big buttons, what happened lately, and resetting a broken save */
@Composable
fun SyncScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(4000); tick++ } }
    var auto by remember { mutableStateOf(RommSync.auto(ctx)) }
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(org.json.JSONArray()) }
    var saves by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf<JSONObject?>(null) }
    var profiles by remember { mutableStateOf(emptyList<JSONObject>()) }
    var profileOpen by remember { mutableStateOf<JSONObject?>(null) }
    var profileAdd by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(emptyList<JSONObject>()) }
    WhileVisible { while (true) { live = wtf.lennard.thorbro.Background.list().objs(); delay(500) } }
    LaunchedEffect(live.count { !it.optBoolean("done") }) { tick++ } // a finished game shows up in the list right away
    LaunchedEffect(tick) { profiles = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Profiles.json(ctx).optJSONArray("profiles").objs() } }
    LaunchedEffect(tick) { history = withContext(Dispatchers.IO) { History.list(ctx, 200) } }
    LaunchedEffect(tick / 8) { saves = withContext(Dispatchers.IO) { runCatching { Saves.overview(ctx) }.getOrNull() } }
    val st = remember(tick) { Romm.status(ctx) }
    val connected = st.optBoolean("connected")
    val uploading = RommSync.current?.takeIf { !it.done } != null
    val entries = history.objs()
    val lastSave = entries.firstOrNull { it.optString("kind").startsWith("save") }

    TileGrid(first) {
        header("RomM", if (connected) st.optString("url").removePrefix("https://").removePrefix("http://") +
            (if (st.optBoolean("viaLan")) " · über Heimnetz (schnell)" else "") +
            (lastSave?.let { " · letzter Spielstand-Sync ${ago(it.optLong("t"))}" } ?: "") else "nicht verbunden – unten „RomM-Server hinzufügen“")
        item {
            ConsoleTile(if (busy.isNotEmpty()) busy else "Jetzt synchronisieren", note.ifEmpty { "Spielstände hoch und runter" },
                Icons.Filled.Sync, accent = true, modifier = Modifier.firstFocus(first, true)) {
                if (busy.isNotEmpty() || !connected) return@ConsoleTile
                busy = "Synchronisiert …"
                scope.launch {
                    val d = withContext(Dispatchers.IO) { runCatching { RommSync.pullSaves(ctx) }.getOrElse { JSONObject().put("error", it.message) } }
                    val r = withContext(Dispatchers.IO) { runCatching { RommSync.syncSaves(ctx, null) }.getOrElse { JSONObject().put("error", it.message) } }
                    busy = ""
                    note = when {
                        r.has("error") -> "Fehler: ${r.optString("error")}"
                        r.optBoolean("off") -> "„Nur lokal“ ist an – Einstellungen → Daten sparen"
                        else -> "${r.optInt("uploaded")} hoch · ${d.optInt("downloaded")} runter"
                    }
                    tick++
                }
            }
        }
        item {
            ConsoleTile(if (uploading) "Lädt hoch …" else "Alle Spiele hochladen", if (uploading) "Fortschritt oben rechts" else "was RomM noch fehlt",
                Icons.Filled.CloudUpload) {
                if (uploading || !connected) return@ConsoleTile
                scope.launch {
                    RommSync.start(ctx, null, true)
                    tick++
                }
            }
        }
        item {
            ConsoleTile(if (auto) "Auto-Sync an" else "Auto-Sync aus", if (auto) "lädt nach jedem Speichern hoch" else "nur per Knopf",
                if (auto) Icons.Filled.CloudDone else Icons.Filled.SyncDisabled, accent = auto) { auto = !auto; RommSync.setAuto(ctx, auto) }
        }
        item {
            if (connected) ConsoleTile("RomM-Server", st.optString("url").removePrefix("https://").removePrefix("http://"), Icons.Filled.Dns) { serverOpen = true }
            else ConsoleTile("RomM-Server hinzufügen", "Adresse eingeben, Code in RomM bestätigen", Icons.Filled.AddLink, accent = true) { serverOpen = true }
        }
        if (connected) item {
            var fwBusy by remember { mutableStateOf(false) }
            var fwNote by remember { mutableStateOf("für RomBro auf dem Mac") }
            ConsoleTile(if (fwBusy) "Lädt hoch …" else "Switch-Keys & Firmware zu RomM", fwNote, Icons.Filled.Key) {
                if (fwBusy) return@ConsoleTile
                fwBusy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { SwitchFirmware.sync(ctx, force = true) }
                    fwBusy = false
                    val up = r.optJSONArray("uploaded")?.length() ?: 0
                    fwNote = when {
                        r.optBoolean("needsRepair") -> "RomM neu koppeln für Firmware (im Browser unter Bibliothek)"
                        r.has("error") -> "Fehler: ${r.optString("error")}"
                        r.has("missing") -> r.optString("missing")
                        up > 0 -> "$up Datei(en) hochgeladen"
                        else -> "RomM hat sie schon"
                    }
                    tick++
                }
            }
        }

        profileTiles(profiles) { p -> if (p == null) profileAdd = true else profileOpen = p }

        val locs = saves?.optJSONArray("locations").objs().filter { (it.optJSONArray("snapshots")?.length() ?: 0) > 0 }
        header("Spielstand zurücksetzen", "Einzelnes Spiel: Spiel öffnen → Spielstände → Backups. Hier ganze Ordner.")
        if (saves == null) item { ConsoleTile("Lade …", "", Icons.Filled.History) {} }
        locs.forEach { l ->
            item {
                val snaps = l.optJSONArray("snapshots").objs()
                ConsoleTile(friendly(l), "${snaps.size} Stände · ${ago(snaps.first().optLong("time"))}", Icons.Filled.Save) { pick = l }
            }
        }
        item {
            ConsoleTile("Jetzt sichern", "alle Spielstände", Icons.Filled.History) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { Saves.backup(ctx, null) } }; note = "gesichert"; tick += 8 }
            }
        }
        val transfers = entries.filter { it.optString("kind") in listOf("download", "rom_up") }
        val running = live.filter { !it.optBoolean("done") }
        header("Downloads", when {
            running.isNotEmpty() -> "läuft gerade · darunter die letzten Spiele mit Dauer"
            transfers.isEmpty() -> "noch keine Spiele geladen"
            else -> "die letzten ${minOf(transfers.size, 20)} Spiele · wie lange es gedauert hat"
        })
        // what is moving right now: the same card as the bubble top right (cover, title, speed + size, bar)
        running.forEach { a -> item(span = { GridItemSpan(maxLineSpan) }) { ActivityCard(a) } }
        transfers.take(20).forEach { e ->
            item(span = { GridItemSpan(maxLineSpan) }) { TransferRow(e) }
        }

    }

    if (serverOpen) RommServerDialog(onDone = { note = it; serverOpen = false; tick++ }) { serverOpen = false; tick++ }

    ProfileDialogs(profileOpen, profileAdd, onDone = { note = it; tick++ }) { profileOpen = null; profileAdd = false }

    pick?.let { l ->
        val snaps = l.optJSONArray("snapshots").objs().take(5)
        ConsoleMenu(friendly(l), "Welcher Stand? Der jetzige wird vorher gesichert, du kannst also zurück.",
            options = snaps.map { s ->
                ("Stand " + java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(s.optLong("time")))) to {
                    pick = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Saves.restore(ctx, File(s.optString("path"))) }.getOrElse { JSONObject().put("error", it.message) } }
                        note = r.optString("error").ifEmpty { "${r.optInt("restored")} Dateien zurückgesetzt" }
                        tick += 8
                    }
                    Unit
                }
            }) { pick = null }
    }
}

/** a game's path on the Thor by console and name (entries from before the history kept the path) */
private fun localPath(ctx: android.content.Context, sys: String, name: String): String? {
    val s = wtf.lennard.thorbro.Roms.scan(ctx, false).optJSONArray("systems").objs().firstOrNull { it.optString("id") == sys } ?: return null
    val key = name.substringBefore(" (").lowercase()
    val roms = s.optJSONArray("roms").objs()
    return (roms.firstOrNull { it.optString("name") == name } ?: roms.firstOrNull { it.optString("file").lowercase().startsWith(key) })?.optString("path")
}

/** one downloaded (or uploaded) game: cover, name, size, how long it took */
@Composable
private fun TransferRow(e: JSONObject) {
    val ctx = LocalContext.current
    val down = e.optString("kind") == "download"
    val romId = e.optInt("romId")
    val file = e.optString("file"); val sys = e.optString("system")
    val cover by androidx.compose.runtime.produceState<File?>(null, romId, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    romId > 0 -> wtf.lennard.thorbro.Library.coverFile(ctx, romId)
                    file.isNotEmpty() && sys.isNotEmpty() -> wtf.lennard.thorbro.Media.coverForFile(ctx, sys, File(file))
                    sys.isNotEmpty() -> localPath(ctx, sys, e.optString("name"))?.let { wtf.lennard.thorbro.Media.coverForFile(ctx, sys, File(it)) }
                    else -> null
                }
            }.getOrNull()
        }
    }
    FocusCard(Modifier.fillMaxWidth(), onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                if (cover != null) Img(cover, 160, Modifier.fillMaxSize(), ContentScale.Crop, e.optString("name"))
                else Icon(if (down) Icons.Filled.Download else Icons.Filled.Upload, null, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.optString("name").substringBefore(" (").ifEmpty { "–" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (down) "Aus RomM geladen" else "Zu RomM hochgeladen") +
                    (e.optString("system").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
                    (e.optLong("size").takeIf { it > 0 }?.let { " · ${fmtBytes(it)}" } ?: ""), fontSize = 13.sp, color = Muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                val ms = e.optLong("ms")
                if (ms > 0) Text("in ${wtf.lennard.thorbro.Jobs.dur((ms + 999) / 1000)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(ago(e.optLong("t")), fontSize = 13.sp, color = Muted)
            }
        }
    }
}

