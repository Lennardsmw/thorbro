package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import wtf.lennard.thorbro.Pals

/** the friends menu: button top right on every page, or L2 */
object FriendsUi {
    var open by mutableStateOf(false)
    fun toggle() { open = !open }
}

private fun org.json.JSONArray?.list(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/** grey round avatar (own icons come later) */
@Composable
fun Avatar(name: String, size: Int = 40, me: Boolean = false) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0xFF5A5E68)), contentAlignment = Alignment.Center) {
        if (me) Icon(Icons.Filled.Person, null, Modifier.size((size * 0.62).dp), tint = Color(0xFFD5D8DE))
        else Text(name.take(1).uppercase(), fontSize = (size * 0.45).sp, fontWeight = FontWeight.Bold, color = Color(0xFFE9EBEF))
    }
}

/** the round button in the status bar: people icon, a dot when requests wait */
@Composable
fun FriendsButton() {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf(0) }
    WhileVisible { while (true) { waiting = withContext(Dispatchers.IO) { Pals.requests(ctx).size + wtf.lennard.thorbro.Party.invites(ctx).size + wtf.lennard.thorbro.Gifts.waiting(ctx) }; delay(3000) } }
    val fx = rememberFx()
    Box {
        Box(Modifier.size(38.dp).fx(fx, CircleShape, lift = 1.1f) { FriendsUi.open = true }
            .then(if (fx.active) Modifier.background(Accent) else Modifier.background(GlassFill)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Group, "Freunde (L2)", Modifier.size(22.dp), tint = if (fx.active) OnAccent else Ink)
        }
        if (waiting > 0) Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(Color(0xFFE5484D)), contentAlignment = Alignment.Center) {
            Text("$waiting", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun Section(text: String) = Text(text, fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

/** one person in the menu; the whole row is one focus stop */
@Composable
private fun PersonRow(name: String, sub: String, modifier: Modifier = Modifier, trailing: String = "", online: Boolean = false, onClick: () -> Unit) {
    val fx = rememberFx()
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().fx(fx, shape, lift = 1.02f, onClick = onClick).background(if (fx.active) Soft else Card)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(name, 38)
            if (online) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(0xFF3FB950)).border(2.dp, Card, CircleShape))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotEmpty()) Text(trailing, fontSize = 13.sp, color = if (fx.active) Ink else Muted)
    }
}

private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when { m < 2 -> "gerade eben"; m < 60 -> "vor $m Min."; m < 48 * 60 -> "vor ${m / 60} Std."; else -> "vor ${m / 1440} Tagen" }
}

/** the panel on the right: me, party mode, requests, friends, nearby; Y adds friends */
@Composable
fun FriendsPanel() {
    if (!FriendsUi.open) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var gifts by remember { mutableStateOf<JSONObject?>(null) }
    var gift by remember { mutableStateOf<JSONObject?>(null) }
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    fun close() { FriendsUi.open = false }
    fun refresh() = scope.launch {
        st = withContext(Dispatchers.IO) { Pals.status(ctx) }
        gifts = withContext(Dispatchers.IO) { runCatching { wtf.lennard.thorbro.Gifts.status(ctx) }.getOrNull() }
    }
    fun act(work: () -> String?) = scope.launch {
        val err = withContext(Dispatchers.IO) { runCatching { work() }.getOrElse { it.message } }
        msg = err ?: ""; refresh()
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Pals.importDownloads(ctx) }
        refresh(); delay(200); runCatching { first.requestFocus() }
    }
    // nearby: same WLAN / hotspot, looked for again every 15 s while the menu is open
    LaunchedEffect(Unit) { while (true) { withContext(Dispatchers.IO) { runCatching { Pals.scanNearby(ctx) } }; refresh(); delay(15_000) } }
    LaunchedEffect(Unit) {
        var v = Pals.version
        while (true) {
            delay(1000)
            val loading = gifts?.optJSONArray("incoming").list().any { it.optString("state") == "loading" }
            if (Pals.version != v || loading) { v = Pals.version; refresh() }
        }
    }

    DialogOpen()
    Dialog(::close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || add || rename || pick != null || gift != null) return@onPreviewKeyEvent false
            when (e.key) {
                Key.ButtonY -> { add = true; true }
                Key.ButtonL2 -> { close(); true }
                else -> false
            }
        }, contentAlignment = Alignment.TopEnd) {
            val s = st
            Column(Modifier.padding(14.dp).width(430.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Sheet)
                .border(1.dp, GlassLine, RoundedCornerShape(22.dp))) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
                    // me
                    val me = s?.optJSONObject("me")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me?.optString("name") ?: "", 52, me = true)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(me?.optString("name") ?: "…", fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("ID ${me?.optString("id")?.take(6) ?: ""}", fontSize = 12.sp, color = Muted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val open = s?.optBoolean("partyOpen") == true
                        BigButton(if (open) "Party: offen" else "Party: nur Einladung", modifier = Modifier.weight(1f).focusRequester(first)) {
                            act { Pals.setPartyOpen(ctx, !open); null }
                        }
                        BigButton("Name") { rename = true }
                    }
                    if (msg.isNotEmpty()) Text(msg, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

                    // gifts: games and saves from friends, and friends who want a game to a save we sent
                    val inc = gifts?.optJSONArray("incoming").list().filter { it.optString("state") != "done" || System.currentTimeMillis() - it.optLong("at") < 86_400_000 }
                    val wants = gifts?.optJSONArray("outgoing").list().flatMap { o -> o.optJSONArray("to").list().filter { it.optBoolean("want") }.map { o to it } }
                    if (inc.isNotEmpty() || wants.isNotEmpty()) {
                        Section("Für dich")
                        inc.forEach { g -> GiftRow(g, Modifier.padding(bottom = 6.dp)) { gift = g } }
                        wants.forEach { (o, t) ->
                            PersonRow(t.optString("name"), "möchte auch das Spiel: ${o.optString("title")}", Modifier.padding(bottom = 6.dp), "A: schicken") {
                                act { Jobs.start("Spiel nachschicken") { j -> wtf.lennard.thorbro.Gifts.sendWanted(ctx, o.optString("gid"), t.optString("id"), j) }; null }
                            }
                        }
                    }

                    val party = s?.optJSONObject("party")
                    if (party != null) {
                        val mem = party.optJSONArray("members").list()
                        Section(if (party.optBoolean("pending")) "Party – warte auf den Leiter …" else "Party (${mem.size})")
                        mem.forEach { m ->
                            PersonRow(m.optString("name"), listOf(if (m.optBoolean("me")) "du" else "", if (m.optString("id") == party.optString("leader")) "Leiter" else "")
                                .filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(bottom = 6.dp), online = true) {}
                        }
                        BigButton(if (party.optBoolean("leaderMe")) "Party auflösen" else "Party verlassen", modifier = Modifier.fillMaxWidth()) {
                            act { wtf.lennard.thorbro.Party.leave(ctx); null }
                        }
                    }
                    val invs = s?.optJSONArray("invites").list()
                    if (invs.isNotEmpty()) {
                        Section("Party-Einladungen")
                        invs.forEach { i -> PersonRow(i.optString("name"), "lädt dich in die Party ein", Modifier.padding(bottom = 6.dp), "A: ansehen") { pick = i.put("kind", "invite") } }
                    }
                    val req = s?.optJSONArray("requests").list()
                    if (req.isNotEmpty()) {
                        Section("Anfragen")
                        req.forEach { r -> PersonRow(r.optString("name"), "möchte dein Freund sein", Modifier.padding(bottom = 6.dp), "A: ansehen") { pick = r.put("kind", "request") } }
                    }
                    val friends = s?.optJSONArray("friends").list()
                    Section("Freunde (${friends.size})")
                    if (friends.isEmpty()) Text("Noch keine. Mit Y fügst du Freunde hinzu – per Code oder LocalSend.", fontSize = 14.sp, color = Muted)
                    friends.forEach { f ->
                        val sub = when {
                            f.optBoolean("online") && f.optBoolean("joinable") -> "online · Party offen"
                            f.optBoolean("online") && f.optBoolean("inParty") -> "online · in einer Party"
                            f.optBoolean("online") -> "online"
                            else -> listOf(f.optString("app"), "zuletzt ${ago(f.optLong("seen"))}").filter { it.isNotBlank() && it != "zuletzt " }.joinToString(" · ")
                        }
                        PersonRow(f.optString("name"), sub, Modifier.padding(bottom = 6.dp), if (f.optBoolean("joinable")) "A: beitreten" else "",
                            online = f.optBoolean("online")) { pick = f.put("kind", "friend") }
                    }
                    val near = s?.optJSONArray("nearby").list()
                    if (near.isNotEmpty()) {
                        Section("In der Nähe")
                        near.forEach { n ->
                            PersonRow(n.optString("name"), "${n.optString("app", "ThorBro")} · gleiches Netz", Modifier.padding(bottom = 6.dp),
                                if (n.optBoolean("asked")) "angefragt" else "A: hinzufügen") {
                                if (!n.optBoolean("asked")) act { Pals.addNearby(ctx, n.optString("id")) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint("Y", "Freunde hinzufügen") { add = true }
                    Hint("B", "Schließen") { close() }
                }
            }
        }
    }
    if (add) AddFriendPopup(onClose = { add = false; refresh() })
    gift?.let { g -> GiftMenu(g, onAct = { work -> act(work) }) { gift = null; refresh() } }
    if (rename) NameDialog(st?.optJSONObject("me")?.optString("name") ?: "", onClose = { rename = false }) { n -> act { Pals.setName(ctx, n); null }; rename = false }
    pick?.let { p ->
        val name = p.optString("name")
        if (p.optString("kind") == "request") ConsoleMenu(name, "möchte dein Freund sein", options = listOf(
            "Annehmen" to { act { if (Pals.accept(ctx, p.optString("id"))) null else "Anfrage ist weg" }; pick = null },
            "Ablehnen" to { act { Pals.decline(ctx, p.optString("id")); null }; pick = null },
        )) { pick = null }
        else if (p.optString("kind") == "invite") ConsoleMenu(name, "lädt dich in die Party ein (${p.optInt("members")} drin)", options = listOf(
            "Beitreten" to { act { wtf.lennard.thorbro.Party.join(ctx, p.optString("id"), p.optString("party")) }; pick = null },
            "Ablehnen" to { act { wtf.lennard.thorbro.Party.decline(ctx, p.optString("id")); null }; pick = null },
        )) { pick = null }
        else ConsoleMenu(name, if (p.optBoolean("online")) "online" else "Freund seit ${ago(p.optLong("since"))}", options = listOfNotNull(
            ("Party beitreten" to { act { wtf.lennard.thorbro.Party.joinOpen(ctx, p.optString("id")) }; pick = null }).takeIf { p.optBoolean("joinable") },
            "Zur Party einladen" to { act { wtf.lennard.thorbro.Party.invite(ctx, p.optString("id"))?.let { it } }; msg = ""; pick = null },
            "Entfernen" to { act { Pals.remove(ctx, p.optString("id")); null }; pick = null },
        )) { pick = null }
    }
}

@Composable
private fun Hint(btn: String, what: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(12.dp))
        .then(Modifier.background(Color.Transparent))) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (btn == "Y") Color(0xFF3FB950) else Color.White), contentAlignment = Alignment.Center) {
            Text(btn, color = if (btn == "Y") Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.material3.TextButton(onClick) { Text(what, fontSize = 14.sp, color = Ink) }
    }
}

/** Y in the menu: enter someone's code, or show / share your own. Scrolls, keeps a margin to the screen edge. */
@Composable
private fun AddFriendPopup(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<JSONObject?>(null) }
    var pad by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    // the own code is shown at once and stays valid while the popup is open (refreshed every minute)
    LaunchedEffect(Unit) { while (true) { code = withContext(Dispatchers.IO) { runCatching { Pals.myCode(ctx) }.getOrNull() }; delay(60_000) } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.width(820.dp).heightIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(Sheet)
                .border(1.dp, GlassLine, RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Freunde hinzufügen", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text("Code eines Freundes", fontSize = 15.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                BigButton(if (busy) "Suche …" else "Code eingeben", primary = true, enabled = !busy, modifier = Modifier.focusRequester(first)) { pad = true }
                if (msg.isNotEmpty()) Text(msg, color = if (ok) Ok else Bad, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))

                Spacer(Modifier.height(22.dp))
                Text("Dein Code – der andere drückt ihn bei sich", fontSize = 15.sp, color = Muted)
                Spacer(Modifier.height(10.dp))
                val c = code?.optString("code")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
                if (c.isNotEmpty()) CodeRow(c, 62) else Text("…", fontSize = 20.sp)
                Text(if (c.isEmpty()) "" else "gilt noch ${code?.optLong("minutes")} Min. · Zifferncode ${code?.optString("digits")}", fontSize = 13.sp, color = Muted,
                    modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Neuer Code") { scope.launch { code = withContext(Dispatchers.IO) { Pals.myCode(ctx, fresh = true) } } }
                    BigButton("Per LocalSend teilen") { runCatching { ctx.startActivity(Pals.shareIntent(ctx)) }.onFailure { msg = it.message ?: "Teilen geht nicht"; ok = false } }
                }
                Text("Teilen schickt eine Datei „Name.thorfriend“. Wer sie in den Download-Ordner bekommt, ist beim nächsten Öffnen des Freunde-Menüs mit dir befreundet.",
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(16.dp))
                BigButton("Schließen") { onClose() }
            }
        }
    }
    if (pad) CodePad(onClose = { pad = false }) { entered ->
        pad = false; busy = true; msg = "Suche den Code …"; ok = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Pals.redeem(ctx, entered) } }
            busy = false
            r.onSuccess { msg = "$it ist jetzt dein Freund"; ok = true }.onFailure { msg = it.message ?: "Fehler"; ok = false }
        }
    }
}

@Composable
private fun NameDialog(current: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val f = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { f.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(22.dp)).background(Sheet).padding(24.dp)) {
            Text("Dein Name", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("So sehen dich deine Freunde.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth().focusRequester(f), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }))
            Acts {
                BigButton("Speichern", primary = true, enabled = name.isNotBlank()) { onSave(name) }
                BigButton("Abbrechen") { onClose() }
            }
        }
    }
}

private fun giftWhat(kind: String) = when (kind) { "save" -> "einen Spielstand"; "both" -> "ein Spiel mit Spielstand"; else -> "ein Spiel" }
private val wayLabel = mapOf("lan" to "lokal", "romm" to "RomM", "tunnel" to "Tunnel")

/** one gift under "Für dich": who, what, and where it stands (with a bar while it loads) */
@Composable
private fun GiftRow(g: JSONObject, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    val shape = RoundedCornerShape(14.dp)
    val from = g.optJSONObject("from")?.optString("name") ?: "Freund"
    val state = g.optString("state")
    val live = g.optJSONObject("live")
    Column(modifier.fillMaxWidth().fx(fx, shape, lift = 1.02f, onClick = onClick).background(if (fx.active) Soft else Card)
        .padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(from, 38)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(when (state) {
                    "loading" -> "Lädt: ${g.optString("title")}"
                    "done" -> "${g.optString("title")} ist da"
                    else -> "$from schickt dir ${giftWhat(g.optString("kind"))}"
                }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val size = g.optLong("size").takeIf { it > 0 } ?: g.optJSONObject("save")?.optLong("size") ?: 0L
                Text(when (state) {
                    "loading" -> "von $from" + (live?.optString("way")?.let { wayLabel[it] }?.let { " · $it" } ?: "") +
                        (live?.takeIf { it.optLong("total") > 0 }?.let { " · ${fmtBytes(it.optLong("done"))} von ${fmtBytes(it.optLong("total"))}" } ?: "")
                    "failed", "wifi" -> g.optString("error")
                    "expired" -> "abgelaufen"
                    "done" -> "von $from · " + (g.optJSONObject("result")?.optString("save")?.takeIf { it.isNotEmpty() }?.let { "Spielstand $it" } ?: "fertig")
                    else -> listOf(g.optString("title"), if (size > 0) fmtBytes(size) else "", if (g.optString("kind") == "both") "mit Spielstand" else "",
                        if (g.optBoolean("missing")) "Spiel fehlt" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                }, fontSize = 12.sp, color = if (state == "failed") Bad else Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(when (state) { "new" -> "neu"; "loading" -> live?.let { l -> if (l.optLong("total") > 0) "${l.optLong("done") * 100 / l.optLong("total")} %" else "…" } ?: "…"
                "failed", "wifi" -> "A: nochmal"; else -> "" }, fontSize = 13.sp, color = if (state == "new") Ok else if (fx.active) Ink else Muted)
        }
        if (state == "loading" && live != null && live.optLong("total") > 0) {
            val p = live.optLong("done").toFloat() / live.optLong("total")
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Ink))
            }
        }
    }
}

/** what to do with a gift: load (game), take over or keep as backup (save), decline */
@Composable
private fun GiftMenu(g: JSONObject, onAct: (() -> String?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val gid = g.optString("gid")
    val from = g.optJSONObject("from")?.optString("name") ?: "Freund"
    val kind = g.optString("kind")
    val state = g.optString("state")
    val ways = g.optJSONObject("ways") ?: JSONObject()
    val probe = g.optJSONObject("probe")
    fun way(id: String, allowed: Boolean) = "${wayLabel[id]} " + when {
        !allowed -> "–"
        probe?.has(id) == true -> "${probe.optString(id)} MB/s"
        else -> "✓"
    }
    val mode = wtf.lennard.thorbro.DataSaver.mode(ctx)
    val local = mode == wtf.lennard.thorbro.DataSaver.Mode.LOCAL
    val text = buildString {
        append(g.optString("title"))
        val size = g.optLong("size")
        if (size > 0) append(" · ${fmtBytes(size)}")
        g.optJSONObject("save")?.let { s -> append(if (kind == "save") " · ${fmtBytes(s.optLong("size"))}" else " · mit Spielstand") }
        if (kind != "save") append("\nWege: " + listOf(way("lan", ways.optBoolean("lan")), way("romm", ways.optBoolean("romm") && !local),
            way("tunnel", ways.optBoolean("tunnel") && !local)).joinToString(" · ") + (if (local) " (Nur lokal ist an)" else "") +
            "\nThorBro testet beim Annehmen alle Wege kurz und nimmt den schnellsten.")
        else append("\nÜbernehmen sichert deinen jetzigen Stand vorher als Backup.")
        if (g.optBoolean("missing")) append("\nDas Spiel fehlt auf diesem Thor.")
        if (state == "failed" || state == "wifi") append("\n" + g.optString("error"))
    }
    val start: (String) -> Unit = { how -> onAct { wtf.lennard.thorbro.Gifts.accept(ctx, gid, how); null }; onClose() }
    val options = buildList<Pair<String, () -> Unit>> {
        when {
            state == "loading" -> {}
            state == "done" || state == "expired" -> {}
            kind == "save" && g.optBoolean("missing") && !g.optBoolean("asked") ->
                add("Spiel auch anfragen" to { onAct { wtf.lennard.thorbro.Gifts.askGame(ctx, gid) }; onClose() })
            kind == "save" -> {
                add("Übernehmen" to { start("take") })
                add("Als Backup ablegen" to { start("backup") })
            }
            else -> add((if (state == "failed" || state == "wifi") "Weiter laden" else "Annehmen & laden") to { start("load") })
        }
        if (state == "new" || state == "failed" || state == "wifi") {
            add("Später" to onClose)
            add("Ablehnen" to { onAct { wtf.lennard.thorbro.Gifts.decline(ctx, gid); null }; onClose() })
        }
    }
    ConsoleMenu(if (state == "done") "${g.optString("title")} ist da" else "$from schickt dir ${giftWhat(kind)}", text, options = options, onClose = onClose)
}
