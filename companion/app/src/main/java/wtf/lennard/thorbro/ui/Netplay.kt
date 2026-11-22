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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
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
import wtf.lennard.thorbro.Roms
import java.io.File

private val modeNames = mapOf("retroarch" to "RetroArch", "ppsspp" to "PPSSPP", "eden" to "Eden", "azahar" to "Azahar")

/** status line of a lobby; Azahar rooms are made by hand in Azahar, so say whether it worked */
private fun hostStateText(l: JSONObject) = when (l.optString("hostState")) {
    "hosting" -> if (l.optString("mode") == "azahar") "Raum läuft ✓" else "wartet auf Mitspieler"
    "manual" -> if (l.optString("mode") == "azahar") (if (l.optString("online").isNotEmpty()) "in Azahar dem Online-Raum beitreten" else "in Azahar Raum erstellen")
        else "im Emulator Host starten"
    "unknown" -> "Raum-Status unbekannt (Shizuku fehlt)"
    else -> "startet …"
}

/** Local multiplayer: host a game (PIN) or join another Thor, as tiles */
@Composable
fun NetplayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var games by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var peers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<JSONObject?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var joinJob by remember { mutableStateOf<Jobs.Job?>(null) }
    var joinProg by remember { mutableStateOf(0f) }
    val direct = rememberDirect()
    var invites by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var invitePopup by remember { mutableStateOf<JSONObject?>(null) }
    val seenInvites = remember { mutableSetOf<String>() }
    var azPick by remember { mutableStateOf<String?>(null) } // "local" | "online": next tapped 3DS game hosts an Azahar room

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val s = runCatching { Netplay.status(ctx) }.getOrNull()
            val ok = s?.optJSONArray("supported").objs().associateBy { it.optString("id") }
            val list = runCatching {
                Roms.scan(ctx, false).optJSONArray("systems").objs().filter { it.optString("id") in ok }
                    .flatMap { sys -> sys.optJSONArray("roms").objs().filter { !it.optBoolean("dir") }.map { sys.optString("id") to it } }
            }.getOrDefault(emptyList())
            st = s; games = list
        }
    }
    // follow the host state (RetroArch hosting switches on a few seconds after start)
    LaunchedEffect(st?.optJSONObject("lobby")?.optString("hostState")) {
        if (st?.optJSONObject("lobby")?.optString("hostState") == "starting") { delay(4000); tick++ }
    }
    // Azahar: the room is made by hand, follow it live
    val azLive = st?.optJSONObject("lobby")?.optString("mode") == "azahar" || st?.optJSONObject("joined")?.optString("mode") == "azahar"
    WhileVisible(azLive, tick) { if (azLive) { delay(3000); tick++ } }
    // Thors nearby inviting over Wi-Fi Direct: scan while this screen is open and no own lobby runs
    WhileVisible {
        while (true) {
            if (Direct.permitted(ctx) && st?.optJSONObject("lobby") == null && joinJob?.done != false) {
                val found = withContext(Dispatchers.IO) { runCatching { Direct.invites(ctx).objs() }.getOrDefault(emptyList()) }
                invites = found
                found.firstOrNull { it.optString("pin").isNotEmpty() && seenInvites.add(it.optString("device") + it.optString("pin")) }
                    ?.let { if (invitePopup == null && pinFor == null) invitePopup = it }
            }
            delay(8000)
        }
    }
    val s = st
    if (s == null) { Line("Lade …", Muted); return }

    fun runJoin(j: Jobs.Job) {
        joinJob = j
        scope.launch {
            while (!j.done) { joinProg = j.progress.toFloat(); delay(300) }
            joinProg = 1f
            info = if (j.error != null) "Beitreten ging nicht" to j.error!! else "Läuft" to (j.result?.optString("hint") ?: "")
            tick++
        }
    }
    fun accept(inv: JSONObject) = runJoin(Jobs.start("Einladung annehmen") { Netplay.accept(ctx, inv, it) })
    val az = s.optJSONObject("azahar")
    val lobby = s.optJSONObject("lobby")
    val supported = s.optJSONArray("supported").objs().associateBy { it.optString("id") }

    fun search() {
        if (searching) return
        searching = true
        scope.launch { peers = withContext(Dispatchers.IO) { runCatching { Netplay.peers(ctx).objs() }.getOrDefault(emptyList()) }; searching = false }
    }

    TileGrid(first) {
        if (lobby != null) {
            header("Deine Lobby", "${lobby.optString("game")} · ${modeNames[lobby.optString("mode")]}")
            val isAz = lobby.optString("mode") == "azahar"
            item {
                ConsoleTile("PIN ${lobby.optString("pin")}", hostStateText(lobby),
                    if (lobby.optString("hostState") == "hosting") Icons.Filled.CheckCircle else Icons.Filled.Lock, accent = true,
                    modifier = Modifier.firstFocus(first, true)) {
                    info = "PIN ${lobby.optString("pin")}" to "Dein Thor: ${lobby.optString("roomIp").ifEmpty { s.optString("ip") }}\nMitspieler: " +
                        (lobby.optJSONArray("guestNames").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }.ifEmpty { "noch keiner" }) +
                        when {
                            isAz -> "\n\n" + Azahar.hostSteps(lobby.optString("roomIp"), lobby.optString("online").ifEmpty { null }) +
                                (if (direct.st?.optBoolean("active") == true) "" else "\n\nThor in der Nähe ohne gemeinsames WLAN? Wi-Fi Direct starten – er bekommt dann eine Einladung.")
                            lobby.optString("hostState") == "manual" -> "\n\nRetroArch hat nicht von selbst gehostet: RetroArch-Menü → Netplay → Netplay-Host starten."
                            else -> ""
                        }
                }
            }
            item { ConsoleTile("Lobby schließen", "", Icons.Filled.Close) { Netplay.close(ctx); tick++ } }
        }
        // guest side of an Azahar room: did it work?
        s.optJSONObject("joined")?.takeIf { it.optString("mode") == "azahar" && lobby == null }?.let { j ->
            header("Azahar-Raum", "${j.optString("game")} · bei ${j.optString("host")}")
            item {
                val room = az?.optString("room")
                ConsoleTile(when (room) { "connected" -> "Im Raum ✓"; "unknown" -> "Status unbekannt"; else -> "Noch nicht im Raum" },
                    if (room == "connected") "im Spiel lokalen Mehrspieler wählen" else "in Azahar: Mehrspieler → Raum beitreten",
                    if (room == "connected") Icons.Filled.CheckCircle else Icons.Filled.SportsEsports, accent = room == "connected") {
                    info = "Azahar-Raum" to j.optString("hint")
                }
            }
        }
        if (invites.isNotEmpty()) {
            header("Einladungen in der Nähe", "Thors mit ThorBro und Wi-Fi Direct – ein Druck genügt")
            invites.forEach { inv ->
                item {
                    ConsoleTile(inv.optString("name"), inv.optString("game").ifEmpty { "Wi-Fi Direct offen" }.let { g ->
                        if (inv.optString("pin").isNotEmpty()) "$g · ${modeNames[inv.optString("mode")] ?: ""} · beitreten" else "$g · verbinden" },
                        Icons.Filled.BluetoothSearching, accent = inv.optString("pin").isNotEmpty()) { accept(inv) }
                }
            }
        }
        if (az != null) {
            val id = az.optJSONObject("identity")
            header("3DS · Azahar", "Raum erstellen, Mitspieler treten bei" + (id?.let { " · dein 3DS-Name: ${it.optString("user")}" } ?: ""))
            item {
                ConsoleTile("Raum lokal", az.optString("ip").ifEmpty { "kein Netz – Wi-Fi Direct starten" }.let { if (azPick == "local") "jetzt 3DS-Spiel wählen ↓" else "dieser Thor ist der Raum · $it" },
                    Icons.Filled.Home, accent = azPick == "local") { azPick = if (azPick == "local") null else "local" }
            }
            item {
                val server = az.optString("server").ifEmpty { null }
                ConsoleTile("Raum online", when { server == null -> "kein Server eingetragen"; azPick == "online" -> "jetzt 3DS-Spiel wählen ↓"; else -> server },
                    Icons.Filled.Public, accent = azPick == "online") {
                    if (server == null) info = "Online-Raum" to "Für Online-Räume braucht es einen Azahar-Raumserver im Internet (z. B. im Homelab). " +
                        "Adresse im Web-Panel eintragen: Multiplayer → Azahar → Online-Server.\n\nOhne Server geht „Raum lokal“: im selben WLAN, Hotspot oder Wi-Fi Direct."
                    else azPick = if (azPick == "online") null else "online"
                }
            }
            if (azPick != null) {
                header("3DS-Spiel wählen", if (azPick == "online") "Online-Raum auf " + az.optString("server") else "lokaler Raum auf diesem Thor")
                games.filter { it.first == "n3ds" }.forEach { (sys, r) ->
                    item {
                        ConsoleTile(r.optString("name"), "Azahar-Raum öffnen", Icons.Filled.WifiTethering, accent = true) {
                            val online = azPick == "online"
                            azPick = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { runCatching { Netplay.host(ctx, sys, File(r.optString("path")), online) }
                                    .getOrElse { JSONObject().put("error", it.message) } }
                                info = if (res.has("error")) "Geht nicht" to res.optString("error")
                                    else "Raum vorbereitet · PIN ${res.optString("pin")}" to res.optString("hint") +
                                        "\n\nDer andere Thor: Multiplayer → Thors suchen (oder Einladung annehmen), PIN ${res.optString("pin")}."
                                tick++
                            }
                        }
                    }
                }
            }
        }
        header("Ohne WLAN", "ein Thor startet Wi-Fi Direct, der andere verbindet sich damit")
        directTiles(ctx, direct, scope, share = true)
        header("Beitreten", if (s.optString("ip").isEmpty()) "kein Netz – oben Wi-Fi Direct nutzen" else "selbes WLAN, Hotspot oder Wi-Fi Direct")
        item {
            val j = joinJob
            if (j != null) ConsoleTile(if (j.done) (j.error ?: "Gestartet") else j.message.ifEmpty { "Verbinde …" }, "${(joinProg * 100).toInt()} %",
                Icons.Filled.SportsEsports, progress = joinProg, modifier = Modifier.firstFocus(first, lobby == null)) { if (j.done) joinJob = null }
            else ConsoleTile(if (searching) "Sucht …" else "Thors suchen", peers?.let { "${it.size} gefunden" } ?: "im Netzwerk",
                Icons.Filled.Search, accent = true, modifier = Modifier.firstFocus(first, lobby == null)) { search() }
        }
        peers.orEmpty().forEach { p ->
            val l = p.optJSONObject("lobby")
            val open = l?.optBoolean("open") == true
            item {
                ConsoleTile(if (open) l!!.optString("game") else p.optString("service").removePrefix("ThorBro ").ifEmpty { p.optString("address") },
                    when { !p.optBoolean("reachable") -> "nicht erreichbar"; open -> "von ${l!!.optString("host")} · beitreten"; else -> "keine Lobby offen" },
                    Icons.Filled.Groups, accent = open) { if (open) pinFor = p else search() }
            }
        }
        header("Hosten", if (supported.isEmpty()) "RetroArch, PPSSPP oder Eden installieren"
            else supported.values.joinToString(", ") { it.optString("name") + if (it.optBoolean("shaky")) " (wackelig)" else "" })
        games.take(120).forEach { (sys, r) ->
            item {
                ConsoleTile(r.optString("name"), supported[sys]?.optString("name") ?: sys, Icons.Filled.WifiTethering) {
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { Netplay.host(ctx, sys, File(r.optString("path"))) }
                            .getOrElse { JSONObject().put("error", it.message) } }
                        info = if (res.has("error")) "Geht nicht" to res.optString("error")
                            else "PIN ${res.optString("pin")}" to "Dem Mitspieler sagen. ${res.optString("hint")}"
                        tick++
                    }
                }
            }
        }
    }

    pinFor?.let { p ->
        PinPad(p.optJSONObject("lobby")?.optString("game") ?: "", onClose = { pinFor = null }) { pin ->
            pinFor = null
            val addr = p.optString("address")
            runJoin(Jobs.start("Netplay beitreten") { Netplay.join(ctx, addr, pin, it) })
        }
    }
    invitePopup?.let { inv ->
        ConsoleMenu("${inv.optString("name")} lädt ein", "${inv.optString("game")} (${modeNames[inv.optString("mode")] ?: ""})\n\nThorBro verbindet sich mit dem Wi-Fi-Direct-Netz, holt das Spiel falls nötig und startet es.",
            options = listOf("Beitreten" to { invitePopup = null; accept(inv) })) { invitePopup = null }
    }
    info?.let { (t, x) -> ConsoleMenu(t, x, options = emptyList()) { info = null } }
    DirectDialog(direct)
}

/** 4-digit PIN entry with big buttons, works with the D-pad */
@Composable
private fun PinPad(game: String, onClose: () -> Unit, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF0141519)).padding(24.dp)) {
            Text("PIN eingeben", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (game.isNotEmpty()) Text(game, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Text(pin.padEnd(4, '·').toList().joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, color = Accent,
                modifier = Modifier.padding(vertical = 12.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEachIndexed { ri, row ->
                Row {
                    row.forEachIndexed { ci, k ->
                        BigButton(k, primary = k == "OK", enabled = k != "OK" || pin.length == 4,
                            modifier = Modifier.weight(1f).padding(4.dp).firstFocus(first, ri == 0 && ci == 0)) {
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (pin.length == 4) onDone(pin)
                                else -> if (pin.length < 4) { pin += k; if (pin.length == 4) onDone(pin) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BigButton("Zurück") { onClose() }
        }
    }
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

private fun directOn(ctx: android.content.Context, d: DirectUi, scope: kotlinx.coroutines.CoroutineScope, then: String) {
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
            "Thor mit ThorBro in der Nähe: bekommt automatisch eine Einladung (Multiplayer öffnen).\n\nSonst in den WLAN-Einstellungen verbinden:\nNetz: ${s?.optString("ssid")}\nPasswort: ${s?.optString("pass")}\n\nThorBro dort im Browser: ${s?.optString("download")}",
            mono = true, options = listOf("Wi-Fi Direct beenden" to {
                scope.launch { d.st = withContext(Dispatchers.IO) { wtf.lennard.thorbro.Direct.stop(ctx) } }; d.menu = null
            })) { d.menu = null }
        "share" -> ConsoleMenu("ThorBro weitergeben",
            "Ohne gemeinsames WLAN: Wi-Fi Direct starten, der andere Thor verbindet sich und öffnet die angezeigte Adresse im Browser." +
                (s?.optString("lanDownload")?.takeIf { it.isNotEmpty() }?.let { "\n\nIm selben WLAN: $it" } ?: ""),
            options = listOf(
                "Über Wi-Fi Direct" to { if (s?.optBoolean("active") == true) d.menu = "info" else directOn(ctx, d, scope, "info") },
                "Quick Share / Bluetooth" to { d.menu = null; runCatching { ctx.startActivity(wtf.lennard.thorbro.Direct.shareIntent(ctx)) } },
            )) { d.menu = null }
        else -> ConsoleMenu("Wi-Fi Direct", m.removePrefix("error:"), options = emptyList()) { d.menu = null }
    }
}
