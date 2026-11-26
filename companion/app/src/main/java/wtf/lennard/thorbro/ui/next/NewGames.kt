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
import androidx.compose.material.icons.filled.Cloud
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
            progress: Float? = null, corner: @Composable (androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null, onClick: () -> Unit) {
    val fx = rememberFx()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).fx(fx, RoundedCornerShape(16.dp), lift = 1.06f, onClick = onClick).background(Panel),
            contentAlignment = Alignment.Center) {
            when {
                image != null && fit -> Img(image, 300, Modifier.fillMaxSize(0.6f))
                image != null -> Img(image, 400, Modifier.fillMaxSize(), ContentScale.Crop, title)
                else -> Text(title, Modifier.padding(12.dp), textAlign = TextAlign.Center, fontSize = 14.sp, color = Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
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

/** grid of square tiles; focus stays centered, room around the edge for the lifted focus tile */
@Composable
fun ArtGrid(min: Dp = ART, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    val (st, fl) = snapGrid()
    CenteredFocus {
        LazyVerticalGrid(GridCells.Adaptive(min), state = st, flingBehavior = fl, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
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
    if (list.isEmpty()) { Line("Keine Spiele gefunden. Lege sie unter ROMs/<Konsole> ab.", Muted); return }
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
    ArtGrid {
        items(list, key = { it.id }) { s ->
            val isFirst = if (last != null && list.any { it.id == last }) s.id == last else s == list.first()
            FolderTile(s, if (isFirst) Modifier.focusRequester(first) else Modifier) { NewGamesNav.lastFolder = s.id; NewGamesNav.folder.value = s.id }
        }
    }
}

/** a console folder: a tab on top, the first covers as a dimmed collage, the console logo in front, the count */
@Composable
private fun FolderTile(s: Sys, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val fx = rememberFx()
    val icon by produceState<File?>(null, s.id) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, s.id) }.getOrNull() } }
    val covers by produceState<List<File>>(emptyList(), s.id) {
        value = withContext(Dispatchers.IO) {
            if (s.id == "ports") emptyList()
            else s.roms.asSequence().mapNotNull { r -> runCatching { Cocoon.media(ctx, s.id, r.getString("path"), "icon") }.getOrNull() }.take(4).toList()
        }
    }
    val count = remember(s) { entries(s).size }
    Column(modifier) {
        // folder tab
        Box(Modifier.padding(start = 14.dp).width(56.dp).height(9.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(if (fx.active) Color(0xF2FFFFFF) else Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).fx(fx, RoundedCornerShape(16.dp), lift = 1.06f, onClick = onOpen).background(GlassFill),
            contentAlignment = Alignment.Center) {
            if (covers.isNotEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    covers.chunked(2).take(2).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            row.forEach { f -> Img(f, 200, Modifier.weight(1f).fillMaxSize(), ContentScale.Crop) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3050608), Color(0xE6050608)))))
            }
            if (icon != null) Img(icon, 240, Modifier.fillMaxSize(0.5f))
            else Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(12.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text(fmtBytes(s.roms.sumOf { it.optLong("size") }), fontSize = 12.sp, color = Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Img(icon, 96, Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)) }
            Text(cur.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("   ${items.size} Spiele · ${fmtBytes(cur.roms.sumOf { it.optLong("size") })}", fontSize = 14.sp, color = Muted, maxLines = 1)
        }
        ArtGrid {
            items(items, key = { it.getString("path") }) { r ->
                val m = meta?.optJSONObject(r.getString("path")) ?: JSONObject()
                val l = lib?.optJSONObject("games")?.optJSONObject(r.getString("path"))
                val sub = listOf(m.optString("year"), r.optString("extra")).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { fmtBytes(r.optLong("size")) }
                ArtTile(gameTitle(r, m), sub, cover(cur.id, r, m), if (r == items.first()) Modifier.focusRequester(first) else Modifier,
                    fit = cur.id == "ports", corner = if (l == null) null else { {
                        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (l.optBoolean("inRomm")) Badge(Icons.Filled.Cloud, true)
                            if (l.optString("save") == "pending" || l.optString("save") == "local") Badge(Icons.Filled.Save, false)
                        }
                    } }) { open = r }
            }
        }
    }
    open?.let { r ->
        val group = if (cur.id == "switch" && r.optString("baseId").isNotEmpty()) cur.roms.filter { it.optString("baseId") == r.optString("baseId") } else listOf(r)
        GameDetail(cur.id, r, meta?.optJSONObject(r.getString("path")) ?: JSONObject(),
            lib = lib?.optJSONObject("games")?.optJSONObject(r.getString("path")), group = group, onChanged = changed) { open = null }
    }
}

