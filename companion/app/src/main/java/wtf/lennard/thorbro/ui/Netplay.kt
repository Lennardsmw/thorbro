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
import wtf.lennard.thorbro.Online
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
    val direct = rememberDirect()
    var invites by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var invitePopup by remember { mutableStateOf<JSONObject?>(null) }
    val seenInvites = remember { mutableSetOf<String>() }
    var serverBusy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var onlineMenu by remember { mutableStateOf<JSONObject?>(null) }
    var onlineBusy by remember { mutableStateOf(false) }
    var codePad by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }

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
            if (st?.optJSONObject("lobby") == null && joinJob?.done != false) {
                val found = withContext(Dispatchers.IO) { runCatching { Netplay.invites(ctx) }.getOrDefault(emptyList()) }
                invites = found
                found.firstOrNull { it.optString("pin").isNotEmpty() && seenInvites.add(it.optString("id") + it.optString("pin")) }
                    ?.let { if (invitePopup == null && pinFor == null) invitePopup = it }
            }
            delay(8000)
        }
    }
    val s = st
    if (s == null) { Line("Lade …", Muted); return }

    /** joining runs in the bottom bar (next to A/B), not as a tile; only a failure with a long reason opens a dialog */
    fun runJoin(j: Jobs.Job) {
        joinJob = j
        scope.launch {
            while (!j.done) { BottomStatus.working(j.message.ifEmpty { "Verbinde …" }, j.progress.toFloat()); delay(250) }
            val err = j.error
            if (err != null) {
                BottomStatus.done("Ging nicht", true, scope)
                info = "Ging nicht" to err
            } else BottomStatus.done(when (j.result?.optString("mode")) {
                "share" -> "Spiel erhalten"
                "azahar" -> "Verbunden mit ${j.result?.optString("host")} · in Azahar 127.0.0.1 beitreten"
                else -> j.result?.optString("hint")?.lineSequence()?.firstOrNull()?.ifEmpty { null } ?: "Verbunden"
            }, false, scope)
            joinJob = null
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

    val online = s.optJSONObject("online")
    val onlineOpen = online?.optBoolean("open") == true
    val known = online?.optJSONArray("known").objs()
    val friends = s.optJSONArray("friends").objs()
    val joined = s.optJSONObject("joined")?.takeIf { it.optString("mode") == "azahar" && lobby == null }
    fun showCode() {
        if (onlineBusy) return
        onlineBusy = true
        scope.launch {
            BottomStatus.working("Öffne Online-Raum …", 0.3f)
            val r = withContext(Dispatchers.IO) { runCatching { if (onlineOpen && online!!.optString("code").isNotEmpty()) Online.hostStatus(ctx) else Online.startHost(ctx) }
                .getOrElse { JSONObject().put("error", it.message) } }
            onlineBusy = false
            if (r.has("error")) { BottomStatus.done("Online geht nicht", true, scope); info = "Freunde einladen" to r.optString("error") }
            else { BottomStatus.text = null; onlineMenu = r }
            tick++
        }
    }

    TileGrid(first) {
        // ---- 1. open lobbies: what you can join (or are in) right now ----
        val lobbyCount = (if (lobby != null) 1 else 0) + (if (joined != null) 1 else 0) + invites.size
        header("Offene Lobbys", when {
            lobbyCount > 0 -> ""
            serverOn -> "Gerade keine bei anderen – dein Spielserver läuft, Freunde finden dich"
            else -> "Gerade keine – Thors in der Nähe tauchen hier von selbst auf"
        })
        if (lobby != null) {
            val share = lobby.optString("mode") == "share"
            item {
                ConsoleTile(lobby.optString("game"), (if (share) "du gibst weiter · " else "deine Lobby · ") + hostStateText(lobby, room),
                    if (lobby.optString("hostState") in setOf("hosting", "sharing")) Icons.Filled.CheckCircle else Icons.Filled.Lock, image = lobbyArt, accent = true,
                    modifier = Modifier.firstFocus(first, true)) {
                    val guests = lobby.optJSONArray("guestNames").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }.ifEmpty { "noch keiner" }
                    val inRoom = room?.optJSONArray("members").let { a -> (0 until (a?.length() ?: 0)).joinToString(", ") { a!!.optString(it) } }
                    info = lobby.optString("game") to buildString {
                        append("PIN ${lobby.optString("pin")} · beigetreten: $guests")
                        if (lobby.optString("mode") == "azahar") append("\nIm 3DS-Raum: ${inRoom.ifEmpty { "niemand" }}\n\n${Azahar.hostSteps()}")
                        if (lobby.optString("hostState") == "manual") append("\n\nRetroArch hat nicht von selbst gehostet: RetroArch-Menü → Netplay → Netplay-Host starten.")
                    }
                }
            }
            item { ConsoleTile("Lobby beenden", if (share) "nicht mehr anbieten" else "für alle schließen", Icons.Filled.Close) { Netplay.close(ctx); tick++ } }
        }
        joined?.let { j ->
            val st3 = s.optJSONObject("azahar")?.optString("room")
            item {
                ConsoleTile("3DS-Raum bei ${j.optString("host")}", if (st3 == "connected") "du bist drin ✓ · im Spiel lokal spielen" else "in Azahar: Mehrspieler → Raum beitreten → 127.0.0.1",
                    if (st3 == "connected") Icons.Filled.CheckCircle else Icons.Filled.SportsEsports, accent = true,
                    modifier = Modifier.firstFocus(first, lobby == null)) { info = "3DS-Raum bei ${j.optString("host")}" to j.optString("hint") }
            }
            item { ConsoleTile("Verlassen", "127.0.0.1 wieder dieser Thor", Icons.Filled.Close) {
                scope.launch { withContext(Dispatchers.IO) { Netplay.clearJoined(ctx) }; tick++ }
            } }
        }
        invites.forEach { inv ->
            val mode = inv.optString("mode")
            item {
                ConsoleTile(if (mode == "server") "Spielserver von ${inv.optString("name")}" else inv.optString("game").ifEmpty { inv.optString("name") },
                    when { mode == "server" -> "3DS-Raum · ${inv.optString("via")}"; mode == "share" -> "${inv.optString("name")} schickt dir das Spiel"
                        inv.optString("pin").isEmpty() -> "${inv.optString("name")} · verbinden"; else -> "${inv.optString("name")} · ${modeNames[mode] ?: ""} · ${inv.optString("via")}" },
                    if (inv.optString("pin").isEmpty() && mode != "server") Icons.Filled.BluetoothSearching else Icons.Filled.SportsEsports,
                    accent = inv.optString("pin").isNotEmpty() || mode == "server",
                    modifier = Modifier.firstFocus(first, lobby == null && joined == null && inv === invites.first())) { accept(inv) }
            }
        }

        // ---- 2. friends: invite, join with a code, manage ----
        header("Freunde", if (onlineOpen) "Online-Raum offen · ${online!!.optInt("guests")} von außen drin" else "auch übers Internet · Freundes-Code reicht")
        item {
            ConsoleTile(if (onlineBusy) "Öffnet …" else "Freunde einladen", "Freundes-Code zeigen", Icons.Filled.Public, accent = onlineOpen,
                modifier = Modifier.firstFocus(first, lobbyCount == 0)) { showCode() }
        }
        item { ConsoleTile("Code eingeben", "bei einem Freund mitspielen", Icons.Filled.Dialpad) { codePad = true } }
        item {
            val n = known.size + friends.size
            ConsoleTile("Freunde verwalten", if (n == 0) "noch keine" else "$n ${if (n == 1) "Freund" else "Freunde"}", Icons.Filled.Groups) { friendsOpen = true }
        }

        // ---- 3. game server ----
        header("Spielserver", when {
            supported.isEmpty() -> "Azahar, PPSSPP, RetroArch oder Eden installieren"
            serverOn -> "läuft · Spiel aus einem beliebigen Frontend starten"
            else -> "3DS-Raum, Wi-Fi Direct und Einladungen mit einem Knopf"
        })
        item {
            ConsoleTile(when { serverBusy -> if (serverOn) "Stoppt …" else "Startet …"; serverOn -> "Server läuft"; else -> "Spielserver starten" },
                if (serverOn) "Details" else "keine Spielauswahl nötig",
                if (serverOn) Icons.Filled.CheckCircle else Icons.Filled.WifiTethering, accent = serverOn) {
                if (serverOn) info = "Spielserver" to serverInfo(server) else toggleServer()
            }
        }
        if (serverOn) item { ConsoleTile("Server stoppen", "Raum und Wi-Fi Direct aus", Icons.Filled.Close) { toggleServer() } }

        // ---- 4. advanced ----
        header("Erweitert", "")
        item { ConsoleTile(if (advanced) "Weniger" else "Erweitert", "WLAN, PIN, Wi-Fi Direct, Controller", Icons.Filled.Tune) { advanced = !advanced } }
        if (advanced) {
            item {
                val auto = s.optBoolean("auto")
                ConsoleTile(if (auto) "Auto-Einladen: an" else "Auto-Einladen: aus",
                    when { !auto -> "nur bei laufendem Spielserver"; s.optString("shizuku") != "ready" -> "braucht Shizuku"; else -> "Spielstart lädt Freunde ein" },
                    Icons.Filled.WifiTethering, accent = auto) { wtf.lennard.thorbro.Friends.setAuto(ctx, !auto); tick++ }
            }
            item {
                val wlan = s.optBoolean("wlanOnly")
                ConsoleTile(if (wlan) "Nur WLAN" else "WLAN + Wi-Fi Direct",
                    if (wlan) "beide im selben WLAN" else "WLAN, sonst eigenes Funknetz", Icons.Filled.WifiTethering, accent = wlan) {
                    scope.launch { withContext(Dispatchers.IO) { runCatching { Netplay.setWlanOnly(ctx, !wlan) } }; tick++ }
                }
            }
            item {
                ConsoleTile(if (searching) "Sucht …" else "Thors suchen", peers?.let { "${it.size} gefunden" } ?: "im selben WLAN, mit PIN", Icons.Filled.Search) { search() }
            }
            peers.orEmpty().forEach { p ->
                val l = p.optJSONObject("lobby")
                val open = l?.optBoolean("open") == true
                item {
                    ConsoleTile(if (open) l!!.optString("game") else p.optString("service").removePrefix("ThorBro ").ifEmpty { p.optString("address") },
                        when { !p.optBoolean("reachable") -> "nicht erreichbar"; open -> "von ${l!!.optString("host")} · PIN eingeben"
                            l?.optBoolean("room") == true -> "3DS-Raum nutzen"; else -> "keine Lobby offen" },
                        Icons.Filled.Groups, accent = open || l?.optBoolean("room") == true) {
                        when {
                            open -> pinFor = p
                            l?.optBoolean("room") == true -> scope.launch {
                                val r = withContext(Dispatchers.IO) { Netplay.useRoomOf(ctx, p.optString("address"), l.optString("host")) }
                                if (r.has("error")) BottomStatus.done(r.optString("error"), true, scope)
                                else BottomStatus.done("3DS-Raum von ${l.optString("host")} · in Azahar 127.0.0.1 beitreten", false, scope)
                                tick++
                            }
                            else -> search()
                        }
                    }
                }
            }
            item {
                ConsoleTile("Thor als Controller", "Bluetooth-Gamepad für anderen Thor oder TV", Icons.Filled.Gamepad) {
                    ctx.startActivity(android.content.Intent(ctx, GamepadActivity::class.java))
                }
            }
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
                ConsoleTile("Dieser Thor", addressText(s.optJSONArray("addresses")).lineSequence().firstOrNull() ?: "", Icons.Filled.Home) {
                    info = "Adressen dieses Thors" to addressText(s.optJSONArray("addresses"))
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
    onlineMenu?.let { o ->
        CodeDialog(o,
            onNew = { scope.launch { onlineMenu = withContext(Dispatchers.IO) { Online.newCode(ctx); Online.hostStatus(ctx) }; tick++ } },
            onStop = { scope.launch { withContext(Dispatchers.IO) { Online.stopHost() }; onlineMenu = null; tick++ } },
            onReset = { scope.launch { onlineMenu = withContext(Dispatchers.IO) { Online.resetAccess(ctx) }.takeIf { it.optBoolean("open") }; tick++ } },
        ) { onlineMenu = null }
    }
    if (friendsOpen) FriendsDialog(known, friends,
        onPlay = { k -> friendsOpen = false; runJoin(Jobs.start("Online beitreten") { Online.joinKnown(ctx, k.optString("id"), it) }) },
        onRemoveOnline = { k -> Online.forget(ctx, k.optString("id")); tick++ },
        onRemoveNearby = { f -> wtf.lennard.thorbro.Friends.remove(ctx, f.optString("id")); tick++ },
        onCode = { friendsOpen = false; codePad = true },
    ) { friendsOpen = false }
    if (codePad) CodePad(onClose = { codePad = false }) { c ->
        codePad = false
        runJoin(Jobs.start("Online beitreten") { Online.joinCode(ctx, c, it) })
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
