package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Azahar
import wtf.lennard.thorbro.Direct
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Netplay
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import java.io.File

private val modeNames = mapOf("retroarch" to "RetroArch", "ppsspp" to "PPSSPP", "eden" to "Eden", "azahar" to "Azahar", "share" to "Weitergeben")

/**
 * Multiplayer = the party. Friends and the party live in the friends menu (L2 / top right); this page only shows what
 * the party plays right now, how each emulator joins (always 127.0.0.1 — ThorBro leads it to the leader, over WLAN,
 * NetBird or the internet), and the tools that stay: setting emulators up, the Thor as controller, passing ThorBro on.
 */
@Composable
fun NetplayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var lobbyArt by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    val direct = rememberDirect()

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            st = runCatching { Netplay.status(ctx) }.getOrNull()
            lobbyArt = Netplay.lobby?.let { Netplay.art(ctx, it.system, it.rom) }?.takeIf { it.extension.lowercase() != "svg" }
        }
    }
    WhileVisible(tick) { delay(3000); tick++ }
    val s = st
    if (s == null) { Line("Lade …", Muted); return }

    val party = s.optJSONObject("party")
    val net = s.optJSONObject("partyNet") ?: JSONObject()
    val lobby = s.optJSONObject("lobby")
    val members = party?.optJSONArray("members").objs()
    val services = s.optJSONArray("services").objs()

    TileGrid(first) {
        header("Party", when {
            party == null -> "Freunde im Freunde-Menü (L2 oder oben rechts) einladen – dann spielt ihr zusammen, auch übers Internet"
            party.optBoolean("leaderMe") -> "Du leitest · ein Spiel, das du startest, startet bei allen"
            net.optBoolean("connected") -> "Verbunden mit ${net.optString("leader")} ✓"
            net.optBoolean("following") -> "Verbinde mit ${net.optString("leader")} …"
            else -> "Wartet auf ${members.firstOrNull { !it.optBoolean("me") }?.optString("name") ?: "den Leiter"}"
        })
        item {
            ConsoleTile(if (party == null) "Freunde & Party" else "Party (${members.size})",
                if (party == null) "öffnen (L2)" else members.joinToString(", ") { if (it.optBoolean("me")) "du" else it.optString("name") },
                Icons.Filled.Groups, accent = party != null, modifier = Modifier.firstFocus(first, true)) { FriendsUi.open = true }
        }
        if (lobby != null) {
            item {
                ConsoleTile(lobby.optString("game"), "spielt gerade · ${modeNames[lobby.optString("mode")] ?: ""}",
                    Icons.Filled.CheckCircle, image = lobbyArt, accent = true) {
                    info = lobby.optString("game") to when (lobby.optString("mode")) {
                        "azahar" -> "Alle in der Party: Azahar → Mehrspieler → Direkt verbinden → 127.0.0.1, Port ${Azahar.PORT}.\nIm 3DS-Raum: " +
                            (s.optJSONObject("azahar")?.optJSONObject("server")?.optJSONArray("members").objs().size).toString()
                        "eden" -> "Alle in der Party: Eden → Mehrspieler → Raum beitreten → 127.0.0.1, Port ${wtf.lennard.thorbro.EdenRoom.PORT}."
                        else -> "Die Party tritt bei, sobald sie im gleichen Netz ist (WLAN, Wi-Fi Direct oder NetBird)."
                    }
                }
            }
            item { ConsoleTile("Spiel für die Party beenden", "Lobby schließen", Icons.Filled.Close) { Netplay.close(ctx); tick++ } }
        }
        if (services.isNotEmpty()) item {
            ConsoleTile("So geht's", "einmal pro Emulator", Icons.Filled.Info) {
                info = "Zusammen spielen" to services.joinToString("\n\n") { "${it.optString("name")}\n${it.optString("how")}" } +
                    "\n\nIn einer Party führt 127.0.0.1 von selbst zum Raum des Leiters – im WLAN, über NetBird oder übers Internet."
            }
        }

        header("Werkzeuge", "")
        item {
            ConsoleTile("Emulatoren einrichten", "RetroArch & PPSSPP fürs Hosten", Icons.Filled.Tune) {
                scope.launch {
                    BottomStatus.working("Richte Emulatoren ein …", 0.5f)
                    val r = withContext(Dispatchers.IO) { runCatching { Netplay.prepare(ctx) }.getOrElse { JSONObject().put("Fehler", it.message) } }
                    BottomStatus.text = null
                    info = "Emulatoren" to r.keys().asSequence().joinToString("\n") { "${modeNames[it] ?: it}: ${r.optString(it)}" }.ifEmpty { "Nichts zu tun" }
                }
            }
        }
        item {
            ConsoleTile("Thor als Controller", "Bluetooth-Gamepad für anderen Thor oder TV", Icons.Filled.Gamepad) {
                ctx.startActivity(android.content.Intent(ctx, GamepadActivity::class.java))
            }
        }
        item { ConsoleTile("ThorBro weitergeben", "an einen anderen Thor", Icons.Filled.Share) { direct.menu = "share" } }
    }

    info?.let { (t, x) -> ConsoleMenu(t, x, options = emptyList()) { info = null } }
    DirectDialog(direct)
}

/** Wi-Fi Direct + passing ThorBro on; shared by Settings and Multiplayer */
class DirectUi {
    var st by mutableStateOf<JSONObject?>(null)
    var busy by mutableStateOf(false)
    var menu by mutableStateOf<String?>(null)
}

@Composable
fun rememberDirect(): DirectUi {
    val ctx = LocalContext.current
    val d = remember { DirectUi() }
    WhileVisible { while (true) { d.st = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Direct.status(ctx) }; delay(3000) } }
    return d
}

internal fun directOn(ctx: android.content.Context, d: DirectUi, scope: kotlinx.coroutines.CoroutineScope, then: String) {
    if (!wtf.lennard.thorbro.Direct.permitted(ctx)) {
        (ctx as? android.app.Activity)?.requestPermissions(arrayOf(wtf.lennard.thorbro.Direct.permissionName()), 7)
        return
    }
    if (d.busy) return
    d.busy = true
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { wtf.lennard.thorbro.Direct.start(ctx) }.getOrElse { JSONObject().put("error", it.message) } }
        d.st = r; d.busy = false
        d.menu = if (r.has("error")) "error:" + r.optString("error") else then
    }
}

fun androidx.compose.foundation.lazy.grid.LazyGridScope.directTiles(
    ctx: android.content.Context, d: DirectUi, scope: kotlinx.coroutines.CoroutineScope, share: Boolean,
) {
    val on = d.st?.optBoolean("active") == true
    item {
        ConsoleTile(when { d.busy -> "Startet …"; on -> "Wi-Fi Direct an"; else -> "Wi-Fi Direct" },
            if (on) d.st!!.optString("ssid") else "eigenes Netz, kein Router nötig", Icons.Filled.WifiTethering, accent = on) {
            if (on) d.menu = "info" else directOn(ctx, d, scope, "info")
        }
    }
    if (share) item {
        ConsoleTile("ThorBro weitergeben", "an einen anderen Thor", Icons.Filled.Share) { d.menu = "share" }
    }
}

@Composable
fun DirectDialog(d: DirectUi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = d.st
    when (val m = d.menu) {
        null -> {}
        "info" -> ConsoleMenu("Wi-Fi Direct",
            "Thor mit ThorBro in der Nähe: bekommt automatisch eine Einladung (Multiplayer öffnen).\n\nOhne ThorBro: in den WLAN-Einstellungen verbinden, dann die Adresse im Browser öffnen.\nNetz: ${s?.optString("ssid")}\nPasswort: ${s?.optString("pass")}\nThorBro: ${s?.optString("download")}",
            mono = true, qrs = listOfNotNull(
                s?.optString("ssid")?.takeIf { it.isNotEmpty() }?.let { wtf.lennard.thorbro.Qr.wifi(it, s.optString("pass")) to "1. Scannen: Netz beitreten" },
                s?.optString("download")?.takeIf { it.isNotEmpty() }?.let { it to "2. Scannen: ThorBro laden" }),
            options = listOf("Wi-Fi Direct beenden" to {
                scope.launch { d.st = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Direct.stop(ctx) } }; d.menu = null
            })) { d.menu = null }
        "share" -> {
            val lan = s?.optString("lanDownload")?.takeIf { it.isNotEmpty() }
            ConsoleMenu("ThorBro weitergeben",
                if (lan != null) "Im selben WLAN: QR-Code scannen oder im Browser öffnen:\n$lan\n\nKein gemeinsames WLAN: über Wi-Fi Direct."
                else "Kein WLAN verbunden: über Wi-Fi Direct weitergeben.",
                qrs = listOfNotNull(lan?.let { it to "Im selben WLAN scannen" }),
                options = listOf(
                    "Über Wi-Fi Direct" to { if (s?.optBoolean("active") == true) d.menu = "info" else directOn(ctx, d, scope, "info") },
                    "Quick Share / Bluetooth" to { d.menu = null; runCatching { ctx.startActivity(wtf.lennard.thorbro.Direct.shareIntent(ctx)) } },
                )) { d.menu = null }
        }
        else -> ConsoleMenu("Wi-Fi Direct", m.removePrefix("error:"), options = emptyList()) { d.menu = null }
    }
}
