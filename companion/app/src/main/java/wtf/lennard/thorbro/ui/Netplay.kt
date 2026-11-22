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

private val modeNames = mapOf("retroarch" to "RetroArch", "ppsspp" to "PPSSPP", "eden" to "Eden", "azahar" to "Azahar", "share" to "Weitergeben")

/** status line of the own lobby */
private fun hostStateText(l: JSONObject, room: JSONObject?) = when (l.optString("hostState")) {
    "hosting" -> if (l.optString("mode") == "azahar") {
        val n = room?.optJSONArray("members")?.length() ?: 0
        if (n == 0) "Raum läuft · in Azahar beitreten" else "Raum läuft · $n im Raum"
    } else "wartet auf Mitspieler"
    "sharing" -> "wird angeboten"
    "error" -> "3DS-Raum ist aus – Lobby schließen, neu starten"
    "manual" -> "im Emulator Host starten"
    else -> "startet …"
}

/** every address of this Thor, by network ("WLAN 192.168.178.20", "NetBird 100.116.x.x", …) */
private fun addressText(a: org.json.JSONArray?) = a.objs().joinToString("\n") { "${it.optString("kind")}: ${it.optString("ip")}" }.ifEmpty { "kein Netz" }

/**
 * Multiplayer, one main way: pick a game → "Mit Freund spielen". ThorBro opens the lobby, Wi-Fi Direct (so a Thor nearby
 * gets the invite over the air) and for 3DS its own room. The friend's Thor shows "X lädt ein" → one button.
 * "Spiel weitergeben" hands a game over the same way. Everything manual (search, PIN, Wi-Fi Direct by hand) is under "Erweitert".
 */
@Composable
fun NetplayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var games by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var allGames by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
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
    var sharePick by remember { mutableStateOf(false) } // next tapped game is handed over instead of played
    var advanced by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val s = runCatching { Netplay.status(ctx) }.getOrNull()
            val ok = s?.optJSONArray("supported").objs().associateBy { it.optString("id") }
            val all = runCatching {
                Roms.scan(ctx, false).optJSONArray("systems").objs()
                    .flatMap { sys -> sys.optJSONArray("roms").objs().filter { !it.optBoolean("dir") }.map { sys.optString("id") to it } }
            }.getOrDefault(emptyList())
            st = s; allGames = all; games = all.filter { it.first in ok }
        }
    }
    // follow the host state (RetroArch hosting switches on a few seconds after start)
    LaunchedEffect(st?.optJSONObject("lobby")?.optString("hostState")) {
        if (st?.optJSONObject("lobby")?.optString("hostState") == "starting") { delay(4000); tick++ }
    }
    // 3DS room: who is in it, live
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
            info = if (j.error != null) "Ging nicht" to j.error!! else (if (j.result?.optString("mode") == "share") "Spiel erhalten" else "Läuft") to (j.result?.optString("hint") ?: "")
            tick++
        }
    }
    fun accept(inv: JSONObject) = runJoin(Jobs.start("Einladung annehmen") { Netplay.accept(ctx, inv, it) })
    val room = s.optJSONObject("azahar")?.optJSONObject("server")
    val lobby = s.optJSONObject("lobby")
    val supported = s.optJSONArray("supported").objs().associateBy { it.optString("id") }
    val canDirect = Direct.permitted(ctx)

    fun search() {
        if (searching) return
        searching = true
        scope.launch { peers = withContext(Dispatchers.IO) { runCatching { Netplay.peers(ctx).objs() }.getOrDefault(emptyList()) }; searching = false }
    }
    fun go(sys: String, r: JSONObject, share: Boolean) {
        if (!canDirect) (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Direct.permissionName()), 7)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { if (share) Netplay.share(ctx, sys, File(r.optString("path"))) else Netplay.host(ctx, sys, File(r.optString("path"))) }
                    .getOrElse { JSONObject().put("error", it.message) }
            }
            val reach = if (res.optBoolean("direct")) "Ein Thor in der Nähe bekommt jetzt eine Einladung – dort „Beitreten“ drücken."
                else "Wi-Fi Direct ist aus (Berechtigung „Geräte in der Nähe“ fehlt?): der andere Thor muss im selben WLAN sein → Erweitert → Thors suchen, PIN ${res.optString("pin")}."
            info = when {
                res.has("error") -> "Geht nicht" to res.optString("error")
                share -> "Wird weitergegeben" to "${r.optString("name")}\n\n$reach"
                else -> "Läuft · PIN ${res.optString("pin")}" to "$reach\n\n${res.optString("hint")}"
            }
            tick++
        }
    }

    TileGrid(first) {
        if (lobby != null) {
            val share = lobby.optString("mode") == "share"
            header(if (share) "Du gibst weiter" else "Du spielst", "${lobby.optString("game")} · ${modeNames[lobby.optString("mode")]}")
            item {
                ConsoleTile(hostStateText(lobby, room), "PIN ${lobby.optString("pin")} · Details",
                    if (lobby.optString("hostState") in setOf("hosting", "sharing")) Icons.Filled.CheckCircle else Icons.Filled.Lock, accent = true,
                    modifier = Modifier.firstFocus(first, true)) {
                    val guests = lobby.optJSONArray("guestNames").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }.ifEmpty { "noch keiner" }
                    val inRoom = room?.optJSONArray("members").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }
                    info = "PIN ${lobby.optString("pin")}" to buildString {
                        append("Beigetreten: $guests")
                        if (lobby.optString("mode") == "azahar") {
                            append("\nIm 3DS-Raum: ${inRoom.ifEmpty { "niemand" }}\n\n${Azahar.hostSteps()}")
                            append("\n\nDer Raum ist erreichbar unter (Port ${room?.optInt("port")}):\n${addressText(s.optJSONArray("addresses"))}")
                            append("\nVon unterwegs: über NetBird, wenn beide Thors drin sind.")
                        } else append("\n\nDieser Thor:\n${addressText(s.optJSONArray("addresses"))}")
                        if (lobby.optString("hostState") == "manual") append("\n\nRetroArch hat nicht von selbst gehostet: RetroArch-Menü → Netplay → Netplay-Host starten.")
                    }
                }
            }
            item { ConsoleTile("Beenden", if (share) "nicht mehr anbieten" else "Lobby und Raum schließen", Icons.Filled.Close) { Netplay.close(ctx); tick++ } }
        }
        // guest side of a 3DS room: did it work?
        s.optJSONObject("joined")?.takeIf { it.optString("mode") == "azahar" && lobby == null }?.let { j ->
            header("3DS-Raum", "${j.optString("game")} · bei ${j.optString("host")}")
            item {
                val st3 = s.optJSONObject("azahar")?.optString("room")
                ConsoleTile(when (st3) { "connected" -> "Im Raum ✓"; "unknown" -> "Status unbekannt"; else -> "Noch nicht im Raum" },
                    if (st3 == "connected") "im Spiel lokalen Mehrspieler wählen" else "in Azahar: Mehrspieler → Raum beitreten",
                    if (st3 == "connected") Icons.Filled.CheckCircle else Icons.Filled.SportsEsports, accent = st3 == "connected") {
                    info = "3DS-Raum" to j.optString("hint")
                }
            }
        }
        joinJob?.let { j ->
            header("Verbinden")
            item {
                ConsoleTile(if (j.done) (j.error ?: "Fertig") else j.message.ifEmpty { "Verbinde …" }, "${(joinProg * 100).toInt()} %",
                    Icons.Filled.SportsEsports, progress = joinProg) { if (j.done) joinJob = null }
            }
        }
        if (invites.isNotEmpty()) {
            header("Einladungen in der Nähe", "ein Druck genügt")
            invites.forEach { inv ->
                val shareInv = inv.optString("mode") == "share"
                item {
                    ConsoleTile(inv.optString("name"), inv.optString("game").ifEmpty { "Wi-Fi Direct offen" }.let { g ->
                        when { inv.optString("pin").isEmpty() -> "$g · verbinden"; shareInv -> "$g · annehmen"; else -> "$g · beitreten" } },
                        if (shareInv) Icons.Filled.Share else Icons.Filled.BluetoothSearching, accent = inv.optString("pin").isNotEmpty(),
                        modifier = Modifier.firstFocus(first, lobby == null)) { accept(inv) }
                }
            }
        }

        if (lobby == null) {
            header(if (sharePick) "Welches Spiel weitergeben?" else "Mit Freund spielen",
                when {
                    sharePick -> "der Thor in der Nähe bekommt es geschickt"
                    supported.isEmpty() -> "RetroArch, PPSSPP, Azahar oder Eden installieren"
                    else -> "Spiel wählen – der andere Thor bekommt eine Einladung · " + supported.values.joinToString(", ") { it.optString("name") + if (it.optBoolean("shaky")) " (wackelig)" else "" }
                })
            item {
                ConsoleTile(if (sharePick) "Abbrechen" else "Spiel weitergeben", if (sharePick) "zurück zum Spielen" else "nur schicken, nicht spielen",
                    if (sharePick) Icons.Filled.Close else Icons.Filled.Share, accent = sharePick,
                    modifier = Modifier.firstFocus(first, invites.isEmpty())) { sharePick = !sharePick }
            }
            (if (sharePick) allGames else games).take(150).forEach { (sys, r) ->
                item {
                    val name = supported[sys]?.optString("name") ?: sys
                    ConsoleTile(r.optString("name"), if (sharePick) "$name · weitergeben" else name,
                        if (sharePick) Icons.Filled.Share else Icons.Filled.WifiTethering) { val share = sharePick; sharePick = false; go(sys, r, share) }
                }
            }
        }

        header("Ein Bildschirm, zwei Spieler", "Dolphin, melonDS & Co. haben am Thor keinen Mehrspieler übers Netz")
        item {
            ConsoleTile("Thor als Controller", "dieser Thor wird Bluetooth-Gamepad für den anderen Thor oder den Fernseher",
                Icons.Filled.Gamepad) { ctx.startActivity(android.content.Intent(ctx, GamepadActivity::class.java)) }
        }

        header("Erweitert", if (advanced) "gleiches WLAN, PIN, Wi-Fi Direct von Hand" else "")
        item { ConsoleTile(if (advanced) "Weniger" else "Erweitert", "Suchen, PIN, Wi-Fi Direct, ThorBro weitergeben", Icons.Filled.Tune) { advanced = !advanced } }
        if (advanced) {
            item {
                ConsoleTile(if (searching) "Sucht …" else "Thors suchen", peers?.let { "${it.size} gefunden" } ?: "im selben WLAN", Icons.Filled.Search) { search() }
            }
            peers.orEmpty().forEach { p ->
                val l = p.optJSONObject("lobby")
                val open = l?.optBoolean("open") == true
                item {
                    ConsoleTile(if (open) l!!.optString("game") else p.optString("service").removePrefix("ThorBro ").ifEmpty { p.optString("address") },
                        when { !p.optBoolean("reachable") -> "nicht erreichbar"; open -> "von ${l!!.optString("host")} · PIN eingeben"; else -> "keine Lobby offen" },
                        Icons.Filled.Groups, accent = open) { if (open) pinFor = p else search() }
                }
            }
            item {
                ConsoleTile("Dieser Thor", addressText(s.optJSONArray("addresses")).replace("\n", " · "), Icons.Filled.Home) {
                    info = "Adressen dieses Thors" to addressText(s.optJSONArray("addresses")) +
                        "\n\nVon unterwegs spielen: beide Thors in NetBird, dann klappt „Thors suchen“ nicht (kein mDNS) – der Freund tippt im Web-Panel die NetBird-Adresse ein, für 3DS reicht die Adresse in Azahar."
                }
            }
            directTiles(ctx, direct, scope, share = true)
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
        val shareInv = inv.optString("mode") == "share"
        ConsoleMenu(if (shareInv) "${inv.optString("name")} schickt dir ein Spiel" else "${inv.optString("name")} lädt ein",
            if (shareInv) "${inv.optString("game")}\n\nThorBro verbindet sich per Wi-Fi Direct und speichert das Spiel bei deinen ROMs."
            else "${inv.optString("game")} (${modeNames[inv.optString("mode")] ?: ""})\n\nThorBro verbindet sich per Wi-Fi Direct, holt das Spiel falls nötig und startet es.",
            options = listOf((if (shareInv) "Annehmen" else "Beitreten") to { invitePopup = null; accept(inv) })) { invitePopup = null }
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
