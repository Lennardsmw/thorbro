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
import java.io.File

private val modeNames = mapOf("retroarch" to "RetroArch", "ppsspp" to "PPSSPP", "eden" to "Eden", "azahar" to "Azahar", "share" to "Weitergeben", "server" to "Spielserver")

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
 * Multiplayer, one main way: "Spielserver starten" (no game to pick). ThorBro runs the 3DS room, Wi-Fi Direct (so a Thor
 * nearby gets the invite over the air) and invites friends to whatever game is started next, from any frontend.
 * The friend's Thor shows "X lädt ein" → one button. Handing a game over lives in the game's own page ("Weitergeben").
 * Everything manual (search, PIN, Wi-Fi Direct by hand) is under "Erweitert".
 */
@Composable
fun NetplayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var lobbyArt by remember { mutableStateOf<File?>(null) }
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
    var serverBusy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var friendMenu by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            st = runCatching { Netplay.status(ctx) }.getOrNull()
            lobbyArt = Netplay.lobby?.let { Netplay.art(ctx, it.system, it.rom) }?.takeIf { it.extension.lowercase() != "svg" }
        }
    }
    // follow the host state (RetroArch hosting switches on a few seconds after start)
    LaunchedEffect(st?.optJSONObject("lobby")?.optString("hostState")) {
        if (st?.optJSONObject("lobby")?.optString("hostState") == "starting") { delay(4000); tick++ }
    }
    // 3DS room: who is in it, live
    val azLive = st?.optJSONObject("lobby")?.optString("mode") == "azahar" || st?.optJSONObject("joined")?.optString("mode") == "azahar" ||
        st?.optJSONObject("server")?.optBoolean("on") == true
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
    val server = s.optJSONObject("server") ?: JSONObject()
    val serverOn = server.optBoolean("on")
    fun serverInfo(sv: JSONObject) = buildString {
        append("Name: ${sv.optString("name")}\n")
        if (sv.optBoolean("direct")) append("Wi-Fi Direct: ${sv.optString("ssid")} (Thors in der Nähe sehen ihn von selbst)\n")
        append("\nAdressen:\n${addressText(sv.optJSONArray("addresses"))}\n")
        val svc = sv.optJSONArray("services").objs()
        if (svc.isEmpty()) append("\nKein passender Emulator installiert (Azahar, PPSSPP, RetroArch, Eden).")
        else {
            append("\nLäuft hier:\n")
            svc.forEach { append("• ${it.optString("name")}${if (it.has("port") && !it.isNull("port")) " · Port ${it.optInt("port")}" else ""}\n   ${it.optString("how")}\n") }
        }
        val members = sv.optJSONObject("room")?.optJSONArray("members").objs().size
        if (members > 0) append("\nIm 3DS-Raum: ${sv.optJSONObject("room")!!.optJSONArray("members").let { a -> (0 until a!!.length()).joinToString(", ") { a.optString(it) } }}\n")
        append("\nSpiel aus einem beliebigen Frontend starten – Freunde in der Nähe bekommen die Einladung. Von unterwegs: über NetBird.")
    }
    fun toggleServer() {
        if (serverBusy) return
        if (!serverOn && !canDirect) (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Direct.permissionName()), 7)
        serverBusy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { if (serverOn) { Netplay.stopServer(ctx); null } else Netplay.startServer(ctx) }.getOrElse { JSONObject().put("roomError", it.message) }
            }
            serverBusy = false
            if (res != null) info = "Spielserver läuft" to (res.optString("roomError").takeIf { it.isNotEmpty() }?.let { "3DS-Raum: $it\n\n" } ?: "") + serverInfo(res)
            tick++
        }
    }

    TileGrid(first) {
        if (lobby != null) {
            val share = lobby.optString("mode") == "share"
            header(if (share) "Du gibst weiter" else "Du spielst", "${lobby.optString("game")} · ${modeNames[lobby.optString("mode")]}")
            item {
                ConsoleTile(hostStateText(lobby, room), "PIN ${lobby.optString("pin")} · Details",
                    if (lobby.optString("hostState") in setOf("hosting", "sharing")) Icons.Filled.CheckCircle else Icons.Filled.Lock, image = lobbyArt, accent = true,
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
                        if (inv.optString("pin").isEmpty()) Icons.Filled.BluetoothSearching else Icons.Filled.SportsEsports, accent = inv.optString("pin").isNotEmpty(),
                        modifier = Modifier.firstFocus(first, lobby == null)) { accept(inv) }
                }
            }
        }

        header("Spielserver", when {
            serverOn -> "läuft – Spiel aus einem beliebigen Frontend starten"
            supported.isEmpty() -> "Azahar, PPSSPP, RetroArch oder Eden installieren"
            else -> "ein Knopf: 3DS-Raum, Wi-Fi Direct und Einladungen · " + supported.values.map { modeNames[it.optString("mode")] }.distinct().joinToString(", ")
        })
        item {
            ConsoleTile(when { serverBusy -> if (serverOn) "Stoppt …" else "Startet …"; serverOn -> "Server läuft"; else -> "Spielserver starten" },
                if (serverOn) addressText(server.optJSONArray("addresses")).lineSequence().firstOrNull()?.substringAfter(": ") ?: "" else "keine Spielauswahl nötig",
                if (serverOn) Icons.Filled.CheckCircle else Icons.Filled.WifiTethering, accent = serverOn,
                modifier = Modifier.firstFocus(first, invites.isEmpty() && lobby == null)) {
                if (serverOn) info = "Spielserver" to serverInfo(server) else toggleServer()
            }
        }
        if (serverOn) {
            item { ConsoleTile("Infos", "Adressen, Ports, wer drin ist", Icons.Filled.Info) { info = "Spielserver" to serverInfo(server) } }
            item { ConsoleTile("Server stoppen", "Raum und Wi-Fi Direct aus", Icons.Filled.Close) { toggleServer() } }
        }

        val friends = s.optJSONArray("friends").objs()
        header("Freunde", if (friends.isEmpty()) "Einmal eine Einladung annehmen – danach kommt sie als Benachrichtigung, auch in ES-DE & Co."
            else "Einladungen kommen als Benachrichtigung · ein Spiel aus jedem Frontend lädt sie ein")
        friends.forEach { f ->
            item {
                ConsoleTile(f.optString("name"), "Freund · ${f.optString("id")}", Icons.Filled.Groups) { friendMenu = f }
            }
        }
        if (friends.isNotEmpty()) {
            item {
                val auto = s.optBoolean("auto")
                ConsoleTile(if (auto) "Automatisch einladen: an" else "Automatisch einladen: aus",
                    when { !auto -> "nur bei laufendem Spielserver"; s.optString("shizuku") != "ready" -> "braucht Shizuku, um das Spiel zu erkennen"; else -> "beim Spielstart aus jedem Frontend" },
                    Icons.Filled.WifiTethering, accent = auto) {
                    wtf.lennard.thorbro.Friends.setAuto(ctx, !auto); tick++
                }
            }
            item {
                ConsoleTile("Emulatoren einrichten", "RetroArch & PPSSPP fürs Hosten vorbereiten", Icons.Filled.Tune) {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Netplay.prepare(ctx) }.getOrElse { JSONObject().put("Fehler", it.message) } }
                        info = "Emulatoren" to r.keys().asSequence().joinToString("\n") { "${modeNames[it] ?: it}: ${r.optString(it)}" }.ifEmpty { "Nichts zu tun" }
                    }
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
    friendMenu?.let { f ->
        ConsoleMenu(f.optString("name"), "Einladungen von diesem Thor kommen als Benachrichtigung.",
            options = listOf("Entfernen" to { wtf.lennard.thorbro.Friends.remove(ctx, f.optString("id")); friendMenu = null; tick++ })) { friendMenu = null }
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
