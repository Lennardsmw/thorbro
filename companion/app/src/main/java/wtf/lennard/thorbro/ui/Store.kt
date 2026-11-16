package wtf.lennard.thorbro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Library
import wtf.lennard.thorbro.Storage
import java.io.File

/** games that are only in RomM: pick one, it downloads right away with live progress */
@Composable
fun StoreScreen() {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf<JSONObject?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        data = withContext(Dispatchers.IO) { runCatching { Library.store(ctx, refresh) }.getOrElse { JSONObject().put("error", it.message) } }
        refresh = false
    }
    // redraw progress twice a second while something downloads
    LaunchedEffect(Unit) { while (true) { delay(500); if (Library.downloads.values.any { !it.done }) frame++ } }
    val free = remember(frame / 20, tick) { runCatching { Storage.free(Library.romsRoot(ctx)) }.getOrDefault(-1L) }

    val d = data
    when {
        d == null -> { Line("Lade RomM-Bibliothek …", Muted); return }
        d.has("error") -> { Line("RomM nicht erreichbar: ${d.optString("error")}", Bad); return }
        !d.optBoolean("connected") -> { Line("RomM ist nicht verbunden (Sync → RomM).", Muted); return }
    }
    val systems = d.optJSONArray("systems").objs()
    val total = systems.sumOf { it.optJSONArray("games")?.length() ?: 0 }
    val shelves = listOf(Shelf("Store", "$total Spiele nur in RomM" + (if (free >= 0) " · ${fmtBytes(free)} frei" else ""), listOf(JSONObject().put("_reload", true)))) +
        systems.map { s -> Shelf(s.optString("name"), "${s.optJSONArray("games")?.length() ?: 0}", s.optJSONArray("games").objs()) }
    Shelves(shelves, key = { if (it.has("_reload")) "reload" else it.optInt("id") }, first = first) { g, mod ->
        if (g.has("_reload")) { ConsoleTile("Neu laden", "", Icons.Filled.Refresh, modifier = mod) { refresh = true; tick++ }; return@Shelves }
        val id = g.optInt("id")
        val job = Library.downloads[id]
        frame.let { } // read so progress redraws
        val img by produceState<File?>(null, id) {
            value = if (g.optBoolean("hasCover")) withContext(Dispatchers.IO) { Library.coverFile(ctx, id) } else null
        }
        val sub = when {
            job == null -> fmtBytes(g.optLong("size"))
            job.error != null -> "Fehler"
            job.done -> "auf dem Thor"
            else -> "${(job.progress * 100).toInt()} %"
        }
        ConsoleTile(g.optString("name"), sub, if (job?.done == true && job.error == null) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
            image = img, progress = job?.takeIf { !it.done }?.progress?.toFloat(), modifier = mod) { pick = g }
    }

    pick?.let { g ->
        val id = g.optInt("id")
        val job = Library.downloads[id]
        val size = g.optLong("size")
        when {
            job != null && !job.done -> ConsoleMenu(g.optString("name"), "Lädt … ${(job.progress * 100).toInt()} %", options = emptyList()) { pick = null }
            job?.done == true && job.error == null -> ConsoleMenu(g.optString("name"), "Ist auf dem Thor. Zu finden unter Alle Spiele.", options = emptyList()) { pick = null }
            else -> ConsoleMenu(g.optString("name"),
                fmtBytes(size) + (if (free in 0 until size) " · nicht genug Platz (${fmtBytes(free)} frei)" else "") + (job?.error?.let { "\nLetzter Versuch: $it" } ?: ""),
                options = if (free in 0 until size) emptyList() else listOf("Herunterladen" to { Library.download(ctx, id); pick = null; frame++ })) { pick = null }
        }
    }
}
