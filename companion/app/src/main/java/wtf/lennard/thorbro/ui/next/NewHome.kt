package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import wtf.lennard.thorbro.Cocoon
import wtf.lennard.thorbro.Media
import java.io.File

private val HOME_TILE = 168.dp

/** Start: the focused favorite fills the screen as hero art, favorites in one row below. No status chips. */
@Composable
fun HomeScreenNew(showDetail: (HomeGame) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Favorites.paths.size) { HomeData.load(ctx) }
    Favorites.load(ctx)
    val favs = HomeData.games.filter { it.rom.getString("path") in Favorites.paths }
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf<HomeGame?>(null) }
    var message by remember { mutableStateOf("") }
    val start = rememberPlay(onError = { message = it })
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }

    val g = focused
    val hero by produceState<File?>(null, g?.rom?.optString("path")) {
        value = if (g == null || g.system == "ports") null
        else withContext(Dispatchers.IO) { runCatching { Cocoon.media(ctx, g.system, g.rom.getString("path"), "hero") }.getOrNull() }
    }
    val art by produceState<File?>(null, g?.rom?.optString("path")) { value = if (g == null) null else withContext(Dispatchers.IO) { homeArt(ctx, g) } }

    Box(Modifier.fillMaxSize().glassBackground()) {
        val back = hero ?: art
        if (back != null) Img(back, 1280, Modifier.fillMaxSize().then(if (hero == null) Modifier.blur(40.dp) else Modifier), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2050608), 0.55f to Color(0x99050608), 1f to Color(0x33050608))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF2050608))))
        Column(Modifier.fillMaxSize()) {
            NewTopBar(Area.START)
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(start = 40.dp, end = 40.dp, bottom = 4.dp)) {
                if (g == null) {
                    Text("Alle Spiele", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("Deine Konsolen als Ordner", fontSize = 16.sp, color = Muted)
                } else {
                    val facts = listOf(g.meta.optString("year"), g.meta.optString("developer"), g.meta.optString("genre")).filter { it.isNotEmpty() }
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontSize = 16.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.title, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (message.isNotEmpty()) Text(message, color = Bad, modifier = Modifier.padding(top = 6.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                item { AllGamesTile(Modifier.focusRequester(first), onFocus = { focused = null }) { Nav.go(Screen.GAMES) } }
                if (favs.isEmpty()) item {
                    Box(Modifier.size(380.dp, HOME_TILE).clip(RoundedCornerShape(18.dp)).background(Panel).padding(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Noch keine Favoriten.\nSpiel öffnen und „Favorit“ wählen.", color = Muted, fontSize = 16.sp)
                    }
                }
                items(favs, key = { it.rom.getString("path") }) { fg ->
                    FavTile(fg, onFocus = { focused = fg }, onStart = { start(fg.system, File(fg.rom.getString("path"))) }, onDetail = { showDetail(fg) })
                }
            }
            ButtonHints("A" to "Starten", "X" to "Details", "L1 R1" to "Bereich")
        }
    }
}

private fun homeArt(ctx: android.content.Context, g: HomeGame): File? {
    if (g.system == "ports") return wtf.lennard.thorbro.Ports.icon(ctx, g.rom.optString("package"))
    return Cocoon.media(ctx, g.system, g.rom.getString("path"), "icon")
        ?: g.meta.optString("cover").takeIf { it.isNotEmpty() }?.let { runCatching { Media.cover(ctx, g.system, it) }.getOrNull() }
}

@Composable
private fun FavTile(g: HomeGame, onFocus: () -> Unit, onStart: () -> Unit, onDetail: () -> Unit) {
    val ctx = LocalContext.current
    val fx = rememberFx()
    if (fx.active) LaunchedEffect(Unit) { onFocus() }
    val img by produceState<File?>(null, g.rom.getString("path")) { value = withContext(Dispatchers.IO) { homeArt(ctx, g) } }
    val icon by produceState<File?>(null, g.system) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, g.system) }.getOrNull() } }
    Box(
        Modifier.size(HOME_TILE)
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonX || e.key == Key.ButtonY)) { onDetail(); true } else false }
            .fx(fx, RoundedCornerShape(18.dp), lift = 1.08f, onClick = onStart)
            .background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null && g.system == "ports") Img(img, 400, Modifier.size(HOME_TILE * 0.6f))
        else if (img != null) Img(img, 400, Modifier.fillMaxSize(), ContentScale.Crop, g.title)
        else Text(g.title, Modifier.padding(14.dp), textAlign = TextAlign.Center, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (icon != null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color(0xCC000000)).padding(5.dp)) {
            Img(icon, 96, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AllGamesTile(modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val fx = rememberFx()
    if (fx.active) LaunchedEffect(Unit) { onFocus() }
    Box(modifier.size(HOME_TILE).fx(fx, RoundedCornerShape(18.dp), lift = 1.08f, onClick = onClick).background(GlassFill), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ViewModule, null, Modifier.size(54.dp))
            Spacer(Modifier.height(4.dp))
            Text("Alle Spiele", fontSize = 16.sp)
        }
    }
}
