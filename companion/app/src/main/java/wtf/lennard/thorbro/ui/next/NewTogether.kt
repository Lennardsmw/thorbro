package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Apps
import wtf.lennard.thorbro.Azahar
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Netplay
import wtf.lennard.thorbro.Streaming
import java.io.File

/** one of the two big cards on the Zusammen page */
@Composable
private fun TogetherCard(title: String, icon: ImageVector, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(GlassFill).border(1.dp, GlassLine, RoundedCornerShape(22.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/**
 * Zusammen: two cards – play with friends (the party; friends themselves live in the L2 panel) and play from the PC
 * (Moonlight). Below a row of the rarer tools. No lobby lists, no game server, no friend tiles.
 */
@Composable
fun TogetherScreenNew() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var lobbyArt by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    val direct = rememberDirect()
    // streaming
    var stream by remember { mutableStateOf<JSONObject?>(null) }
    var job by remember { mutableStateOf<Jobs.Job?>(null) }
    var prog by remember { mutableStateOf(0f) }
    var hosts by remember { mutableStateOf<List<JSONObject>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(wtf.lennard.thorbro.Screen.running) }
    var hostMenu by remember { mutableStateOf<JSONObject?>(null) }
    var screenMenu by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            st = runCatching { Netplay.status(ctx) }.getOrNull()
            lobbyArt = Netplay.lobby?.let { Netplay.art(ctx, it.system, it.rom) }?.takeIf { it.extension.lowercase() != "svg" }
        }
    }
    LaunchedEffect(tick / 5) { stream = withContext(Dispatchers.IO) { runCatching { Streaming.status(ctx) }.getOrNull() } }
    WhileVisible { while (true) { live = wtf.lennard.thorbro.Screen.running; delay(1000) } }
    WhileVisible(tick) { delay(3000); tick++ }
    LaunchedEffect(Unit) { delay(250); runCatching { first.requestFocus() } }

    val s = st
    val party = s?.optJSONObject("party")
    val net = s?.optJSONObject("partyNet") ?: JSONObject()
    val lobby = s?.optJSONObject("lobby")
    val members = party?.optJSONArray("members").objs()
    val services = s?.optJSONArray("services").objs()
    val installed = stream?.optBoolean("installed") == true
    val update = stream?.optBoolean("updateAvailable") == true

    fun install() {
        val j = Jobs.start("Moonlight installieren") { Streaming.install(ctx, it) }
        job = j
        scope.launch { while (!j.done) { prog = j.progress.toFloat(); delay(300) }; delay(2500); job = null; tick += 5 }
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeaderFollows(list)
    CenteredFocus {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      item(key = "cards") {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TogetherCard("Mit Freunden", Icons.Filled.Groups, Modifier.weight(1f).fillMaxHeight()) {
                Text(when {
                    party == null -> "Freunde einladen – dann startet ein Spiel bei allen, auch übers Internet."
                    party.optBoolean("leaderMe") -> "Du leitest die Party"
                    net.optBoolean("connected") -> "Verbunden mit ${net.optString("leader")}"
                    net.optBoolean("following") -> "Verbinde mit ${net.optString("leader")} …"
                    else -> "Wartet auf ${members.firstOrNull { !it.optBoolean("me") }?.optString("name") ?: "den Leiter"}"
                }, fontSize = 14.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // the party at a glance: you, the others, and a place to invite someone
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    PartyFace(wtf.lennard.thorbro.Pals.myName(ctx), "Du", me = true)
                    members.filter { !it.optBoolean("me") }.take(4).forEach { m -> PartyFace(m.optString("name"), m.optString("name")) }
                    InviteFace(Modifier.focusRequester(first)) { FriendsUi.open = true }
                }
                if (lobby != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Panel)) { Img(lobbyArt, 200, Modifier.fillMaxSize(), ContentScale.Crop) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lobby.optString("game"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("spielt gerade", fontSize = 13.sp, color = Ok)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (lobby != null) BigButton("Spiel für alle beenden", Icons.Filled.Close, modifier = Modifier.fillMaxWidth()) { Netplay.close(ctx); tick++ }
                else if (party != null) BigButton("Party öffnen", Icons.Filled.Groups, primary = true, modifier = Modifier.fillMaxWidth()) { FriendsUi.open = true }
                lobby?.let { l -> if (l.optString("mode") in listOf("azahar", "eden")) Text(
                    if (l.optString("mode") == "azahar") "Azahar → Mehrspieler → Direkt verbinden → 127.0.0.1:${Azahar.PORT}"
                    else "Eden → Mehrspieler → Raum beitreten → 127.0.0.1:${wtf.lennard.thorbro.EdenRoom.PORT}",
                    fontSize = 13.sp, color = Muted, maxLines = 2) }
            }
            TogetherCard("Vom PC spielen", Icons.Filled.Computer, Modifier.weight(1f).fillMaxHeight()) {
                Text(if (installed) "Moonlight ${stream?.optString("version")} · Sunshine auf dem PC" else "Moonlight auf dem Thor, Sunshine auf dem PC",
                    fontSize = 14.sp, color = Muted, maxLines = 2)
                hosts?.let { list ->
                    if (list.isEmpty()) Text("Kein PC mit Sunshine im WLAN gefunden.", fontSize = 14.sp, color = Muted)
                    list.take(3).forEach { h -> SettingRow(h.optString("name"), h.optString("address"), Icons.Filled.Computer, value = "Koppeln") { hostMenu = h } }
                }
                Spacer(Modifier.weight(1f))
                val j = job
                when {
                    j != null -> BigButton(if (j.done) (j.error ?: "Am Thor bestätigen") else "Lädt … ${(prog * 100).toInt()} %", Icons.Filled.Download,
                        primary = true, modifier = Modifier.fillMaxWidth()) {}
                    installed -> BigButton("Moonlight starten", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { Apps.launch(ctx, Streaming.MOONLIGHT) }
                    else -> BigButton("Moonlight installieren", Icons.Filled.Download, primary = true, modifier = Modifier.fillMaxWidth()) { install() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(if (searching) "Sucht …" else "PCs suchen", Icons.Filled.Search, modifier = Modifier.weight(1f)) {
                        if (searching) return@BigButton
                        searching = true
                        scope.launch { hosts = withContext(Dispatchers.IO) { runCatching { Streaming.hosts(ctx).objs() }.getOrDefault(emptyList()) }; searching = false }
                    }
                    if (installed && update && job == null) BigButton("Aktualisieren", Icons.Filled.Download, modifier = Modifier.weight(1f)) { install() }
                }
            }
        }
      }
      // the rarer tools: 2×2 tiles with a line that says what they do (no cut-off pills)
      item(key = "tools") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile("Emulatoren einrichten", "Netplay in RetroArch, PPSSPP, Eden …", Icons.Filled.Tune, Modifier.weight(1f)) {
                    scope.launch {
                        BottomStatus.working("Richte Emulatoren ein …", 0.5f)
                        val r = withContext(Dispatchers.IO) { runCatching { Netplay.prepare(ctx) }.getOrElse { JSONObject().put("Fehler", it.message) } }
                        BottomStatus.text = null
                        info = "Emulatoren" to r.keys().asSequence().joinToString("\n") { "$it: ${r.optString(it)}" }.ifEmpty { "Nichts zu tun" }
                    }
                }
                ToolTile(if (live) "Übertragung stoppen" else "Bildschirm am PC", if (live) "läuft gerade" else "Thor-Bild im Browser zeigen", Icons.Filled.Tv, Modifier.weight(1f)) {
                    if (live) { wtf.lennard.thorbro.Screen.stop(); live = false }
                    else {
                        ctx.startActivity(android.content.Intent(ctx, wtf.lennard.thorbro.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(wtf.lennard.thorbro.MainActivity.EXTRA_CAPTURE, true))
                        screenMenu = true
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile("Thor als Controller", "für PC oder einen anderen Thor", Icons.Filled.Gamepad, Modifier.weight(1f)) {
                    ctx.startActivity(android.content.Intent(ctx, GamepadActivity::class.java))
                }
                ToolTile("ThorBro weitergeben", "an einen Thor in der Nähe", Icons.Filled.Share, Modifier.weight(1f)) { direct.menu = "share" }
            }
            if (lobby == null && services.isNotEmpty()) ToolTile("So geht's", "Zusammen spielen in den einzelnen Emulatoren", Icons.Filled.Info, Modifier.fillMaxWidth()) {
                info = "Zusammen spielen" to services.joinToString("\n\n") { "${it.optString("name")}\n${it.optString("how")}" } +
                    "\n\nIn einer Party führt 127.0.0.1 von selbst zum Raum des Leiters – im WLAN, über NetBird oder übers Internet."
            }
        }
      }
    }
    }

    info?.let { (t, x) -> ConsoleMenu(t, x, options = emptyList()) { info = null } }
    hostMenu?.let { h ->
        ConsoleMenu(h.optString("name"), "In Moonlight antippen, dann die PIN am PC unter ${h.optString("web")} eingeben.",
            options = if (installed) listOf("Moonlight öffnen" to { Apps.launch(ctx, Streaming.MOONLIGHT); hostMenu = null }) else emptyList()) { hostMenu = null }
    }
    if (screenMenu) {
        val ip = ipv4().first ?: "<Thor-IP>"
        ConsoleMenu("Am PC im Browser öffnen", "http://$ip:${wtf.lennard.thorbro.ThorService.PORT}\nCode: ${wtf.lennard.thorbro.Prefs.token(ctx)}\n→ Bildschirm",
            mono = true, options = emptyList()) { screenMenu = false }
    }
    DirectDialog(direct)
}


/** one face in the party row: avatar + name below */
@Composable
private fun PartyFace(name: String, label: String, me: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Avatar(name, 52, me = me)
        Text(label, fontSize = 12.sp, color = if (me) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** the empty seat: "+" → friends panel (invite) */
@Composable
private fun InviteFace(modifier: Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(modifier.size(52.dp).hintA("Einladen").fx(fx, CircleShape, lift = 1.1f, onClick = onClick).background(if (fx.active) Accent else Panel),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, "Einladen", Modifier.size(26.dp), tint = if (fx.active) OnAccent else Ink)
        }
        Text("Einladen", fontSize = 12.sp, color = if (fx.active) Ink else Muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** a small tool tile: icon, title, one line what it does */
@Composable
private fun ToolTile(title: String, sub: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    Row(modifier.height(68.dp).fx(fx, RoundedCornerShape(18.dp), lift = 1.03f, onClick = onClick).background(if (fx.active) Soft else Panel)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
