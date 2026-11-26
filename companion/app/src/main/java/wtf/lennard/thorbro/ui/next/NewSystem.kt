package wtf.lennard.thorbro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.lennard.thorbro.AppActivity
import wtf.lennard.thorbro.DataSaver
import wtf.lennard.thorbro.Device
import wtf.lennard.thorbro.Library
import wtf.lennard.thorbro.MainActivity
import wtf.lennard.thorbro.Prefs
import wtf.lennard.thorbro.ThorService
import wtf.lennard.thorbro.Updater

/**
 * System › Allgemein: the connection on the left (QR, address, code), settings as grouped rows with real switches
 * on the right. Same settings as the old Einstellungen page, incl. Daten sparen and the ROM folder.
 */
@Composable
fun SystemScreenNew() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(3000); tick++ } }
    val p = remember(tick) { Device.permissions(ctx) }
    val grants = remember(tick) { wtf.lennard.thorbro.Emulators.trees(ctx).size }
    var autoUpdate by remember { mutableStateOf(Updater.auto(ctx)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var updMsg by remember { mutableStateOf("") }
    var dataTick by remember { mutableIntStateOf(0) }
    var modeMenu by remember { mutableStateOf(false) }
    var romMenu by remember { mutableStateOf(false) }
    var romRoot by remember { mutableStateOf("") }
    LaunchedEffect(tick / 10) { romRoot = withContext(Dispatchers.IO) { Library.romsRoot(ctx).path } }
    fun open(action: String) = ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val (lan, mesh) = remember(tick) { ipv4() }
    val version = remember { Updater.current(ctx) }
    val direct = rememberDirect()
    val code = remember(tick) { Prefs.token(ctx) }
    val url = lan?.let { "http://$it:${ThorService.PORT}" }
    // friends: name and party mode live here (the gear in the friends menu jumps to this group)
    var palTick by remember { mutableIntStateOf(0) }
    val myName = remember(palTick) { wtf.lennard.thorbro.Pals.myName(ctx) }
    val myId = remember { runCatching { wtf.lennard.thorbro.Pals.myId(ctx).take(6) }.getOrDefault("") }
    val partyOpen = remember(palTick) { wtf.lennard.thorbro.Pals.partyOpen(ctx) }
    var rename by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val friendsFirst = remember { FocusRequester() }

    val mode = dataTick.let { DataSaver.mode(ctx) }
    val keep = remember(dataTick) { DataSaver.keep(ctx) }
    val giftAuto = remember(dataTick) { DataSaver.giftAuto(ctx) }
    val mb = remember(dataTick) { (DataSaver.giftMaxMobile(ctx) shr 20).toInt() }
    val missing = listOf(
        Triple("Alle Dateien", p.getBoolean("allFiles")) { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) },
        Triple("Über anderen Apps", p.getBoolean("overlay")) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
        Triple("Apps installieren", p.getBoolean("install")) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) },
        Triple("ROM-Ordner freigeben", grants > 0) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_FOLDER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    ).filter { !it.second }
    LaunchedEffect(Unit) {
        delay(250)
        if (SystemNav.focus.value == "friends") {
            SystemNav.focus.value = null
            runCatching { list.animateScrollToItem(if (missing.isNotEmpty()) 2 else 1); delay(120); friendsFirst.requestFocus() }
        } else runCatching { first.requestFocus() }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // connection: shown, not a list of tiles
        Column(Modifier.width(290.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(GlassFill).border(1.dp, GlassLine, RoundedCornerShape(22.dp))
            .padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ThorBro $version", fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (url != null) QrImage(url, "Im selben WLAN scannen", 150.dp)
            Text(url?.removePrefix("http://") ?: "kein WLAN", fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
            mesh?.let { Text("$it  (NetBird)", fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1) }
            Text("Code  $code", fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Accent)
            Spacer(Modifier.weight(1f))
            BigButton("Web-Oberfläche", Icons.Filled.Web, modifier = Modifier.fillMaxWidth()) { ctx.startActivity(Intent(ctx, AppActivity::class.java)) }
            BigButton("Neuer Code", Icons.Filled.Key, modifier = Modifier.fillMaxWidth()) { Prefs.newToken(ctx); tick++ }
        }
        CenteredFocus {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (missing.isNotEmpty()) item {
                    SettingsGroup("Fehlt noch") {
                        missing.forEach { (t, _, fix) -> SettingRow(t, "antippen zum Erlauben", Icons.Filled.ErrorOutline, warn = true) { fix() } }
                    }
                }
                item {
                    SettingsGroup("Allgemein") {
                        SettingRow("Neues Design", "aus = altes ThorBro zum Vergleichen", Icons.Filled.Palette, on = UiMode.newDesign,
                            modifier = Modifier.focusRequester(first)) { UiMode.set(ctx, !UiMode.newDesign) }
                        Theme.all.forEach { t ->
                            SettingRow("Design: ${t.label}", if (t.mascot) "warm, handgezeichnet – gilt auch fürs Web-Panel" else "schwarz, Milchglas – gilt auch fürs Web-Panel",
                                Icons.Filled.Palette, on = Theme.p.id == t.id) { Theme.set(ctx, t.id) }
                        }
                        SettingRow("Autostart", "beim Hochfahren", Icons.Filled.PowerSettingsNew, on = autostart) { autostart = !autostart; Prefs.setAutostart(ctx, autostart) }
                        SettingRow("Auto-Update", "alle 6 Stunden", Icons.Filled.Autorenew, on = autoUpdate) { autoUpdate = !autoUpdate; Updater.configure(ctx, null, autoUpdate) }
                        SettingRow("Nach Updates suchen", icon = Icons.Filled.SystemUpdate, value = updMsg.ifEmpty { version }) {
                            updMsg = "Sucht …"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Updater.update(ctx) }
                                updMsg = when { r.has("error") -> "Quelle nicht erreichbar"; r.has("session") -> "Installiert ${r.getString("latest")} …"; else -> "Aktuell" }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup("Freunde") {
                        SettingRow("Dein Name", "so sehen dich deine Freunde · ID $myId", Icons.Filled.Badge, value = myName,
                            modifier = Modifier.focusRequester(friendsFirst)) { rename = true }
                        SettingRow("Party", if (partyOpen) "Freunde können ohne Einladung beitreten" else "nur wer eingeladen ist", Icons.Filled.Groups,
                            on = partyOpen) { scope.launch { withContext(Dispatchers.IO) { wtf.lennard.thorbro.Pals.setPartyOpen(ctx, !partyOpen) }; palTick++ } }
                        SettingRow("Spielstände von Freunden annehmen", if (giftAuto) "automatisch als Backup, der eigene bleibt aktiv" else "erst fragen",
                            Icons.Filled.CardGiftcard, on = giftAuto) { DataSaver.setGiftAuto(ctx, !giftAuto); dataTick++ }
                    }
                }
                item {
                    SettingsGroup("Daten sparen · wann ThorBro übers Netz lädt (RomM, Freunde)") {
                        SettingRow("Netz nutzen", mode.sub, Icons.Filled.SignalCellularAlt, value = mode.label) { modeMenu = true }
                        SettingRow("Backups pro Spiel", "so viele Stände behalten", Icons.Filled.History, value = "$keep") {
                            val c = DataSaver.KEEP_CHOICES; DataSaver.setKeep(ctx, c[(c.indexOf(keep) + 1) % c.size]); dataTick++
                        }
                        SettingRow("Spiele von Freunden mobil", "größere warten aufs WLAN", Icons.Filled.CloudOff,
                            value = "bis " + if (mb >= 1000) "${mb / 1000} GB" else "$mb MB") {
                            val c = DataSaver.GIFT_MB_CHOICES; DataSaver.setGiftMaxMobile(ctx, c[(c.indexOf(mb) + 1) % c.size]); dataTick++
                        }
                    }
                }
                item {
                    SettingsGroup("Speicher") {
                        SettingRow("ROM-Ordner", "Downloads und empfangene Spiele", Icons.Filled.Folder, value = romRoot) { romMenu = true }
                    }
                }
                item {
                    val on = direct.st?.optBoolean("active") == true
                    SettingsGroup("Weitergeben") {
                        SettingRow("Wi-Fi Direct", if (on) direct.st!!.optString("ssid") else "eigenes Netz, kein Router nötig", Icons.Filled.WifiTethering, on = on) {
                            if (on) direct.menu = "info" else directOn(ctx, direct, scope, "info")
                        }
                        SettingRow("ThorBro weitergeben", "an einen anderen Thor", Icons.Filled.Share) { direct.menu = "share" }
                    }
                }
                item {
                    SettingsGroup("Wartung") {
                        SettingRow("Cocoon neu starten", "wenn der Launcher hängt", Icons.Filled.RestartAlt) {
                            wtf.lennard.thorbro.Apps.restart(ctx, wtf.lennard.thorbro.Apps.COCOON)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (modeMenu) ConsoleMenu("Netz nutzen", "Wann ThorBro Spielstände und Spiele übers Netz bewegt.",
        options = DataSaver.Mode.entries.map { m -> ((if (m == mode) "✓ " else "") + m.label + " · " + m.sub) to { DataSaver.setMode(ctx, m); dataTick++; modeMenu = false } }) { modeMenu = false }
    if (romMenu) {
        val choices = remember { mutableStateOf<List<Pair<String, java.io.File>>>(emptyList()) }
        LaunchedEffect(Unit) { choices.value = withContext(Dispatchers.IO) { Library.romsRootChoices(ctx) } }
        ConsoleMenu("ROM-Ordner", "Jetzt: $romRoot\nNeue Downloads und empfangene Spiele landen dort (je Konsole ein Unterordner).",
            options = choices.value.map { (label, f) ->
                (if (f.path == romRoot) "✓ " else "") + "$label · ${f.path}" to {
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { Library.setRomsRoot(ctx, f.path) }
                        romRoot = withContext(Dispatchers.IO) { Library.romsRoot(ctx).path }
                        if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                    romMenu = false
                }
            }) { romMenu = false }
    }
    if (rename) NameDialog(myName, onClose = { rename = false }) { n ->
        rename = false
        scope.launch { withContext(Dispatchers.IO) { runCatching { wtf.lennard.thorbro.Pals.setName(ctx, n) } }; palTick++ }
    }
    DirectDialog(direct)
}
