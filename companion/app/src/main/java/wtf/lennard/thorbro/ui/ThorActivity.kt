package wtf.lennard.thorbro.ui

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
        setContent { ThorTheme { Root(screen.value) { screen.value = it } } }
    }

    // L1 / R1 switch sections; every key and stick move also feeds the controller tester
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Pads.onKey(event)
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (screen.value != null && !detailOpen) { screen.value = null; return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> if (screen.value == null) { screen.value = Screen.GAMES; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) Pads.onMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Pads.refresh(this)
    }

    companion object {
        /** a dialog handles B itself */
        @Volatile var detailOpen = false
    }
}

@Composable
private fun Root(screen: Screen?, open: (Screen?) -> Unit) {
    var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<HomeGame?>(null) }
    if (screen == null) {
        HomeScreen(open = { open(it) }, showDetail = { detail = it })
    } else {
        Column(Modifier.fillMaxSize().background(Bg)) {
            StatusBar(screen.label)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) {
                when (screen) {
                    Screen.GAMES -> GamesScreen()
                    Screen.SYNC -> SyncScreen()
                    Screen.SAVES -> SavesScreen()
                    Screen.CONTROLLER -> ControllerScreen()
                    Screen.SETTINGS -> SettingsScreen()
                }
            }
            ButtonHints("A" to "Auswählen", "B" to "Zurück")
        }
    }
    detail?.let { g ->
        ThorActivity.detailOpen = true
        GameDetail(g.system, g.rom, g.meta) { detail = null; ThorActivity.detailOpen = false }
    }
}

fun ipv4(): Pair<String?, String?> {
    val addrs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    val mesh = addrs.firstOrNull { it.startsWith("100.") }
    val lan = addrs.firstOrNull { !it.startsWith("100.") }
    return lan to mesh
}

@Composable
private fun SyncScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }
    var auto by remember { mutableStateOf(RommSync.auto(ctx)) }
    var busy by remember { mutableStateOf("") }
    var saves by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tick / 6) { saves = withContext(Dispatchers.IO) { runCatching { Saves.overview(ctx) }.getOrNull() } }
    val st = remember(tick) { Romm.status(ctx) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("RomM")
                Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(st.optBoolean("connected")); Big(if (st.optBoolean("connected")) st.optString("url") else "nicht verbunden") }
                Line(SaveWatcher.last.optString("state", "–"), Muted)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Switch(auto, { auto = it; RommSync.setAuto(ctx, it) })
                    Spacer(Modifier.padding(6.dp)); Line("Spielstände automatisch hochladen, sobald ein Spiel speichert (nur WLAN)")
                }
                Acts {
                    Act("Spielstände synchronisieren", primary = true, enabled = busy.isEmpty() && st.optBoolean("connected")) {
                        busy = "synchronisiert …"
                        scope.launch {
                            val d = withContext(Dispatchers.IO) { runCatching { RommSync.pullSaves(ctx) }.getOrElse { JSONObject().put("error", it.message) } }
                            val r = withContext(Dispatchers.IO) { runCatching { RommSync.syncSaves(ctx, null) }.getOrElse { JSONObject().put("error", it.message) } }
                            busy = ""; SaveWatcherNote.last = if (r.has("error")) "Fehler: ${r.optString("error")}"
                                else "${r.optInt("uploaded")} hochgeladen, ${d.optInt("downloaded")} heruntergeladen" +
                                    (if (r.optInt("skippedNoGame") > 0) ", ${r.optInt("skippedNoGame")} warten auf ihr Spiel in RomM" else "")
                        }
                    }
                    Act("Alle Spiele zu RomM hochladen", enabled = busy.isEmpty() && st.optBoolean("connected")) {
                        busy = "prüft …"
                        scope.launch {
                            val systems = withContext(Dispatchers.IO) {
                                runCatching { RommSync.plan(ctx).getJSONArray("systems").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                                    .filter { it.getInt("missing") > 0 }.map { it.getString("system") }.toSet() }.getOrDefault(emptySet())
                            }
                            if (systems.isEmpty()) { busy = ""; SaveWatcherNote.last = "RomM hat schon alle Spiele"; return@launch }
                            val job = wtf.lennard.thorbro.Jobs.start("RomM-Abgleich") { j -> RommSync.run(ctx, systems, true, j) }
                            while (!job.done) { busy = "Spiele hochladen: ${(job.progress * 100).toInt()} % · ${job.message}"; delay(1000) }
                            busy = ""; SaveWatcherNote.last = job.error?.let { "Fehler: $it" }
                                ?: job.result?.let { "${it.optInt("romsUploaded")} Spiele, ${it.optInt("savesUploaded")} Spielstände hochgeladen" } ?: ""
                        }
                    }
                    Text(busy.ifEmpty { SaveWatcherNote.last }, color = Muted)
                }
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Sicherungen auf dem Thor")
                val locs = saves?.optJSONArray("locations")
                val n = (0 until (locs?.length() ?: 0)).map { locs!!.getJSONObject(it) }
                val newest = n.mapNotNull { it.optJSONArray("snapshots")?.optJSONObject(0)?.optLong("time") }.maxOrNull()
                Big("${n.size} Speicherorte · ${n.sumOf { it.optJSONArray("snapshots")?.length() ?: 0 }} Sicherungen")
                Line(newest?.let { "Neueste: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Noch keine Sicherung", Muted)
                Acts {
                    Act("Jetzt sichern", enabled = busy.isEmpty()) {
                        busy = "sichert …"
                        scope.launch { withContext(Dispatchers.IO) { runCatching { Saves.backup(ctx, null) } }; busy = ""; tick++ }
                    }
                }
            }
        }
    }
}

object SaveWatcherNote { var last = "" }

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val p = remember(tick) { Device.permissions(ctx) }
    val grants = remember(tick) { wtf.lennard.thorbro.Emulators.trees(ctx).size }
    var autoUpdate by remember { mutableStateOf(Updater.auto(ctx)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var updMsg by remember { mutableStateOf("") }

    fun open(action: String) = ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    val (lan, mesh) = remember(tick) { ipv4() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(ThorService.running); Big(if (ThorService.running) "ThorBro läuft" else "Server gestoppt") }
                Label("Im Browser öffnen")
                lan?.let { Line("http://$it:${ThorService.PORT}") }
                mesh?.let { Line("http://$it:${ThorService.PORT}   (NetBird, von überall)") }
                Label("Kopplungscode")
                Text(Prefs.token(ctx), fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = Accent)
            }
        }
        item { PermRow("Zugriff auf alle Dateien", "Spiele hochladen, Spielstände sichern", p.getBoolean("allFiles")) { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) } }
        item { PermRow("Über anderen Apps anzeigen", "Spiele und Apps aus der Ferne starten", p.getBoolean("overlay")) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) } }
        item { PermRow("Apps installieren", "APKs und ThorBro-Updates", p.getBoolean("install")) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) } }
        item {
            PermRow("ROM-Ordner freigegeben", "Spiele in Emulatoren starten", grants > 0) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_FOLDER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(autoUpdate, { autoUpdate = it; Updater.configure(ctx, null, it) }); Spacer(Modifier.padding(6.dp)); Line("Automatisch aktualisieren") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(autostart, { autostart = it; Prefs.setAutostart(ctx, it) }); Spacer(Modifier.padding(6.dp)); Line("Beim Hochfahren starten") }
                Acts {
                    Act("Nach Updates suchen") {
                        updMsg = "suche …"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Updater.update(ctx) }
                            updMsg = when { r.has("error") -> r.getString("error"); r.has("session") -> "installiere ${r.getString("latest")} …"; else -> "aktuell (${r.getString("current")})" }
                        }
                    }
                    Text(updMsg, color = Muted)
                }
            }
        }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                Label("Weiteres")
                Acts {
                    Act("Neuer Kopplungscode") { Prefs.newToken(ctx); tick++ }
                    Act("Volle Web-Oberfläche") { ctx.startActivity(Intent(ctx, AppActivity::class.java)) }
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, ok: Boolean, fix: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth(), onClick = if (ok) null else fix) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok)
            Column(Modifier.weight(1f)) { Big(title); Label(sub) }
            if (!ok) Act("Erlauben", primary = true, onClick = fix)
        }
    }
}

@Composable
private fun SavesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { data = withContext(Dispatchers.IO) { runCatching { Saves.overview(ctx) }.getOrNull() } }
    val locs = data?.optJSONArray("locations")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Acts {
                Act("Alles jetzt sichern", primary = true, enabled = busy.isEmpty()) {
                    busy = "sichert …"
                    scope.launch { withContext(Dispatchers.IO) { runCatching { Saves.backup(ctx, null) } }; busy = ""; tick++ }
                }
                Text(busy.ifEmpty { "Gesichert wird automatisch, sobald ein Spiel speichert." }, color = Muted)
            }
        }
        if (data == null) item { Line("Lade …", Muted) }
        locs.forEach { l ->
            item {
                FocusCard(Modifier.fillMaxWidth(), onClick = {}) {
                    Big(l.optString("name"))
                    val snaps = l.optJSONArray("snapshots")
                    val newest = snaps?.optJSONObject(0)?.optLong("time")
                    Line("${l.optString("emulator")} · ${l.optInt("files")} Dateien · ${snaps?.length() ?: 0} Sicherungen" +
                        (newest?.let { " · zuletzt " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: ""), Muted)
                }
            }
        }
    }
}
