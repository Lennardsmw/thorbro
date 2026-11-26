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
    val title get() = displayTitle(meta.optString("title").ifEmpty { rom.getString("name").substringBefore(" (").substringBefore(" [") })
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
        if (Theme.p.mascot) Mascot(currentMood(), 40.dp)
        else Box(Modifier.size(34.dp).clip(CircleShape).background(GlassFill).border(1.dp, GlassLine, CircleShape), contentAlignment = Alignment.Center) {
            Text("T", fontWeight = FontWeight.Bold, color = Ink)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = if (HeadFont != null) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = HeadFont)
        Spacer(Modifier.weight(1f))
        Text(time, fontSize = 17.sp)
        Spacer(Modifier.width(16.dp))
        Icon(if (wifi) Icons.Filled.Wifi else Icons.Filled.WifiOff, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Filled.BatteryFull, null, Modifier.size(20.dp))
        Text(" $battery %", fontSize = 15.sp)
        Spacer(Modifier.width(18.dp))
        FriendsButton()
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
    Box(Modifier.fillMaxWidth().background(Theme.p.hintBar).padding(horizontal = 32.dp, vertical = 10.dp)) {
        BottomStatus.text?.let { t -> // e.g. "Verbinde mit Thor-8b8e …" while a job runs, then its result for a moment
            Row(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f), verticalAlignment = Alignment.CenterVertically) {
                val p = BottomStatus.progress
                if (p != null) androidx.compose.material3.CircularProgressIndicator(progress = { p }, modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp,
                    color = Ink, trackColor = Soft)
                else Box(Modifier.size(10.dp).clip(CircleShape).background(if (BottomStatus.error) Color(0xFFE5484D) else Color(0xFF3FB950)))
                Text("  $t", fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (BottomStatus.error) Color(0xFFFF8A8D) else Ink)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            hints.forEach { (btn, what) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                        Text(btn, color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("  $what", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(open: (Screen) -> Unit, showDetail: (HomeGame) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Favorites.paths.size) { HomeData.load(ctx) }
    Favorites.load(ctx)
    val favs = HomeData.games.filter { it.rom.getString("path") in Favorites.paths }
    val first = remember { FocusRequester() }
    var focusedTitle by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val start = rememberPlay(onError = { message = it })
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().glassBackground()) {
        StatusBar("ThorBro")
        if (Theme.p.mascot) MoodLine() else Spacer(Modifier.height(18.dp))
        Text(focusedTitle, Modifier.padding(start = 40.dp).height(30.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        run {
            LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                item { MoreTile(Modifier.focusRequester(first), onFocus = { focusedTitle = "Alle Spiele" }) { open(Screen.GAMES) } }
                if (favs.isEmpty()) item {
                    Box(Modifier.size(420.dp, 210.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Noch keine Favoriten.\nIn „Alle Spiele“ ein Spiel öffnen und „☆ Zu Favoriten“ wählen.", color = Muted, fontSize = 16.sp)
                    }
                }
                items(favs, key = { it.rom.getString("path") }) { g ->
                    GameTile(g, Modifier,
                        onFocus = { focusedTitle = g.title },
                        onStart = { start(g.system, File(g.rom.getString("path"))) },
                        onDetail = { showDetail(g) })
                }
            }
        }
        if (message.isNotEmpty()) Text(message, Modifier.padding(start = 40.dp), color = Bad)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.Center) {
            Screen.entries.forEach { s -> RoundButton(s.label, s.icon) { open(s) }; Spacer(Modifier.width(22.dp)) }
        }
        ButtonHints("A" to "Starten", "X" to "Details", "R1" to "Alle Spiele")
    }
}

/** how the robot feels: busy while a job runs, tired on low battery, stuffed when storage is nearly full */
@Composable
fun currentMood(): Mood {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(30_000); tick++ } }
    val (battery, charging) = remember(tick) {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }
    val freeShare = remember(tick / 10) {
        runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).let { it.availableBytes.toDouble() / it.totalBytes } }.getOrDefault(1.0)
    }
    return when {
        BottomStatus.text != null && BottomStatus.progress != null -> Mood.BUSY
        BottomStatus.text != null && BottomStatus.error -> Mood.ASK
        BottomStatus.text != null -> Mood.DONE
        battery in 0..15 && !charging -> Mood.TIRED
        freeShare < 0.05 -> Mood.STUFFED
        else -> Mood.BASE
    }
}

/** doodle start screen: the robot and one line on how the Thor is doing */
@Composable
private fun MoodLine() {
    val ctx = LocalContext.current
    val mood = currentMood()
    val battery = remember(mood) { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    Row(Modifier.padding(start = 40.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Mascot(mood, 76.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(mood.line, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = HeadFont)
            Text(BottomStatus.text ?: "Akku $battery % · bereit zum Spielen", fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

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
    val shape = tileShape(18)
    Box(
        modifier.size(210.dp)
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
    val shape = tileShape(18)
    Box(modifier.size(210.dp).fx(fx, shape, lift = 1.08f, onClick = onClick).background(GlassFill), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ViewModule, null, Modifier.size(54.dp))
            Text("Alle Spiele", fontSize = 16.sp)
        }
    }
}

@Composable
private fun RoundButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val fx = rememberFx()
    val focused = fx.active
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).then(if (focused) Modifier.hardShadow(CircleShape, Theme.p.shadow, 3.dp) else Modifier).fx(fx, CircleShape, lift = 1.12f, frame = false, onClick = onClick)
            .then(if (focused) Modifier.background(Accent) else Modifier.background(GlassFill)),
            contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(30.dp), tint = if (focused) OnAccent else Ink) }
        Spacer(Modifier.height(6.dp))
        Text(if (focused) label else "", fontSize = 13.sp, color = Muted)
    }
}
