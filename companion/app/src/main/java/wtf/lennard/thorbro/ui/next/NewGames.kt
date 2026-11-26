package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import wtf.lennard.thorbro.StreamApps
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Background
import wtf.lennard.thorbro.Cocoon
import wtf.lennard.thorbro.Library
import wtf.lennard.thorbro.Media
import wtf.lennard.thorbro.Roms
import java.io.File

/** which console folder is open in the new Spiele page (null = all folders); B closes it */
object NewGamesNav { val folder = mutableStateOf<String?>(null); var lastFolder: String? = null }

/** size of a square artwork tile (Figma: 150 dp); grids stretch it a little to fill the row */
val ART = 150.dp

/**
 * One square artwork with the title below: every game, store entry and folder uses this.
 * The art always fills the square (cropped); [fit] centers it instead (app icons of ports).
 * Text never runs over: one title line, one short sub line.
 */
@Composable
fun ArtTile(title: String, sub: String = "", image: File?, modifier: Modifier = Modifier, fit: Boolean = false,
            progress: Float? = null, badge: String? = null, hint: String = "Details",
            corner: @Composable (androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null, onClick: () -> Unit) {
    val fx = rememberFx()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).hintA(hint).fx(fx, RoundedCornerShape(16.dp), lift = 1.06f, onClick = onClick).background(Panel),
            contentAlignment = Alignment.Center) {
            when {
                image != null && fit -> Img(image, 300, Modifier.fillMaxSize(0.6f))
                image != null -> Img(image, 400, Modifier.fillMaxSize(), ContentScale.Crop, title)
                // no cover: the console icon big + the title, never a bare text box
                badge != null -> Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    ConsoleIcon(badge, 64.dp)
                    Text(title, Modifier.padding(top = 6.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(title, Modifier.padding(12.dp), textAlign = TextAlign.Center, fontSize = 14.sp, color = Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null && image != null) ConsoleBadge(badge)
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(Accent))
            }
            corner?.invoke(this)
        }
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (fx.active) Ink else Muted, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text(sub.ifEmpty { " " }, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
    }
}

/**
 * grid of square tiles; focus stays centered, room around the edge for the lifted focus tile.
 * [rows] > 0: tiles sized so exactly that many rows (with title + sub line) fit on screen.
 */
@Composable
fun ArtGrid(min: Dp = ART, rows: Int = 0, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    val (st, fl) = snapGrid()
    HeaderFollows(st)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // measured once per page: the head hiding must not resize the tiles
        val h = remember { maxHeight }
        val side = if (rows > 0) ((h - 28.dp - 14.dp * (rows - 1) - 44.dp * rows) / rows).coerceIn(96.dp, 220.dp) else min
        CenteredFocus {
            LazyVerticalGrid(GridCells.Adaptive(side), state = st, flingBehavior = fl, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

/** Spiele › Bibliothek: consoles as folders → inside, the games as square artworks */
@Composable
fun GamesScreenNew() {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val seen = remember { intArrayOf(Background.changes) }
    WhileVisible { while (true) { if (Background.changes != seen[0]) { seen[0] = Background.changes; Roms.invalidate(); reload++ }; delay(1000) } }
    val systems by produceState<List<Sys>?>(null, reload) {
        value = withContext(Dispatchers.IO) {
            Roms.scan(ctx, false).getJSONArray("systems").objs().filter { it.getString("id") != "_unassigned" }.map { s ->
                Sys(s.getString("id"), s.getString("name"), s.getJSONArray("roms").objs())
            }.filter { it.roms.isNotEmpty() }
        }
    }
    val list = systems
    if (list == null) { Line("Suche Spiele …", Muted); return }
    if (NewGamesNav.folder.value == STREAM_FOLDER) { StreamFolder(); return }
    val cur = list.firstOrNull { it.id == NewGamesNav.folder.value }
    if (cur == null) { Folders(list); return }
    FolderContent(cur, reload) { Roms.invalidate(); reload++ }
}

@Composable
private fun Folders(list: List<Sys>) {
    val first = remember { FocusRequester() }
    // back from a folder: focus the folder we came from
    val last = remember { NewGamesNav.lastFolder }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }
    ArtGrid(rows = 2) {
        items(list, key = { it.id }) { s ->
            val isFirst = if (last != null && list.any { it.id == last }) s.id == last else s == list.first()
            FolderTile(s, if (isFirst) Modifier.focusRequester(first) else Modifier) { NewGamesNav.lastFolder = s.id; NewGamesNav.folder.value = s.id }
        }
        item(key = STREAM_FOLDER) {
            ArtTile("Streaming", "vom Mac", null, if (list.isEmpty()) Modifier.focusRequester(first) else Modifier, hint = "Öffnen",
                corner = { Icon(Icons.Filled.Cast, null, Modifier.align(Alignment.Center).size(64.dp), tint = Accent) }) {
                NewGamesNav.lastFolder = STREAM_FOLDER; NewGamesNav.folder.value = STREAM_FOLDER
            }
        }
    }
}

/** a console folder: a tab on top, the first covers as a dimmed collage, the console logo in front, the count */
@Composable
private fun FolderTile(s: Sys, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val fx = rememberFx()
    // one cover per game (Switch: base + updates are one game), never the same picture twice
    val covers by produceState<List<File>>(emptyList(), s.id) {
        value = withContext(Dispatchers.IO) {
            if (s.id == "ports") emptyList()
            else entries(s).asSequence().mapNotNull { r -> runCatching { Cocoon.media(ctx, s.id, r.getString("path"), "icon") }.getOrNull() }
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }.distinctBy { it.length() }.take(4).toList()
        }
    }
    val count = remember(s) { entries(s).size }
    Column(modifier) {
        // folder tab
        Box(Modifier.padding(start = 14.dp).width(56.dp).height(9.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(if (fx.active) Color(0xF2FFFFFF) else Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).hintA("Öffnen").fx(fx, RoundedCornerShape(16.dp), lift = 1.06f, onClick = onOpen).background(GlassFill),
            contentAlignment = Alignment.Center) {
            if (covers.size >= 2) {
                // the covers as they are: a clean 2×2 collage, the console only as the small badge
                Column(Modifier.fillMaxSize()) {
                    covers.chunked(2).take(2).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            row.forEach { f -> Img(f, 200, Modifier.weight(1f).fillMaxSize(), ContentScale.Crop) }
                            if (row.size == 1) Box(Modifier.weight(1f).fillMaxSize().background(Panel), contentAlignment = Alignment.Center) { ConsoleIcon(s.id, 40.dp) }
                        }
                    }
                }
                ConsoleBadge(s.id)
            } else if (covers.size == 1) {
                Img(covers[0], 300, Modifier.fillMaxSize(), ContentScale.Crop)
                ConsoleBadge(s.id)
            } else {
                // no covers (yet): the console itself, big
                ConsoleIcon(s.id, 96.dp)
            }
        }
        Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (fx.active) Ink else Muted,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text("$count · ${fmtBytes(s.roms.sumOf { it.optLong("size") })}", fontSize = 12.sp, color = Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun FolderContent(cur: Sys, reload: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val meta by produceState<JSONObject?>(null, cur.id, reload) { value = withContext(Dispatchers.IO) { runCatching { Media.metaFor(ctx, cur.id, cur.roms) }.getOrNull() } }
    val lib by produceState<JSONObject?>(null, reload) { value = withContext(Dispatchers.IO) { runCatching { Library.status(ctx) }.getOrNull() } }
    val icon by produceState<File?>(null, cur.id) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, cur.id) }.getOrNull() } }
    val items = remember(cur) { entries(cur) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(cur.id) { delay(200); runCatching { first.requestFocus() } }
    ArtGrid(rows = 2) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "head") {
            Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Img(icon, 96, Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)) }
                Text(cur.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("   ${items.size} · ${fmtBytes(cur.roms.sumOf { it.optLong("size") })}", fontSize = 14.sp, color = Muted, maxLines = 1)
            }
        }
        // state (RomM, saves) lives on the detail page, not as icons on every cover
        items(items, key = { it.getString("path") }) { r ->
            val m = meta?.optJSONObject(r.getString("path")) ?: JSONObject()
            val sub = listOf(m.optString("year"), r.optString("extra")).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { fmtBytes(r.optLong("size")) }
            val img = cover(cur.id, r, m)
            // inside the folder no console badge on covers; without a cover the console icon stands in
            ArtTile(gameTitle(r, m), sub, img, if (r == items.first()) Modifier.focusRequester(first) else Modifier,
                fit = cur.id == "ports", badge = if (img == null && cur.id != "ports") cur.id else null) { open = r }
        }
    }
    open?.let { r ->
        val group = if (cur.id == "switch" && r.optString("baseId").isNotEmpty()) cur.roms.filter { it.optString("baseId") == r.optString("baseId") } else listOf(r)
        GameDetail(cur.id, r, meta?.optJSONObject(r.getString("path")) ?: JSONObject(),
            lib = lib?.optJSONObject("games")?.optJSONObject(r.getString("path")), group = group, onChanged = changed) { open = null }
    }
}


private const val STREAM_FOLDER = "_stream"

/** Spiele › Streaming: the games Sunshine offers on the Mac (cover from the scraping DB), tap starts them in Moonlight */
@Composable
private fun StreamFolder() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var onlyShortcuts by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the cached list at once, then ask the Mac
    LaunchedEffect(tick) {
        data = withContext(Dispatchers.IO) { StreamApps.list(ctx) }
        busy = true
        data = withContext(Dispatchers.IO) { runCatching { StreamApps.refresh(ctx) }.getOrNull() } ?: data
        busy = false
    }
    val apps = data?.optJSONArray("apps")?.objs().orEmpty()
    val shown = if (onlyShortcuts) apps.filter { it.optBoolean("shortcut") } else apps
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }
    ArtGrid(rows = 2) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "head") {
            Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cast, null, Modifier.size(30.dp), tint = Accent); Spacer(Modifier.width(12.dp))
                Text("Streaming", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val host = data?.optJSONObject("host")?.optString("name").orEmpty()
                Text("   ${apps.size}" + (if (host.isNotEmpty()) " · $host" else "") + (if (busy) " · suche …" else ""), fontSize = 14.sp, color = Muted, maxLines = 1)
            }
        }
        item(key = "shortcuts") {
            ArtTile(if (onlyShortcuts) "Alle Spiele" else "Shortcuts", "${apps.count { it.optBoolean("shortcut") }} Favoriten", null, Modifier.focusRequester(first), hint = "Wechseln",
                corner = { Icon(Icons.Filled.Star, null, Modifier.align(Alignment.Center).size(56.dp), tint = Accent) }) { onlyShortcuts = !onlyShortcuts }
        }
        items(shown, key = { it.getString("name") }) { a ->
            val name = a.getString("name")
            val img by produceState<File?>(null, name, data?.optLong("updated")) { value = withContext(Dispatchers.IO) { runCatching { StreamApps.icon(ctx, name) }.getOrNull() } }
            ArtTile(name, if (a.optBoolean("shortcut")) "★ Shortcut" else "", img, hint = "Spielen") { open = a }
        }
    }
    if (apps.isEmpty() && !busy) Box(Modifier.fillMaxSize().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Text(if (data?.optBoolean("login") == true) "Keine Spiele von Sunshine. Läuft der Mac?" else
            "Noch keine Spiele. In RomBro „Meine Spiele hinzufügen“ drücken oder im Web-Panel unter Spiele › Streaming das Sunshine-Login eintragen.",
            color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
    }
    open?.let { a ->
        val name = a.getString("name")
        ConsoleMenu(name, msg, options = listOf(
            "Spielen" to {
                msg = ""; open = null
                scope.launch { withContext(Dispatchers.IO) { runCatching { StreamApps.play(ctx, name) } }.exceptionOrNull()?.let { msg = it.message ?: "Fehler" } }
            },
            (if (a.optBoolean("shortcut")) "Aus Shortcuts entfernen" else "Zu Shortcuts") to {
                StreamApps.setShortcut(ctx, name, !a.optBoolean("shortcut")); open = null; tick++
            },
        )) { open = null }
    }
}
