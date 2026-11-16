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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
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
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Netplay
import wtf.lennard.thorbro.Roms
import java.io.File

private val modeNames = mapOf("retroarch" to "RetroArch", "ppsspp" to "PPSSPP", "eden" to "Eden")

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
    val s = st
    if (s == null) { Line("Lade …", Muted); return }
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
            item {
                ConsoleTile("PIN ${lobby.optString("pin")}", when (lobby.optString("hostState")) {
                    "hosting" -> "wartet auf Mitspieler"; "manual" -> "im Emulator Host starten"; else -> "startet …" },
                    Icons.Filled.Lock, accent = true, modifier = Modifier.firstFocus(first, true)) {
                    info = "PIN ${lobby.optString("pin")}" to "Dein Thor: ${s.optString("ip")}\nMitspieler: " +
                        (lobby.optJSONArray("guestNames").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }.ifEmpty { "noch keiner" }) +
                        if (lobby.optString("hostState") == "manual") "\n\nRetroArch hat nicht von selbst gehostet: RetroArch-Menü → Netplay → Netplay-Host starten." else ""
                }
            }
            item { ConsoleTile("Lobby schließen", "", Icons.Filled.Close) { Netplay.close(); tick++ } }
        }
        header("Beitreten", if (s.optString("ip").isEmpty()) "kein WLAN – Hotspot am anderen Thor einschalten" else "selbes WLAN oder Hotspot")
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
            val j = Jobs.start("Netplay beitreten") { Netplay.join(ctx, addr, pin, it) }
            joinJob = j
            scope.launch {
                while (!j.done) { joinProg = j.progress.toFloat(); delay(300) }
                joinProg = 1f
                info = if (j.error != null) "Beitreten ging nicht" to j.error!! else "Läuft" to (j.result?.optString("hint") ?: "")
            }
        }
    }
    info?.let { (t, x) -> ConsoleMenu(t, x, options = emptyList()) { info = null } }
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
