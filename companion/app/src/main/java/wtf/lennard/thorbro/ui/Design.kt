package wtf.lennard.thorbro.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * ThorBro design system (see DESIGN.md in the repo root): the five areas, the top bar,
 * status chips, switch tiles, the activity pill and the cockpit (second screen) registry.
 * New pages are built from these pieces and from Common.kt / Console.kt, nothing else.
 */

// ---------- areas & navigation ----------

/** The five areas of ThorBro. L1/R1 step through them; every page belongs to exactly one. */
enum class Area(val label: String, val icon: ImageVector, val pages: List<Screen?>) {
    START("Start", Icons.Filled.Home, listOf(null)),
    GAMES("Spiele", Icons.Filled.ViewModule, listOf(Screen.GAMES, Screen.STORE)),
    SYNC("Sync", Icons.Filled.Sync, listOf(Screen.SYNC)),
    TOGETHER("Zusammen", Icons.Filled.Groups, listOf(Screen.NETPLAY, Screen.STREAM)),
    SYSTEM("System", Icons.Filled.Settings, listOf(Screen.SETTINGS, Screen.CONTROLLER, Screen.DRIVERS));

    companion object { fun of(s: Screen?): Area = entries.first { s in it.pages } }
}

/** where ThorBro is; shared by the main screen and the cockpit on the second screen */
object Nav {
    val screen = mutableStateOf<Screen?>(null)
    private val last = HashMap<Area, Screen?>()

    fun go(s: Screen?) { last[Area.of(s)] = s; screen.value = s }
    /** opens an area on the page that was open there last time */
    fun area(a: Area) = go(if (last.containsKey(a)) last[a] else a.pages.first())
    fun step(d: Int) { val e = Area.entries; area(e[(Area.of(screen.value).ordinal + d + e.size) % e.size]) }
}

/** what a sub page is called in the page row of its area */
val Screen.short: String get() = when (this) {
    Screen.GAMES -> "Bibliothek"; Screen.STORE -> "Store"; Screen.SYNC -> "Sync"
    Screen.NETPLAY -> "Multiplayer"; Screen.STREAM -> "Streaming"
    Screen.SETTINGS -> "Allgemein"; Screen.CONTROLLER -> "Controller"; Screen.DRIVERS -> "Treiber"
}

/** a button glyph like the ones in the hint bar: white pill with black text */
@Composable
fun KeyCapSmall(text: String) {
    Box(Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xD9FFFFFF)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** tap only (touch/mouse), never a d-pad focus stop: the bars are reached with L1/R1, not by walking into them */
internal fun Modifier.tap(onTap: () -> Unit) = this.pointerInput(onTap) { detectTapGestures { onTap() } }

/**
 * Top of every page: L1 · the five areas · R1, then the activity pill, time, Wi-Fi, battery.
 * [extra] goes right before the time (e.g. a friends button).
 */
@Composable
fun TopBar(area: Area, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(20_000); tick++ } }
    val time = remember(tick) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()) }
    val battery = remember(tick) { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    val wifi = remember(tick) { onWifi(ctx) }
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyCapSmall("L1")
        Spacer(Modifier.width(10.dp))
        Area.entries.forEach { a ->
            val on = a == area
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else Color.Transparent)
                .tap { Nav.area(a) }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(a.icon, null, Modifier.size(18.dp), tint = if (on) OnAccent else Muted)
                Spacer(Modifier.width(6.dp))
                Text(a.label, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) OnAccent else Muted)
            }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(6.dp))
        KeyCapSmall("R1")
        Spacer(Modifier.weight(1f))
        ActivityPill()
        extra()
        Spacer(Modifier.width(14.dp))
        Text(time, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        Icon(if (wifi) Icons.Filled.Wifi else Icons.Filled.WifiOff, null, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.BatteryFull, null, Modifier.size(18.dp))
        Text(" $battery %", fontSize = 14.sp)
    }
}

fun onWifi(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

/**
 * The pages of an area (Bibliothek · Store …) as a row of chips under the top bar.
 * These ARE focus stops (d-pad up from the content), because they are the only way between sub pages.
 */
@Composable
fun PageRow(area: Area, current: Screen?) {
    if (area.pages.size < 2) return
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        area.pages.filterNotNull().forEach { p ->
            val fx = rememberFx()
            val on = p == current
            Box(Modifier.fx(fx, RoundedCornerShape(50), lift = 1.06f, restLine = !on) { Nav.go(p) }
                .background(if (on) Color(0x33FFFFFF) else Color.Transparent).padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(p.short, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color.White else Muted)
            }
        }
    }
}

// ---------- status ----------

enum class Tone(val color: Color) { OK(Ok), WARN(Color(0xFFFFC66B)), BAD(Bad), NEUTRAL(Color(0x99FFFFFF)) }

/** state in one glance: coloured dot + a few words. Status is shown, never hidden in a subtitle. */
@Composable
fun StatusChip(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x40000000)).border(1.dp, GlassLine, RoundedCornerShape(50))
        .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** a drawn switch, for tiles that turn something on or off */
@Composable
fun SwitchMark(on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) Ok else Color(0x40FFFFFF)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * A setting that is on or off. Looks different from an action tile on purpose: a switch top right
 * and "an"/"aus" in the status line. Never use a plain ConsoleTile with "… an"/"… aus" in the title.
 */
@Composable
fun ToggleTile(title: String, on: Boolean, sub: String = "", icon: ImageVector? = null, enabled: Boolean = true,
               modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    ConsoleTile(title, listOf(if (on) "an" else "aus", sub).filter { it.isNotEmpty() }.joinToString(" · "), icon, modifier = modifier,
        badges = { SwitchMark(on, Modifier.align(Alignment.TopEnd).padding(10.dp)) }) { if (enabled) onToggle(!on) }
}

// ---------- activity (background jobs) ----------

/** background jobs (offload, downloads, RomM uploads): one poller, read by the pill, drawer and cockpit */
object ActivityUi {
    var items by mutableStateOf<List<JSONObject>>(emptyList())
    /** the drawer with all cards (Select or a tap on the pill) */
    var open by mutableStateOf(false)
    val running get() = items.filter { !it.optBoolean("done") }
    val failed get() = items.filter { it.optBoolean("done") && it.optString("error").let { e -> e.isNotEmpty() && e != "null" } }
}

/** "⟳ 2 · 45 %" in the top bar; red when something failed. Select opens the drawer. */
@Composable
fun ActivityPill() {
    WhileVisible { while (true) { ActivityUi.items = wtf.lennard.thorbro.Background.list().objs(); delay(500) } }
    val run = ActivityUi.running
    val bad = ActivityUi.failed
    if (run.isEmpty() && bad.isEmpty()) return
    val p = if (run.isEmpty()) 1f else run.map { it.optDouble(if (it.has("gameProgress")) "gameProgress" else "progress", 0.0).toFloat() }.average().toFloat()
    val text = when {
        run.isNotEmpty() -> "${run.size} ${if (run.size == 1) "Aufgabe" else "Aufgaben"} · ${(p * 100).toInt()} %"
        else -> "${bad.size} fehlgeschlagen"
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (run.isEmpty()) Color(0x55FF8A80) else Color(0x33FFFFFF))
        .tap { ActivityUi.open = !ActivityUi.open }.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (run.isNotEmpty()) androidx.compose.material3.CircularProgressIndicator(progress = { p }, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
            color = Color.White, trackColor = Color(0x33FFFFFF))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        KeyCapSmall("Select")
    }
}

// ---------- cockpit: actions for the second screen ----------

/** one button on the second screen */
class CockpitAction(val label: String, val icon: ImageVector? = null, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * What the second screen shows for the page in front: a title and big touch buttons.
 * Pages register with [CockpitActions]; the innermost (last registered, e.g. a dialog) wins.
 */
object Cockpit {
    class Entry { var title by mutableStateOf(""); var actions by mutableStateOf<List<CockpitAction>>(emptyList()) }
    /** true while the second screen is shown (pages skip the work otherwise) */
    var on by mutableStateOf(false)
    val stack = mutableStateListOf<Entry>()
    val top: Entry? get() = stack.lastOrNull()
}

/** registers this page's actions for the second screen while it is on screen. Cheap when the mode is off. */
@Composable
fun CockpitActions(title: String, actions: () -> List<CockpitAction>) {
    if (!Cockpit.on) return
    val entry = remember { Cockpit.Entry() }
    SideEffect { entry.title = title; entry.actions = actions() }
    DisposableEffect(entry) { Cockpit.stack.add(entry); onDispose { Cockpit.stack.remove(entry) } }
}

/** request focus once the screen has settled (lists need a frame before they can take focus) */
@Composable
fun FocusOnStart(f: FocusRequester, vararg keys: Any?) =
    androidx.compose.runtime.LaunchedEffect(*keys) { delay(200); runCatching { f.requestFocus() } }
