package wtf.lennard.thorbro.ui

import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.AppActivity
import wtf.lennard.thorbro.Device
import wtf.lennard.thorbro.MainActivity
import wtf.lennard.thorbro.Prefs
import wtf.lennard.thorbro.Romm
import wtf.lennard.thorbro.RommSync
import wtf.lennard.thorbro.SaveWatcher
import wtf.lennard.thorbro.Saves
import wtf.lennard.thorbro.ThorService
import wtf.lennard.thorbro.Updater
import java.net.Inet4Address
import java.net.NetworkInterface

/** Native ThorBro on the device: gamepad first, only what matters. */
class ThorActivity : ComponentActivity() {
    private val screen = androidx.compose.runtime.mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ThorService.running) ThorService.start(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        wtf.lennard.thorbro.CrashLog.install(this)
        wtf.lennard.thorbro.FrameStats.attach(this)
        rememberDisplay()
        immersive(window)
        // console icons from RomM are SVG
        coil3.SingletonImageLoader.setSafe { c -> coil3.ImageLoader.Builder(c).components { add(coil3.svg.SvgDecoder.Factory()) }.build() }
        setContent { ThorTheme { Box(Modifier.fillMaxSize()) {
            Root(screen.value) { screen.value = it }
            BackgroundOverlay(Modifier.align(Alignment.TopEnd))
        } } }
    }

    // full screen like a console: bars come back only with a swipe from the edge
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(window) }

    // L1 / R1 switch sections; every key and stick move also feeds the controller tester
    private fun rememberDisplay() { runCatching { wtf.lennard.thorbro.Emulators.display = display?.displayId ?: 0 } }

    // moving between the two screens no longer recreates the activity (configChanges) – just note where we are
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); rememberDisplay() }
    override fun onResume() { super.onResume(); rememberDisplay() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // the live tester and player assignment only matter on the controller page
        if (screen.value == Screen.CONTROLLER) Pads.onKey(event)
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (openDialogs <= 0) {
                // on the start screen B does nothing (a console does not quit with B); only the system back gesture leaves
                if (screen.value == null) return event.keyCode == KeyEvent.KEYCODE_BUTTON_B
                when {
                    screen.value == Screen.GAMES && GamesNav.system.value != null -> GamesNav.system.value = null
                    screen.value == Screen.CONTROLLER && Pads.lrMode -> Pads.lrMode = false
                    screen.value == Screen.CONTROLLER && ControllerNav.panel.value != null -> ControllerNav.panel.value = null
                    else -> screen.value = null
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> if (screen.value == null) { screen.value = Screen.GAMES; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (screen.value == Screen.CONTROLLER && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) Pads.onMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Pads.refresh(this)
    }

    companion object {
        /** number of open dialogs; a dialog handles B itself */
        @Volatile var openDialogs = 0
    }
}

@Composable
private fun Root(screen: Screen?, open: (Screen?) -> Unit) {
    val page = when {
        screen == null -> "Start"
        screen == Screen.GAMES && GamesNav.system.value != null -> "Spiele: " + GamesNav.system.value
        else -> screen.label
    }
    if (wtf.lennard.thorbro.FrameStats.page != page) { wtf.lennard.thorbro.Logs.i("ui", "Seite: $page"); wtf.lennard.thorbro.FrameStats.page = page }
    var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<HomeGame?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (screen == null) {
        HomeScreen(open = { open(it) }, showDetail = { detail = it })
    } else {
        Column(Modifier.fillMaxSize().glassBackground()) {
            StatusBar(screen.label)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) {
                when (screen) {
                    Screen.GAMES -> GamesScreen()
                    Screen.SYNC -> SyncScreen()
                    Screen.CONTROLLER -> ControllerScreen()
                    Screen.SETTINGS -> SettingsScreen()
                    Screen.STORE -> StoreScreen()
                    Screen.STREAM -> StreamScreen()
                    Screen.DRIVERS -> DriversScreen()
                }
            }
            ButtonHints("A" to "Auswählen", "B" to "Zurück")
        }
    }
    detail?.let { g ->
        GameDetail(g.system, g.rom, g.meta) { detail = null }
    }
}

fun ipv4(): Pair<String?, String?> {
    val addrs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    val mesh = addrs.firstOrNull { it.startsWith("100.") }
    val lan = addrs.firstOrNull { !it.startsWith("100.") }
    return lan to mesh
}

object SaveWatcherNote { var last = "" }

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(3000); tick++ } }
    val p = remember(tick) { Device.permissions(ctx) }
    val grants = remember(tick) { wtf.lennard.thorbro.Emulators.trees(ctx).size }
    var autoUpdate by remember { mutableStateOf(Updater.auto(ctx)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var updMsg by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    fun open(action: String) = ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val (lan, mesh) = remember(tick) { ipv4() }
    val version = remember { Updater.current(ctx) }

    TileGrid(first) {
        header("ThorBro $version", (if (ThorService.running) "läuft" else "Server gestoppt") + (lan?.let { " · http://$it:${ThorService.PORT}" } ?: "") + " · Code ${Prefs.token(ctx)}")
        item { ConsoleTile("Im Browser öffnen", "Adresse + Code", androidx.compose.material.icons.Icons.Filled.Language, accent = true,
            modifier = Modifier.firstFocus(first, true)) { menu = true } }
        item { ConsoleTile(if (updMsg.isEmpty()) "Nach Updates suchen" else updMsg, "jetzt $version", androidx.compose.material.icons.Icons.Filled.SystemUpdate) {
            updMsg = "Sucht …"
            scope.launch {
                val r = withContext(Dispatchers.IO) { Updater.update(ctx) }
                updMsg = when { r.has("error") -> "Quelle nicht erreichbar"; r.has("session") -> "Installiert ${r.getString("latest")} …"; else -> "Aktuell" }
            }
        } }
        item { ConsoleTile(if (autoUpdate) "Auto-Update an" else "Auto-Update aus", "alle 6 Stunden", androidx.compose.material.icons.Icons.Filled.Autorenew, accent = autoUpdate) {
            autoUpdate = !autoUpdate; Updater.configure(ctx, null, autoUpdate) } }
        item { ConsoleTile(if (autostart) "Autostart an" else "Autostart aus", "beim Hochfahren", androidx.compose.material.icons.Icons.Filled.PowerSettingsNew, accent = autostart) {
            autostart = !autostart; Prefs.setAutostart(ctx, autostart) } }
        item { ConsoleTile("Cocoon neu starten", "wenn der Launcher hängt", androidx.compose.material.icons.Icons.Filled.RestartAlt) {
            wtf.lennard.thorbro.Apps.restart(ctx, wtf.lennard.thorbro.Apps.COCOON) } }
        item { ConsoleTile("Neuer Code", "alte Kopplungen ungültig", androidx.compose.material.icons.Icons.Filled.Key) { Prefs.newToken(ctx); tick++ } }
        item { ConsoleTile("Web-Oberfläche", "alles auf dem Thor", androidx.compose.material.icons.Icons.Filled.Web) { ctx.startActivity(Intent(ctx, AppActivity::class.java)) } }

        header("Berechtigungen", if (p.getBoolean("allFiles") && p.getBoolean("overlay") && p.getBoolean("install") && grants > 0) "alles erteilt" else "fehlende antippen")
        fun perm(title: String, ok: Boolean, fix: () -> Unit) = item {
            ConsoleTile(title, if (ok) "erteilt" else "antippen zum Erlauben",
                if (ok) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Filled.ErrorOutline, accent = !ok) { if (!ok) fix() }
        }
        perm("Alle Dateien", p.getBoolean("allFiles")) { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) }
        perm("Über anderen Apps", p.getBoolean("overlay")) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        perm("Apps installieren", p.getBoolean("install")) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
        perm("ROM-Ordner", grants > 0) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_FOLDER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    if (menu) ConsoleMenu("Im Browser öffnen",
        listOfNotNull(lan?.let { "http://$it:${ThorService.PORT}" }, mesh?.let { "http://$it:${ThorService.PORT}  (NetBird)" }).joinToString("\n") + "\nCode: ${Prefs.token(ctx)}",
        mono = true, options = emptyList()) { menu = false }
}
