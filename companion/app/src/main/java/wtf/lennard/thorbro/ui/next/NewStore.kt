package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WatchLater
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Library
import wtf.lennard.thorbro.Storage
import java.io.File

/** the search of the new Store; B clears it */
object StoreNav { val query = mutableStateOf("") }

/** the transfer queue as seen by the store: romId → entry (state, progress, transfer); polled while the store is open */
object StoreQueue {
    var byRom by mutableStateOf<Map<Int, JSONObject>>(emptyMap())
    var count by mutableIntStateOf(0)
    suspend fun poll(ctx: android.content.Context) {
        val l = withContext(Dispatchers.IO) { wtf.lennard.thorbro.TransferQueue.list(ctx).objs() }
        byRom = l.filter { it.optString("kind") == "download" }.associateBy { it.optInt("romId") }
        count = l.size
    }
}

/** what a store game is doing: null = nothing; else (short text, progress or null) */
private fun storeState(id: Int): Pair<String, Float?>? {
    val q = StoreQueue.byRom[id]
    val job = Library.downloads[id]
    return when {
        q?.optString("state") == "running" -> "${(q.optDouble("progress", 0.0) * 100).toInt()} %" + (q.optString("transfer").substringAfter("noch ", "").takeIf { it.isNotEmpty() && q.has("transfer") }?.let { " · noch $it" } ?: "") to q.optDouble("progress", 0.0).toFloat()
        q?.optString("state") == "waiting" -> "wartet" to 0f
        q?.optString("state") == "later" -> "für später" to null
        q?.optString("state") == "error" -> "Fehler" to null
        q?.optString("state") == "cancelled" -> "abgebrochen" to null
        job?.done == true && job.error == null -> "auf dem Thor ✓" to null
        else -> null
    }
}

private class StoreGame(val g: JSONObject, val sys: String, val sysName: String) {
    val id get() = g.optInt("id")
    val name: String get() = displayTitle(g.optString("name"))
    val size get() = g.optLong("size")
}

@Composable
private fun storeCover(s: StoreGame): File? {
    val ctx = LocalContext.current
    val f by produceState<File?>(null, s.id) {
        value = if (s.g.optBoolean("hasCover")) withContext(Dispatchers.IO) { runCatching { Library.coverFile(ctx, s.id) }.getOrNull() } else null
    }
    return f
}

/** Spiele › Store: everything in RomM that is not on the Thor. Banner, one shelf per console, search, a page per game. */
@Composable
fun StoreScreenNew() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf<StoreGame?>(null) }
    var searching by remember { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        data = withContext(Dispatchers.IO) { runCatching { Library.store(ctx, refresh) }.getOrElse { JSONObject().put("error", it.message) } }
        refresh = false
    }
    WhileVisible { while (true) { StoreQueue.poll(ctx); frame++; delay(700) } }
    val free = remember(frame / 20, tick) { runCatching { Storage.free(Library.romsRoot(ctx)) }.getOrDefault(-1L) }

    val d = data
    when {
        d == null -> { Line("Lade RomM-Bibliothek …", Muted); return }
        d.has("error") -> { Line("RomM nicht erreichbar: ${d.optString("error")}", Bad); return }
        !d.optBoolean("connected") -> { Line("RomM ist nicht verbunden (Sync → RomM-Server).", Muted); return }
    }
    val shelves = remember(d) {
        d.optJSONArray("systems").objs().map { s -> s.optString("name") to s.optJSONArray("games").objs().map { StoreGame(it, s.optString("system"), s.optString("name")) } }
    }
    val all = remember(shelves) { shelves.flatMap { it.second } }
    // the banner: a game with cover, a different one each day
    val featured = remember(all) {
        val withCover = all.filter { it.g.optBoolean("hasCover") }.ifEmpty { all }
        withCover.getOrNull(((System.currentTimeMillis() / 86_400_000L) % maxOf(1, withCover.size)).toInt())
    }
    val q = StoreNav.query.value
    val first = remember { FocusRequester() }
    LaunchedEffect(q) { delay(250); runCatching { first.requestFocus() } }
    frame.let { } // read so progress redraws

    /** one game: cover (or the console icon + title), size or progress; [badge] = the console badge on the cover (search) */
    @Composable
    fun tile(s: StoreGame, mod: Modifier, badge: Boolean) {
        // reads StoreQueue (state) → this tile redraws by itself while its download runs
        val st = storeState(s.id)
        val img = storeCover(s)
        ArtTile(s.name, st?.first ?: fmtBytes(s.size), img, mod, progress = st?.second,
            badge = if (badge || img == null) s.sys else null, hint = if (st == null) "Laden" else "Details") { pick = s }
    }

    // search, reload and the free space: part of the list, so they scroll away with it
    @Composable
    fun head() = Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(if (q.isEmpty()) "Suchen" else "„$q“", Icons.Filled.Search, modifier = Modifier.width(240.dp).focusRequester(first).hintA("Suchen")) { searching = true }
        BigButton("Neu laden", Icons.Filled.Refresh) { refresh = true; tick++ }
        BigButton(if (StoreQueue.count > 0) "Downloads · ${StoreQueue.count}" else "Downloads", Icons.Filled.CloudDownload,
            modifier = Modifier.hintA("Öffnen")) { Nav.go(Screen.TRANSFERS) }
        Spacer(Modifier.weight(1f))
        if (free >= 0) Text("${fmtBytes(free)} frei", fontSize = 15.sp, color = Muted)
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    if (q.isNotEmpty()) {
        val hits = all.filter { it.name.contains(q, ignoreCase = true) }
        ArtGrid(rows = 2, head = 76.dp) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "head") {
                Column {
                    head()
                    if (hits.isEmpty()) Text("Nichts gefunden für „$q“", color = Muted, modifier = Modifier.padding(16.dp))
                }
            }
            items(hits, key = { it.id }) { tile(it, Modifier, badge = true) }
        }
    } else {
        HeaderFollows(list)
        CenteredFocus {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "head") { head() }
                if (all.isEmpty()) item(key = "empty") { Text("Alles aus RomM ist schon auf dem Thor.", color = Muted, modifier = Modifier.padding(16.dp)) }
                featured?.let { f -> item(key = "banner") { Banner(f, free) { pick = f } } }
                // one row per console, with its name; tiles a bit smaller so a whole row fits under the head
                shelves.filter { it.second.isNotEmpty() }.forEach { (name, games) ->
                    item(key = "shelf-$name") {
                        Column {
                            Row(Modifier.padding(start = 14.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                ConsoleIcon(games.first().sys, 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont)
                                Text("  ${games.size}", fontSize = 14.sp, color = Muted)
                            }
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(games, key = { it.id }) { tile(it, Modifier.width(128.dp), badge = false) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (searching) SearchDialog(q) { StoreNav.query.value = it.trim(); searching = false }
    pick?.let { s -> StoreDetail(s, free, frame) { pick = null } }
}

/** the big card on top of the store */
@Composable
private fun Banner(s: StoreGame, free: Long, onOpen: () -> Unit) {
    val fx = rememberFx()
    val img = storeCover(s)
    Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(160.dp).hintA("Ansehen").fx(fx, RoundedCornerShape(22.dp), lift = 1.02f, onClick = onOpen).background(Panel)) {
        if (img != null) Img(img, 900, Modifier.fillMaxSize().blur(30.dp), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6050608), 0.7f to Color(0x99050608), 1f to Color(0x4D050608))))
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Bg)) {
                if (img != null) Img(img, 500, Modifier.fillMaxSize(), ContentScale.Crop)
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("Heute im Store · ${s.sysName}", fontSize = 14.sp, color = Muted, maxLines = 1)
                Text(s.name, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(fmtBytes(s.size) + " · " + if (free in 0 until s.size) "passt nicht" else "passt auf den Thor", fontSize = 15.sp,
                    color = if (free in 0 until s.size) Bad else Ok)
            }
        }
    }
}

/** a page per game: cover, size, fits or not, one button with progress right under it */
@Composable
private fun StoreDetail(s: StoreGame, free: Long, @Suppress("UNUSED_PARAMETER") frame: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val img = storeCover(s)
    val job = Library.downloads[s.id]
    val fits = !(free in 0 until s.size)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (img != null) Img(img, 1280, Modifier.fillMaxSize().blur(40.dp), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2050608), 0.6f to Color(0xCC050608), 1f to Color(0x80050608))))
            Box(Modifier.align(Alignment.BottomCenter)) { ButtonHints("A" to (Hints.a ?: "Auswählen"), "B" to "Zurück") }
            Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 60.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Box(Modifier.fillMaxHeight(0.8f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Panel), contentAlignment = Alignment.Center) {
                    if (img != null) Img(img, 900, Modifier.fillMaxSize(), ContentScale.Crop)
                    else ConsoleIcon(s.sys, 140.dp)
                }
                Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(s.sysName, fontSize = 16.sp, color = Muted, maxLines = 1)
                    Text(s.name, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp))
                    Text(fmtBytes(s.size), fontSize = 18.sp)
                    Text(if (fits) "Passt auf den Thor" + (if (free >= 0) " · ${fmtBytes(free)} frei" else "")
                        else "Passt nicht · es fehlen ${fmtBytes(s.size - free)}", fontSize = 16.sp, color = if (fits) Ok else Bad,
                        modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(28.dp))
                    val q = StoreQueue.byRom[s.id]
                    val state = q?.optString("state")
                    val running = state == "running"
                    val waiting = state == "waiting"
                    val done = q == null && job?.done == true && job.error == null
                    val p = q?.optDouble("progress", 0.0)?.toFloat() ?: 0f
                    fun add(later: Boolean) = scope.launch {
                        withContext(Dispatchers.IO) { wtf.lennard.thorbro.TransferQueue.addDownload(ctx, s.id, s.name, s.size, s.sys, later) }
                        StoreQueue.poll(ctx)
                    }
                    BigButton(when {
                        running -> "Lädt … ${(p * 100).toInt()} %"
                        waiting -> "In der Warteschlange"
                        done -> "Auf dem Thor"
                        else -> "Laden · ${fmtBytes(s.size)}"
                    }, if (done) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload, primary = true, enabled = fits && !running && !waiting && !done,
                        modifier = Modifier.width(320.dp).focusRequester(first).hintA("Laden")) { add(false) }
                    if (running) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.width(320.dp).padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Accent, trackColor = Soft)
                        q?.optString("transfer")?.takeIf { q.has("transfer") && it.isNotEmpty() }?.let {
                            Text(it, color = Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    if (state == "error") Text("Letzter Versuch: ${q.optString("error")}", color = Bad, fontSize = 14.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    if (done) Text("Zu finden unter Spiele › ${s.sysName}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(12.dp))
                    if (running || waiting || state == "later") {
                        BigButton("Abbrechen", Icons.Filled.Close, modifier = Modifier.width(320.dp).hintA("Abbrechen")) {
                            scope.launch { withContext(Dispatchers.IO) { wtf.lennard.thorbro.TransferQueue.cancel(ctx, q!!.optString("id")) }; StoreQueue.poll(ctx) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (q == null && !done) {
                        BigButton("Für später merken", Icons.Filled.WatchLater, modifier = Modifier.width(320.dp).hintA("Merken")) { add(true) }
                        Spacer(Modifier.height(12.dp))
                    } else if (state == "later" || state == "error" || state == "cancelled") {
                        BigButton("Jetzt laden", Icons.Filled.CloudDownload, modifier = Modifier.width(320.dp)) {
                            scope.launch { withContext(Dispatchers.IO) { wtf.lennard.thorbro.TransferQueue.start(ctx, q.optString("id")) }; StoreQueue.poll(ctx) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    BigButton("Zurück", Icons.Filled.ArrowBack, modifier = Modifier.width(320.dp)) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(initial: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val f = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { f.requestFocus() } }
    DialogOpen()
    Dialog({ onDone(text) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(22.dp)).background(Sheet).padding(24.dp)) {
            Text("Im Store suchen", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(f), singleLine = true,
                placeholder = { Text("Spielname", color = Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onDone(text) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = GlassLine, cursorColor = Accent))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Suchen", Icons.Filled.Search, primary = true, modifier = Modifier.weight(1f)) { onDone(text) }
                BigButton("Leeren", modifier = Modifier.weight(1f)) { onDone("") }
            }
        }
    }
}
