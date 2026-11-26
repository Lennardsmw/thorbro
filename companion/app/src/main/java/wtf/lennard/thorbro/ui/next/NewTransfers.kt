package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.TransferQueue

/** one tick every half second while the queue is busy: the screens read it, so progress bars and times redraw */
object TransferUi {
    var tick by mutableIntStateOf(0)
    private var owned = false

    /** "noch 4 min 05 s" / null while the speed is still unknown */
    fun eta(s: Long): String? = if (s >= 0) "noch ${Jobs.dur(s)}" else null

    /** the line for the bottom bar: "↓ Zelda · 34 % · noch 4 min 05 s · +2" */
    fun line(): Pair<String, Float>? {
        val run = TransferQueue.running().ifEmpty { return null }
        val it = run.first(); val j = it.job ?: return null
        val p = j.gameProgress().toFloat().coerceIn(0f, 1f)
        val waiting = TransferQueue.waiting().size + run.size - 1
        val name = j.game.ifEmpty { it.title }
        val text = (if (it.download) "↓ " else "↑ ") + name + " · " + (if (j.gameTotal < 0) "RomM verarbeitet …" else "${(p * 100).toInt()} %") +
            (eta(j.eta())?.let { e -> " · $e" } ?: "") + (if (waiting > 0) " · +$waiting" else "")
        return text to p
    }

    /** feeds the bottom bar (its status slot) from the queue; leaves it alone when someone else set a text */
    fun feedBottom() {
        val l = line()
        if (l != null) { BottomStatus.working(l.first, l.second); owned = true }
        else if (owned) { owned = false; BottomStatus.text = null; BottomStatus.progress = null }
    }
}

/** keeps [TransferUi.tick] and the bottom bar current; compose once at the root of the new design */
@Composable
fun TransferTicker() {
    WhileVisible { while (true) { delay(500); if (TransferQueue.busy || TransferQueue.paused().isNotEmpty()) TransferUi.tick++; TransferUi.feedBottom() } }
}

/** the page for everything in the queue: what runs, what waits, what is paused; pause, resume, cancel, how many at once */
@Composable
fun TransfersDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    TransferUi.tick // redraw with the ticker
    val first = remember { FocusRequester() }
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    val list = TransferQueue.all().sortedWith(compareBy({ when (it.state) { "running" -> 0; "queued" -> 1; "paused" -> 2; "error" -> 3; else -> 4 } }, { it.added }))
    val others = wtf.lennard.thorbro.Background.list().objs().filter { it.optString("kind") == "offload" }
    LaunchedEffect(Unit) { delay(250); runCatching { first.requestFocus() }; delay(350); runCatching { first.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 64.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Downloads & Uploads", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont, modifier = Modifier.weight(1f), maxLines = 1)
                    SmallButton("Gleichzeitig: ${TransferQueue.parallel(ctx)}", modifier = Modifier.focusRequester(first).hintA("Umschalten")) {
                        TransferQueue.setParallel(ctx, if (TransferQueue.parallel(ctx) == 1) 2 else 1); rev++
                    }
                    SmallButton("Fertige entfernen", Icons.Filled.Delete) { TransferQueue.clearFinished(); rev++ }
                    SmallButton("Zurück", Icons.Filled.ArrowBack, onClick = onClose)
                }
                Text("Downloads laufen einzeln oder zu zweit, Uploads immer nacheinander. Pausierte Aufträge machen danach dort weiter, wo sie waren – auch nach einem Neustart.",
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                if (list.isEmpty() && others.isEmpty()) Text("Gerade nichts in der Warteschlange.", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(others, key = { "o" + it.optString("title") + it.optString("name") }) { ActivityCard(it) }
                    items(list, key = { it.id }) { TransferRow(it) { rev++ } }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter)) { ButtonHints("A" to (Hints.a ?: "Auswählen"), "B" to "Zurück") }
        }
    }
}

@Composable
private fun TransferRow(it: TransferQueue.Item, changed: () -> Unit) {
    TransferUi.tick
    val j = it.job
    val p = (j?.gameProgress() ?: 0.0).toFloat().coerceIn(0f, 1f)
    val name = j?.game?.takeIf { g -> g.isNotEmpty() } ?: it.title
    val state = it.state
    val sub = when (state) {
        "running" -> listOfNotNull(j?.transfer() ?: j?.message?.takeIf { m -> m.isNotEmpty() }).joinToString(" · ").ifEmpty { "Startet …" }
        "queued" -> "Wartet" + (if (it.size > 0) " · ${fmtBytes(it.size)}" else "")
        "paused" -> "Pausiert · ${(p * 100).toInt()} %"
        "error" -> it.error ?: "Fehler"
        else -> "Fertig"
    }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Panel).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (it.download) Icons.Filled.CloudDownload else Icons.Filled.CloudUpload, null, Modifier.size(28.dp), tint = if (state == "error") Bad else Ink)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (it.download) "Aus RomM laden" else "Zu RomM hochladen") + " · " + sub, fontSize = 13.sp, color = if (state == "error") Bad else Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    "running", "queued" -> SmallButton("Pause", Icons.Filled.Pause) { TransferQueue.pause(it.id); changed() }
                    "paused" -> SmallButton("Weiter", Icons.Filled.PlayArrow) { TransferQueue.resume(it.id); changed() }
                    "error" -> SmallButton("Nochmal", Icons.Filled.Refresh) { TransferQueue.resume(it.id); changed() }
                }
                if (state != "done") SmallButton("Abbrechen", Icons.Filled.Close) { TransferQueue.cancel(it.id); changed() }
            }
        }
        if (state == "running" || state == "paused") Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Soft)) {
            Box(Modifier.fillMaxWidth(p).height(6.dp).background(Accent))
        }
    }
}
