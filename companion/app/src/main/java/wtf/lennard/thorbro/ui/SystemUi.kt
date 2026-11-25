package wtf.lennard.thorbro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.lennard.thorbro.AppActivity
import wtf.lennard.thorbro.Device
import wtf.lennard.thorbro.MainActivity
import wtf.lennard.thorbro.Prefs
import wtf.lennard.thorbro.ThorService
import wtf.lennard.thorbro.Updater

/** System › Allgemein: connection, updates, start & display, maintenance, passing on, permissions */
@Composable
fun SystemScreen() {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    WhileVisible { while (true) { delay(3000); tick++ } }
    val p = remember(tick) { Device.permissions(ctx) }
    val grants = remember(tick) { wtf.lennard.thorbro.Emulators.trees(ctx).size }
    var autoUpdate by remember { mutableStateOf(Updater.auto(ctx)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var dual by remember { mutableStateOf(activity?.let { SecondScreen.enabled(it) } == true) }
    var updMsg by remember { mutableStateOf("") }
    fun open(action: String) = ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val (lan, mesh) = remember(tick) { ipv4() }
    val version = remember { Updater.current(ctx) }
    val direct = rememberDirect()
    val code = remember(tick) { Prefs.token(ctx) }
    val url = lan?.let { "http://$it:${ThorService.PORT}" }
    val problem = if (activity != null) remember(tick) { SecondScreen.problem(activity) } else "nicht möglich"

    fun checkUpdate() {
        updMsg = "Sucht …"
        scope.launch {
            val r = withContext(Dispatchers.IO) { Updater.update(ctx) }
            updMsg = when { r.has("error") -> "Quelle nicht erreichbar"; r.has("session") -> "Installiert ${r.getString("latest")} …"; else -> "Aktuell" }
        }
    }
    fun setAutoUpdate(on: Boolean) { autoUpdate = on; Updater.configure(ctx, null, on) }
    fun setAutostart(on: Boolean) { autostart = on; Prefs.setAutostart(ctx, on) }
    fun setDual(on: Boolean) { if (activity != null) { SecondScreen.setEnabled(activity, on); dual = on } }

    CockpitActions("System") {
        listOf(
            CockpitAction("Nach Updates suchen", Icons.Filled.SystemUpdate) { checkUpdate() },
            CockpitAction(if (autoUpdate) "Auto-Update aus" else "Auto-Update an", Icons.Filled.Autorenew) { setAutoUpdate(!autoUpdate) },
            CockpitAction(if (autostart) "Autostart aus" else "Autostart an", Icons.Filled.PowerSettingsNew) { setAutostart(!autostart) },
            CockpitAction(if (dual) "Zwei-Bildschirm aus" else "Zwei-Bildschirm an", Icons.Filled.Cast) { setDual(!dual) },
        )
    }

    TileGrid(first) {
        header("Verbindung", "ThorBro $version · " + if (ThorService.running) "läuft" else "Server gestoppt")
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassFill).border(1.dp, GlassLine, RoundedCornerShape(20.dp)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (url != null) { QrImage(url, "Im selben WLAN scannen", 170.dp); Spacer(Modifier.width(24.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(if (ThorService.running) "Server läuft" else "Server gestoppt", if (ThorService.running) Tone.OK else Tone.BAD)
                    Text(url ?: "kein WLAN", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    mesh?.let { Text("http://$it:${ThorService.PORT}  (NetBird)", fontSize = 14.sp, color = Muted, fontFamily = FontFamily.Monospace) }
                    Text("Code  $code", fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = Accent)
                }
            }
        }
        item { ConsoleTile("Web-Oberfläche", "alles auf dem Thor", Icons.Filled.Web, accent = true, modifier = Modifier.firstFocus(first, true)) {
            ctx.startActivity(Intent(ctx, AppActivity::class.java)) } }
        item { ConsoleTile("Neuer Code", "alte Kopplungen ungültig", Icons.Filled.Key) { Prefs.newToken(ctx); tick++ } }

        header("Updates")
        item { ConsoleTile(if (updMsg.isEmpty()) "Nach Updates suchen" else updMsg, "jetzt $version", Icons.Filled.SystemUpdate) { checkUpdate() } }
        item { ToggleTile("Auto-Update", autoUpdate, "alle 6 Stunden", Icons.Filled.Autorenew) { setAutoUpdate(it) } }

        header("Start & Anzeige")
        item { ToggleTile("Autostart", autostart, "beim Hochfahren", Icons.Filled.PowerSettingsNew) { setAutostart(it) } }
        item { ToggleTile("Zwei-Bildschirm-Modus", dual && problem == null, if (dual && problem != null) problem else "unten Touch-Tasten",
            Icons.Filled.Cast, enabled = problem == null || dual) { setDual(it) } }

        header("Wartung")
        item { ConsoleTile("Cocoon neu starten", "wenn der Launcher hängt", Icons.Filled.RestartAlt) {
            wtf.lennard.thorbro.Apps.restart(ctx, wtf.lennard.thorbro.Apps.COCOON) } }

        header("Weitergeben", "ThorBro an einen anderen Thor schicken – auch ohne gemeinsames WLAN")
        directTiles(ctx, direct, scope, share = true)

        header("Berechtigungen", if (p.getBoolean("allFiles") && p.getBoolean("overlay") && p.getBoolean("install") && grants > 0) "alles erteilt" else "fehlende antippen")
        fun perm(title: String, ok: Boolean, fix: () -> Unit) = item {
            ConsoleTile(title, if (ok) "erteilt" else "antippen zum Erlauben", if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, accent = !ok) { if (!ok) fix() }
        }
        perm("Alle Dateien", p.getBoolean("allFiles")) { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) }
        perm("Über anderen Apps", p.getBoolean("overlay")) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        perm("Apps installieren", p.getBoolean("install")) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
        perm("ROM-Ordner", grants > 0) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_FOLDER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    DirectDialog(direct)
}
