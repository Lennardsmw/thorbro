package wtf.lennard.thorbro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
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

private val kinds = mapOf(
    "save_up" to Triple(Icons.Filled.CloudUpload, "Spielstand hochgeladen", true),
    "save_down" to Triple(Icons.Filled.CloudDownload, "Spielstand von RomM geholt", true),
    "rom_up" to Triple(Icons.Filled.Upload, "Spiel zu RomM hochgeladen", false),
    "offload" to Triple(Icons.Filled.Inventory2, "Ausgelagert", false),
    "download" to Triple(Icons.Filled.Download, "Aus RomM geladen", false),
    "restore" to Triple(Icons.Filled.Restore, "Spielstand zurückgesetzt", true),
    "save_install" to Triple(Icons.Filled.Download, "Spielstand installiert", true),
)

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
    LaunchedEffect(Unit) { while (true) { delay(4000); tick++ } }
    var auto by remember { mutableStateOf(RommSync.auto(ctx)) }
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(org.json.JSONArray()) }
    var saves by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf<JSONObject?>(null) }
    var profiles by remember { mutableStateOf(emptyList<JSONObject>()) }
    var profileOpen by remember { mutableStateOf<JSONObject?>(null) }
    var profileAdd by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { profiles = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Profiles.json(ctx).optJSONArray("profiles").objs() } }
    LaunchedEffect(tick) { history = withContext(Dispatchers.IO) { History.list(ctx, 40) } }
    LaunchedEffect(tick / 8) { saves = withContext(Dispatchers.IO) { runCatching { Saves.overview(ctx) }.getOrNull() } }
    val st = remember(tick) { Romm.status(ctx) }
    val connected = st.optBoolean("connected")
    val uploading = RommSync.current?.takeIf { !it.done } != null
    val entries = history.objs()
    val lastSave = entries.firstOrNull { it.optString("kind").startsWith("save") }

    TileGrid(first) {
        header("RomM", if (connected) st.optString("url").removePrefix("https://").removePrefix("http://") +
            (if (st.optBoolean("viaLan")) " · über Heimnetz (schnell)" else "") +
            (lastSave?.let { " · letzter Spielstand-Sync ${ago(it.optLong("t"))}" } ?: "") else "nicht verbunden – im Browser unter Bibliothek koppeln")
        item {
            ConsoleTile(if (busy.isNotEmpty()) busy else "Jetzt synchronisieren", note.ifEmpty { "Spielstände hoch und runter" },
                Icons.Filled.Sync, accent = true, modifier = Modifier.firstFocus(first, true)) {
                if (busy.isNotEmpty() || !connected) return@ConsoleTile
                busy = "Synchronisiert …"
                scope.launch {
                    val d = withContext(Dispatchers.IO) { runCatching { RommSync.pullSaves(ctx) }.getOrElse { JSONObject().put("error", it.message) } }
                    val r = withContext(Dispatchers.IO) { runCatching { RommSync.syncSaves(ctx, null) }.getOrElse { JSONObject().put("error", it.message) } }
                    busy = ""
                    note = if (r.has("error")) "Fehler: ${r.optString("error")}" else "${r.optInt("uploaded")} hoch · ${d.optInt("downloaded")} runter"
                    tick++
                }
            }
        }
        item {
            ConsoleTile(if (uploading) "Lädt hoch …" else "Alle Spiele hochladen", if (uploading) "Fortschritt oben rechts" else "was RomM noch fehlt",
                Icons.Filled.CloudUpload) {
                if (uploading || !connected) return@ConsoleTile
                scope.launch {
                    val systems = withContext(Dispatchers.IO) {
                        runCatching { RommSync.plan(ctx).getJSONArray("systems").objs().filter { it.getInt("missing") > 0 }.map { it.getString("system") }.toSet() }
                            .getOrDefault(emptySet())
                    }
                    if (systems.isEmpty()) note = "RomM hat schon alle Spiele" else RommSync.start(ctx, systems, true)
                    tick++
                }
            }
        }
        item {
            ConsoleTile(if (auto) "Auto-Sync an" else "Auto-Sync aus", if (auto) "lädt nach jedem Speichern hoch" else "nur per Knopf",
                if (auto) Icons.Filled.CloudDone else Icons.Filled.SyncDisabled, accent = auto) { auto = !auto; RommSync.setAuto(ctx, auto) }
        }
        if (!connected) item { ConsoleTile("Nicht verbunden", "", Icons.Filled.CloudOff) {} }

        profileTiles(profiles) { p -> if (p == null) profileAdd = true else profileOpen = p }

        val locs = saves?.optJSONArray("locations").objs().filter { (it.optJSONArray("snapshots")?.length() ?: 0) > 0 }
        header("Spielstand zurücksetzen", "Spielstand kaputt oder aus Versehen überschrieben? Älteren Stand zurückholen.")
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
        header("Verlauf", if (entries.isEmpty()) "noch nichts passiert" else "die letzten ${minOf(entries.size, 12)} Vorgänge")
        entries.take(12).forEach { e ->
            item(span = { GridItemSpan(maxLineSpan) }) { HistoryRow(e) }
        }

    }

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

@Composable
private fun HistoryRow(e: JSONObject) {
    val (icon, label, _) = kinds[e.optString("kind")] ?: Triple(Icons.Filled.History, e.optString("kind"), false)
    FocusCard(Modifier.fillMaxWidth(), onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.optString("name").substringBefore(" (").ifEmpty { "–" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label + (e.optString("system").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), fontSize = 13.sp, color = Muted, maxLines = 1)
            }
            Text(ago(e.optLong("t")), fontSize = 13.sp, color = Muted)
        }
    }
}

