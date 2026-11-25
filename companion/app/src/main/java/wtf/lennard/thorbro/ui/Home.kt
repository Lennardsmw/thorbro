package wtf.lennard.thorbro.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Cocoon
import wtf.lennard.thorbro.Emulators
import wtf.lennard.thorbro.Media
import wtf.lennard.thorbro.Roms
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** one game on the home row, with what is needed to show and start it */
class HomeGame(val system: String, val rom: JSONObject, val meta: JSONObject) {
    val title get() = meta.optString("title").ifEmpty { rom.getString("name").substringBefore(" (").substringBefore(" [") }
    val lastPlayed get() = meta.optString("lastplayed")
}

object HomeData {
    val games = mutableStateListOf<HomeGame>()
    @Volatile var loaded = false

    /** favorite games only: only the consoles that contain favorites are read (meta for all consoles is a lot of work) */
    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        Favorites.load(ctx)
        val favs = Favorites.paths.toSet()
        val have = games.map { it.rom.getString("path") }.toSet()
        if (favs.all { it in have } && loaded) return@withContext
        loaded = true
        val a = Roms.scan(ctx, false).getJSONArray("systems")
        val out = mutableListOf<HomeGame>()
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i)
            val id = s.getString("id")
            val roms = s.getJSONArray("roms").let { r -> (0 until r.length()).map { r.getJSONObject(it) } }.filter { it.getString("path") in favs }
            if (roms.isEmpty()) continue
            val meta = runCatching { Media.metaFor(ctx, id, roms) }.getOrDefault(JSONObject())
            roms.forEach { out += HomeGame(id, it, meta.optJSONObject(it.getString("path")) ?: JSONObject()) }
        }
        val sorted = out.sortedWith(compareByDescending<HomeGame> { it.lastPlayed }.thenBy { it.title.lowercase() })
        withContext(Dispatchers.Main) { games.clear(); games.addAll(sorted) }
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    GAMES("Alle Spiele", Icons.Filled.ViewModule),
    SYNC("Sync", Icons.Filled.Sync),
    CONTROLLER("Controller", Icons.Filled.SportsEsports),
    STORE("Store", Icons.Filled.Storefront),
    STREAM("Streaming", Icons.Filled.Cast),
    NETPLAY("Multiplayer", Icons.Filled.Groups),
    DRIVERS("Treiber", Icons.Filled.Memory),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

@Composable
fun StatusBar(title: String) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(20_000); tick++ } }
    val time = remember(tick) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()) }
    val battery = remember(tick) { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    val wifi = remember(tick) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(GlassFill).border(1.dp, GlassLine, CircleShape), contentAlignment = Alignment.Center) {
            Text("T", fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(time, fontSize = 17.sp)
        Spacer(Modifier.width(16.dp))
        Icon(if (wifi) Icons.Filled.Wifi else Icons.Filled.WifiOff, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Filled.BatteryFull, null, Modifier.size(20.dp))
        Text(" $battery %", fontSize = 15.sp)
    }
}

/** a status line in the bottom bar, next to the button hints; set by long actions (joining, connecting) */
object BottomStatus {
    var text by androidx.compose.runtime.mutableStateOf<String?>(null)
    var progress by androidx.compose.runtime.mutableStateOf<Float?>(null)
    var error by androidx.compose.runtime.mutableStateOf(false)
    private var shownAt = 0L

    fun working(t: String, p: Float) { text = t; progress = p.coerceIn(0.02f, 1f); error = false; shownAt = System.currentTimeMillis() }
    /** a result: stays for a few seconds, then the bar is clean again */
    fun done(t: String, failed: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        text = t; progress = null; error = failed
        val at = System.currentTimeMillis().also { shownAt = it }
        scope.launch { kotlinx.coroutines.delay(if (failed) 9000 else 6000); if (shownAt == at) text = null }
    }
}

@Composable
fun ButtonHints(vararg hints: Pair<String, String>) {
    Box(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 32.dp, vertical = 10.dp)) {
        BottomStatus.text?.let { t -> // e.g. "Verbinde mit Thor-8b8e …" while a job runs, then its result for a moment
            Row(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f), verticalAlignment = Alignment.CenterVertically) {
                val p = BottomStatus.progress
                if (p != null) androidx.compose.material3.CircularProgressIndicator(progress = { p }, modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp,
                    color = Color.White, trackColor = Color(0x33FFFFFF))
                else Box(Modifier.size(10.dp).clip(CircleShape).background(if (BottomStatus.error) Color(0xFFE5484D) else Color(0xFF3FB950)))
                Text("  $t", fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (BottomStatus.error) Color(0xFFFF8A8D) else Color.White)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            hints.forEach { (btn, what) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.height(24.dp).widthIn(min = 24.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(btn, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("  $what", fontSize = 14.sp)
                }
            }
        }
    }
}

/** Start: the focused favorite fills the screen as artwork, its facts and state sit on top, the favorites run along the bottom */
@Composable
fun HomeScreen(open: (Screen) -> Unit, showDetail: (HomeGame) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Favorites.paths.size) { HomeData.load(ctx) }
    Favorites.load(ctx)
    val favs = HomeData.games.filter { it.rom.getString("path") in Favorites.paths }
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf<HomeGame?>(null) }
    var message by remember { mutableStateOf("") }
    val start = rememberPlay(onError = { message = it })
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }

    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(5000); tick++ } }
    val romm = remember(tick) { wtf.lennard.thorbro.Romm.status(ctx).optBoolean("connected") }
    val autoSync = remember(tick) { wtf.lennard.thorbro.RommSync.auto(ctx) }

    val g = focused
    val hero by produceState<File?>(null, g?.rom?.optString("path")) {
        value = if (g == null) null else withContext(Dispatchers.IO) {
            if (g.system == "ports") return@withContext null
            runCatching { Cocoon.media(ctx, g.system, g.rom.getString("path"), "hero") }.getOrNull()
        }
    }
    val cover by produceState<File?>(null, g?.rom?.optString("path")) {
        value = if (g == null) null else withContext(Dispatchers.IO) {
            if (g.system == "ports") return@withContext wtf.lennard.thorbro.Ports.icon(ctx, g.rom.optString("package"))
            Cocoon.media(ctx, g.system, g.rom.getString("path"), "icon")
                ?: g.meta.optString("cover").takeIf { it.isNotEmpty() }?.let { runCatching { Media.cover(ctx, g.system, it) }.getOrNull() }
        }
    }

    CockpitActions(g?.title ?: "Start") {
        if (g == null) listOf(CockpitAction("Alle Spiele", Icons.Filled.ViewModule, primary = true) { open(Screen.GAMES) })
        else {
            val path = g.rom.getString("path")
            listOf(
                CockpitAction("Spielen", Icons.Filled.PlayArrow, primary = true) { start(g.system, File(path)) },
                CockpitAction("Details", Icons.Filled.Info) { showDetail(g) },
                CockpitAction(if (path in Favorites.paths) "Favorit entfernen" else "Favorit", if (path in Favorites.paths) Icons.Filled.Star else Icons.Filled.StarBorder) { Favorites.toggle(ctx, path) },
            )
        }
    }

    Box(Modifier.fillMaxSize().glassBackground()) {
        val back = hero ?: cover
        if (back != null) Img(back, 1280, Modifier.fillMaxSize().then(if (hero == null) Modifier.blur(40.dp) else Modifier), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2050608), 0.55f to Color(0x99050608), 1f to Color(0x33050608))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF2050608))))
        Column(Modifier.fillMaxSize()) {
            TopBar(Area.START)
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(start = 40.dp, end = 40.dp, bottom = 6.dp)) {
                if (g == null) {
                    Text("Alle Spiele", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("Deine Bibliothek, Store und Sync", fontSize = 16.sp, color = Muted)
                } else {
                    val facts = listOf(g.meta.optString("year"), g.meta.optString("developer"), g.meta.optString("genre")).filter { it.isNotEmpty() }
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontSize = 16.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.title, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(if (romm) "RomM verbunden" else "RomM nicht verbunden", if (romm) Tone.OK else Tone.NEUTRAL)
                    if (romm) StatusChip(if (autoSync) "Auto-Sync an" else "Auto-Sync aus", if (autoSync) Tone.OK else Tone.WARN)
                    StatusChip(if (wtf.lennard.thorbro.ThorService.running) "Server läuft" else "Server gestoppt", if (wtf.lennard.thorbro.ThorService.running) Tone.OK else Tone.BAD)
                }
                if (message.isNotEmpty()) Text(message, color = Bad, modifier = Modifier.padding(top = 6.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                item { MoreTile(Modifier.focusRequester(first), onFocus = { focused = null }) { open(Screen.GAMES) } }
                if (favs.isEmpty()) item {
                    Box(Modifier.size(380.dp, TILE).clip(RoundedCornerShape(18.dp)).background(Panel).padding(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Noch keine Favoriten.\nIn „Alle Spiele“ ein Spiel öffnen und „Favorit“ wählen.", color = Muted, fontSize = 16.sp)
                    }
                }
                items(favs, key = { it.rom.getString("path") }) { fg ->
                    GameTile(fg, Modifier,
                        onFocus = { focused = fg },
                        onStart = { start(fg.system, File(fg.rom.getString("path"))) },
                        onDetail = { showDetail(fg) })
                }
            }
            ButtonHints("A" to "Starten", "X" to "Details", "L1 R1" to "Bereich")
        }
    }
}

private val TILE = 168.dp

@Composable
private fun GameTile(g: HomeGame, modifier: Modifier, onFocus: () -> Unit, onStart: () -> Unit, onDetail: () -> Unit) {
    val ctx = LocalContext.current
    val fx = rememberFx()
    if (fx.active) LaunchedEffect(Unit) { onFocus() }
    val img by produceState<File?>(null, g.rom.getString("path")) {
        value = withContext(Dispatchers.IO) {
            if (g.system == "ports") return@withContext wtf.lennard.thorbro.Ports.icon(ctx, g.rom.optString("package"))
            Cocoon.media(ctx, g.system, g.rom.getString("path"), "icon")
                ?: g.meta.optString("cover").takeIf { it.isNotEmpty() }?.let { runCatching { Media.cover(ctx, g.system, it) }.getOrNull() }
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.size(TILE)
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonX || e.key == Key.ButtonY)) { onDetail(); true } else false }
            .fx(fx, shape, lift = 1.08f, onClick = onStart)
            .background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Img(img, 400, Modifier.fillMaxSize(), ContentScale.Crop, g.title)
        else Text(g.title, Modifier.padding(14.dp), textAlign = TextAlign.Center, fontSize = 16.sp)
        val icon by produceState<File?>(null, g.system) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, g.system) }.getOrNull() } }
        if (icon != null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(32.dp).clip(CircleShape).background(Color(0xCC000000)).padding(5.dp)) {
            Img(icon, 96, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MoreTile(modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val fx = rememberFx()
    if (fx.active) LaunchedEffect(Unit) { onFocus() }
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.size(TILE).fx(fx, shape, lift = 1.08f, onClick = onClick).background(GlassFill), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ViewModule, null, Modifier.size(54.dp))
            Text("Alle Spiele", fontSize = 16.sp)
        }
    }
}
