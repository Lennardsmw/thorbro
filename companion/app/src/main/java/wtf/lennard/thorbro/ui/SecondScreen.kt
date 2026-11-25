package wtf.lennard.thorbro.ui

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import wtf.lennard.thorbro.Logs
import wtf.lennard.thorbro.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two-screen mode (design B, switchable under System): while ThorBro is in front on the top screen,
 * the other screen shows the cockpit – areas, the actions of the page in front, background jobs – as big touch buttons.
 *
 * It is an overlay window (needs "Über anderen Apps"), NOT an activity: an activity on the second screen would take the
 * key focus after the first touch, and the gamepad would suddenly steer the bottom screen. FLAG_NOT_FOCUSABLE keeps
 * every key with ThorActivity. The window goes away when ThorBro leaves the front (onStop), so games and the
 * launcher get their screen back.
 */
object SecondScreen {
    private var view: ComposeView? = null
    private var wm: WindowManager? = null

    fun enabled(ctx: android.content.Context) = Prefs.of(ctx).getBoolean("dualScreen", false)
    fun setEnabled(a: ComponentActivity, on: Boolean) { Prefs.of(a).edit().putBoolean("dualScreen", on).apply(); refresh(a) }

    private fun other(a: ComponentActivity): Display? {
        val mine = a.display?.displayId ?: Display.DEFAULT_DISPLAY
        return a.getSystemService(DisplayManager::class.java).displays.firstOrNull { it.displayId != mine }
    }

    /** why the mode cannot run right now, or null when it can */
    fun problem(a: ComponentActivity): String? = when {
        other(a) == null -> "kein zweiter Bildschirm"
        (a.display?.displayId ?: 0) != Display.DEFAULT_DISPLAY -> "ThorBro läuft auf dem unteren Bildschirm"
        !Settings.canDrawOverlays(a) -> "Recht „Über anderen Apps“ fehlt"
        else -> null
    }

    fun show(a: ComponentActivity) {
        if (view != null || !enabled(a) || problem(a) != null) return
        val d = other(a) ?: return
        runCatching {
            val wctx = a.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val m = wctx.getSystemService(WindowManager::class.java)
            val v = ComposeView(wctx).apply {
                setViewTreeLifecycleOwner(a); setViewTreeViewModelStoreOwner(a); setViewTreeSavedStateRegistryOwner(a)
                setContent { ThorTheme { CockpitScreen() } }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            )
            m.addView(v, lp)
            view = v; wm = m; Cockpit.on = true
            Logs.i("ui", "Zweiter Bildschirm an (Display ${d.displayId})")
        }.onFailure { Logs.e("ui", "Zweiter Bildschirm", it) }
    }

    fun hide() {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null; wm = null; Cockpit.on = false
    }

    fun refresh(a: ComponentActivity) { hide(); show(a) }
}

/** the second screen: status, the five areas, the actions of the page in front, background jobs */
@Composable
private fun CockpitScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(20_000); tick++ } }
    val time = remember(tick) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()) }
    val battery = remember(tick) { ctx.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    val area = Area.of(Nav.screen.value)
    val entry = Cockpit.top

    Column(Modifier.fillMaxSize().glassBackground().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry?.title?.ifEmpty { null } ?: area.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$time   $battery %", fontSize = 16.sp, color = Muted)
        }
        // areas: tap to switch, like L1/R1
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Area.entries.forEach { a ->
                val on = a == area
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).then(if (on) Modifier.background(Accent) else Modifier.background(GlassFill))
                    .tap { Nav.area(a) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(a.icon, a.label, Modifier.size(26.dp), tint = if (on) OnAccent else Color.White)
                    Text(a.label, fontSize = 13.sp, color = if (on) OnAccent else Muted, maxLines = 1)
                }
            }
        }
        // the page's actions, two per row, the primary one full width
        val actions = entry?.actions.orEmpty()
        if (actions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Hier erscheinen die Aktionen zu dem, was oben ausgewählt ist.", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        } else Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (prim, rest) = actions.partition { it.primary }
            prim.forEach { CockpitButton(it, Modifier.fillMaxWidth().height(72.dp)) }
            rest.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { CockpitButton(it, Modifier.weight(1f).height(64.dp)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        // what runs in the background, always visible here (no drawer needed)
        ActivityUi.running.take(2).forEach { ActivityCard(it) }
    }
}

@Composable
private fun CockpitButton(a: CockpitAction, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).then(if (a.primary) Modifier.background(Accent) else Modifier.background(GlassFill)).tap(a.onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val fg = if (a.primary) OnAccent else Color.White
        a.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = fg); Spacer(Modifier.width(10.dp)) }
        Text(a.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
