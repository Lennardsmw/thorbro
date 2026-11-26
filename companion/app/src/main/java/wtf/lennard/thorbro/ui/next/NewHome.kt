package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Cocoon
import wtf.lennard.thorbro.Media
import wtf.lennard.thorbro.Recent
import wtf.lennard.thorbro.Roms
import java.io.File

/** a recently played game: when, and the console's name */
class RecentGame(val g: HomeGame, val t: Long, val sysName: String)

/** Start: what was played last (Recent: ThorBro launches + Cocoon's lastplayed) */
object RecentData {
    val games = mutableStateListOf<RecentGame>()
    var total by mutableStateOf(0)
    var loaded by mutableStateOf(false)

    suspend fun load(ctx: android.content.Context) = withContext(Dispatchers.IO) {
        val list = runCatching { Recent.list(ctx, 5) }.getOrDefault(emptyList())
        val systems = Roms.scan(ctx, false).getJSONArray("systems").objs().filter { it.getString("id") != "_unassigned" }
        val count = systems.sumOf { it.getJSONArray("roms").length() }
        val out = list.mapNotNull { (path, sys, t) ->
            val s = systems.firstOrNull { it.getString("id") == sys } ?: return@mapNotNull null
            val rom = s.getJSONArray("roms").objs().firstOrNull { it.getString("path") == path } ?: return@mapNotNull null
            val meta = runCatching { Media.metaFor(ctx, sys, listOf(rom)).optJSONObject(path) }.getOrNull() ?: JSONObject()
            RecentGame(HomeGame(sys, rom, meta), t, s.getString("name"))
        }
        withContext(Dispatchers.Main) { games.clear(); games.addAll(out); total = count; loaded = true }
    }
}

private val SMALL = 118.dp

/**
 * Start: one clear hero "Weiterspielen" (the last game: console, when, Spielen), next to it the four games before it
 * and the way into the library. No favorites row, no "Alle Spiele" headline.
 */
@Composable
fun HomeScreenNew(showDetail: (HomeGame) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { RecentData.load(ctx) }
    val first = remember { FocusRequester() }
    var message by remember { mutableStateOf("") }
    var focusedTitle by remember { mutableStateOf<String?>(null) }
    val start = rememberPlay(onError = { message = it })
    LaunchedEffect(RecentData.loaded, RecentData.games.firstOrNull()?.g?.rom?.optString("path")) { first.focusSoon() }
    val recent = RecentData.games
    val hero = recent.firstOrNull()
    val rest = recent.drop(1).take(4)
    fun play(r: RecentGame) = start(r.g.system, File(r.g.rom.getString("path")))

    Column(Modifier.fillMaxSize().glassBackground()) {
        NewTopBar(Area.START)
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HeroCard(hero, Modifier.weight(1f).fillMaxHeight(), first, message, onPlay = { hero?.let { play(it) } },
                onDetail = { hero?.let { showDetail(it.g) } }, onLibrary = { Nav.go(Screen.GAMES) })
            Column(Modifier.width(SMALL * 2 + 12.dp).fillMaxHeight()) {
                Text(focusedTitle ?: "Zuletzt gespielt", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (focusedTitle != null) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                (0 until 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        (0 until 2).forEach { col ->
                            val r = rest.getOrNull(row * 2 + col)
                            if (r == null) Box(Modifier.size(SMALL).clip(RoundedCornerShape(16.dp)).background(Panel.copy(alpha = 0.4f)))
                            else RecentTile(r, onFocus = { on -> if (on) focusedTitle = r.g.title else if (focusedTitle == r.g.title) focusedTitle = null },
                                onPlay = { play(r) }, onDetail = { showDetail(r.g) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.weight(1f))
                LibraryTile(RecentData.total) { Nav.go(Screen.GAMES) }
            }
        }
        ButtonHints("A" to (Hints.a ?: "Spielen"), "X" to "Details", "L1 R1" to "Bereich")
    }
}

/** the big card: art of the last game, its name, console + when, Spielen and Details */
@Composable
private fun HeroCard(r: RecentGame?, modifier: Modifier, first: FocusRequester, message: String,
                     onPlay: () -> Unit, onDetail: () -> Unit, onLibrary: () -> Unit) {
    val ctx = LocalContext.current
    val path = r?.g?.rom?.optString("path")
    val hero by produceState<File?>(null, path) {
        value = if (r == null || r.g.system == "ports") null
        else withContext(Dispatchers.IO) { runCatching { Cocoon.media(ctx, r.g.system, r.g.rom.getString("path"), "hero") }.getOrNull() }
    }
    val art by produceState<File?>(null, path) { value = if (r == null) null else withContext(Dispatchers.IO) { homeArt(ctx, r.g) } }
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Panel)) {
        val back = hero ?: art
        if (back != null) Img(back, 1280, Modifier.fillMaxSize().then(if (hero == null) Modifier.blur(28.dp) else Modifier), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.15f to Color(0x33050608), 1f to Color(0xF2050608))))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            if (r == null) {
                Text("Noch nichts gespielt", fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont)
                Text("Such dir in der Bibliothek ein Spiel aus.", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                BigButton("Bibliothek", Icons.Filled.ViewModule, primary = true, modifier = Modifier.width(220.dp).focusRequester(first).hintA("Öffnen")) { onLibrary() }
            } else {
                Text("Weiterspielen", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Text(r.g.title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)) {
                    ConsoleIcon(r.g.system, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${r.sysName} · ${agoShort(r.t)}", fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Spielen", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.width(200.dp).focusRequester(first).hintA("Spielen")) { onPlay() }
                    BigButton("Details", Icons.Filled.Info, modifier = Modifier.hintA("Details")) { onDetail() }
                }
            }
            if (message.isNotEmpty()) Text(message, color = Bad, modifier = Modifier.padding(top = 8.dp), maxLines = 2)
        }
    }
}

internal fun homeArt(ctx: android.content.Context, g: HomeGame): File? {
    if (g.system == "ports") return wtf.lennard.thorbro.Ports.icon(ctx, g.rom.optString("package"))
    return Cocoon.media(ctx, g.system, g.rom.getString("path"), "icon")
        ?: g.meta.optString("cover").takeIf { it.isNotEmpty() }?.let { runCatching { Media.cover(ctx, g.system, it) }.getOrNull() }
}

@Composable
private fun RecentTile(r: RecentGame, onFocus: (Boolean) -> Unit, onPlay: () -> Unit, onDetail: () -> Unit) {
    val ctx = LocalContext.current
    val fx = rememberFx()
    LaunchedEffect(fx.active) { onFocus(fx.active) }
    val img by produceState<File?>(null, r.g.rom.getString("path")) { value = withContext(Dispatchers.IO) { homeArt(ctx, r.g) } }
    Box(
        Modifier.size(SMALL).hintA("Spielen")
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonX || e.key == Key.ButtonY)) { onDetail(); true } else false }
            .fx(fx, RoundedCornerShape(16.dp), lift = 1.06f, onClick = onPlay)
            .background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null && r.g.system == "ports") Img(img, 300, Modifier.size(SMALL * 0.6f))
        else if (img != null) Img(img, 300, Modifier.fillMaxSize(), ContentScale.Crop, r.g.title)
        else Text(r.g.title, Modifier.padding(10.dp), textAlign = TextAlign.Center, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        ConsoleBadge(r.g.system)
    }
}

@Composable
private fun LibraryTile(count: Int, onClick: () -> Unit) {
    val fx = rememberFx()
    Row(Modifier.fillMaxWidth().height(58.dp).hintA("Öffnen").fx(fx, RoundedCornerShape(16.dp), lift = 1.04f, onClick = onClick)
        .background(if (fx.active) Accent else Panel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ViewModule, null, Modifier.size(24.dp), tint = if (fx.active) OnAccent else Ink)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Bibliothek", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (fx.active) OnAccent else Ink)
            if (count > 0) Text("$count Spiele", fontSize = 12.sp, color = if (fx.active) OnAccent else Muted, maxLines = 1)
        }
    }
}

/** the console icon (assets/consoles: the doodle robots), or nothing while it loads */
@Composable
fun ConsoleIcon(sys: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val icon by produceState<File?>(null, sys) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, sys) }.getOrNull() } }
    if (icon != null) Img(icon, 160, modifier.size(size))
}

/** THE badge system: the console as a small round icon, top left on a tile (folders, store, recent games) */
@Composable
fun BoxScope.ConsoleBadge(sys: String) {
    Box(Modifier.align(Alignment.TopStart).padding(7.dp).size(30.dp).clip(CircleShape).background(Color(0xF2EFE7D2)).padding(3.dp),
        contentAlignment = Alignment.Center) { ConsoleIcon(sys, 22.dp) }
}
