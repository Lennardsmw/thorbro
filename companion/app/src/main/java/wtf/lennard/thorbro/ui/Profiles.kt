package wtf.lennard.thorbro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Profiles
import wtf.lennard.thorbro.SharedSaves
import java.io.File

private fun org.json.JSONArray?.ints(): List<Int> = (0 until (this?.length() ?: 0)).map { this!!.getInt(it) }

private fun color(p: JSONObject) = runCatching { Color(android.graphics.Color.parseColor(p.optString("color"))) }.getOrDefault(Color(0xFF4F8EF7))

/** round avatar with the first letter, like the Switch user icons */
@Composable
fun ProfileDot(p: JSONObject, size: Int) = Box(Modifier.size(size.dp).clip(CircleShape).background(color(p)), contentAlignment = Alignment.Center) {
    Text(p.optString("name").take(1).uppercase(), fontSize = (size * 0.42).sp, fontWeight = FontWeight.Bold, color = Color.White)
}

/** "Wer spielt?": big avatars in a row; A picks, B cancels */
@Composable
fun ProfilePicker(profiles: List<JSONObject>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.fillMaxSize().background(Color(0xF2050608)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Wer spielt?", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEachIndexed { i, p ->
                    val src = remember { MutableInteractionSource() }
                    val focused by src.collectIsFocusedAsState()
                    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "pick")
                    Column(Modifier.firstFocus(first, i == 0).scale(scale).clip(RoundedCornerShape(20.dp))
                        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable(interactionSource = src, indication = null) { onPick(p.getString("id")) }.focusable(interactionSource = src)
                        .padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ProfileDot(p, 96)
                        Spacer(Modifier.height(10.dp))
                        Text(p.optString("name"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(when { p.optBoolean("active") -> "zuletzt gespielt"; p.optBoolean("hasSave") -> "hat Spielstand"; else -> "neu" },
                            fontSize = 13.sp, color = Muted)
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            ButtonHints("A" to "Auswählen", "B" to "Abbrechen")
        }
    }
}

/**
 * Start function for games: asks "Wer spielt?" from the second profile on, swaps the save, starts the emulator.
 * The picker is drawn where this is called.
 */
@Composable
fun rememberPlay(onError: (String) -> Unit, onStarted: () -> Unit = {}): (String, File) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Triple<String, File, List<JSONObject>>?>(null) }
    fun go(sys: String, rom: File, profile: String?) = scope.launch {
        val r = withContext(Dispatchers.IO) { Profiles.launch(ctx, sys, rom, null, profile) }
        if (r.has("error")) onError(r.optString("error")) else { onError(""); onStarted() }
    }
    ask?.let { (sys, rom, list) -> ProfilePicker(list, onPick = { ask = null; go(sys, rom, it) }) { ask = null } }
    return { sys, rom ->
        if (Profiles.supports(sys) && Profiles.needsPicker(ctx)) scope.launch {
            ask = Triple(sys, rom, withContext(Dispatchers.IO) { Profiles.forGame(ctx, rom.path).objs() })
        } else go(sys, rom, null)
    }
}

private fun day(t: String) = runCatching {
    java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.GERMANY).format(java.util.Date(java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()))
}.getOrDefault(t.take(16).replace('T', ' '))

/** a game's saves: whose is live, own saves in RomM (share), shared ones from others (install for a profile) */
@Composable
fun GameSaves(sys: String, path: String, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<JSONObject?>(null) }
    var ownMenu by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tick) { data = withContext(Dispatchers.IO) { runCatching { SharedSaves.forGame(ctx, sys, path) }.getOrElse { JSONObject().put("error", it.message) } } }
    val first = remember { FocusRequester() }
    LaunchedEffect(data != null) { kotlinx.coroutines.delay(200); runCatching { first.requestFocus() } }
    fun act(label: String, block: () -> JSONObject) {
        if (busy) return
        busy = true; msg = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block).getOrElse { JSONObject().put("error", it.message) } }
            busy = false; msg = r.optString("error").ifEmpty { r.optString("done", "Fertig") }; tick++
        }
    }
    val d = data
    val profiles = d?.optJSONArray("profiles").objs()
    val supported = d?.optBoolean("supported") == true

    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.fillMaxSize().background(Color(0xFF07080A)).padding(horizontal = 40.dp, vertical = 28.dp)) {
            Text("Spielstände", fontSize = 15.sp, color = Muted)
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (msg.isNotEmpty()) Text(msg, color = if (busy) Muted else Ok, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (d == null) item { Text("Lade …", color = Muted) }
                if (d != null && supported) {
                    item { Text("Wer spielt mit welchem Stand", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            profiles.forEachIndexed { i, p ->
                                FocusCard(Modifier.width(170.dp).firstFocus(first, i == 0), onClick = {
                                    if (!p.optBoolean("active")) act("Tausche Spielstand …") {
                                        Profiles.activate(ctx, path, p.getString("id")).also { if (!it.has("error")) it.put("done", "Spielstand von ${it.optString("profile")} ist aktiv") }
                                    }
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProfileDot(p, 40); Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(p.optString("name"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (p.optBoolean("active")) "aktiv" else if (p.optBoolean("hasSave")) "hat Spielstand" else "kein Spielstand",
                                                fontSize = 12.sp, color = if (p.optBoolean("active")) Ok else Muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (d != null && !supported) item { Text("Switch-Spielstände liegen in Eden, das eigene Profile hat.", color = Muted) }
                when {
                    d == null -> {}
                    !d.optBoolean("connected") -> item { Text("RomM nicht verbunden – geteilte Spielstände gibt es nur mit RomM.", color = Muted) }
                    d.has("error") -> item { Text(d.optString("error"), color = Bad) }
                    !d.optBoolean("inRomm") -> item { Text("Das Spiel ist nicht in RomM.", color = Muted) }
                    else -> {
                        val own = d.optJSONArray("own").objs()
                        val shared = d.optJSONArray("shared").objs()
                        item { Text("Geteilte Spielstände", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                        if (shared.isEmpty()) item { Text("Niemand hat für dieses Spiel einen Spielstand geteilt.", color = Muted) }
                        shared.forEach { s ->
                            item {
                                SaveRow(Icons.Filled.Download, s.optString("user") + (s.optString("slot").takeIf { !s.isNull("slot") && it.isNotEmpty() }?.let { " · $it" } ?: "") + devLabel(s, d),
                                    "${s.optString("name")} · ${day(s.optString("updated"))} · ${fmtBytes(s.optLong("size"))}", if (supported) "Installieren" else null) { installing = s }
                            }
                        }
                        item { Text("Deine Spielstände in RomM", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                        if (own.isEmpty()) item { Text("Noch keiner – nach dem ersten Speichern lädt ThorBro ihn hoch.", color = Muted) }
                        own.forEach { s ->
                            item {
                                val pub = s.optBoolean("public")
                                SaveRow(if (pub) Icons.Filled.Public else Icons.Filled.PublicOff, s.optString("profile") + devLabel(s, d),
                                    "${day(s.optString("updated"))} · ${fmtBytes(s.optLong("size"))}" + (if (pub) " · geteilt" else ""),
                                    "Optionen") { ownMenu = s }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ButtonHints("A" to "Auswählen", "B" to "Zurück")
        }
    }
    ownMenu?.let { s ->
        val pub = s.optBoolean("public")
        ConsoleMenu(s.optString("profile"), "${day(s.optString("updated"))} · ${fmtBytes(s.optLong("size"))}", options = buildList {
            add((if (pub) "Nicht mehr teilen" else "Teilen") to {
                ownMenu = null
                act(if (pub) "Nehme zurück …" else "Teile …") {
                    SharedSaves.share(ctx, s.optJSONArray("ids").ints(), !pub).also { if (!it.has("error")) it.put("done", if (pub) "Nicht mehr geteilt" else "Geteilt – alle RomM-Nutzer sehen ihn") }
                }
            })
            if (supported) add("Für ein Profil installieren" to { ownMenu = null; installing = s })
        }) { ownMenu = null }
    }
    installing?.let { s ->
        ConsoleMenu("Für wen installieren?", "Der jetzige Spielstand des Profils wird vorher gesichert.",
            options = profiles.map { p ->
                p.optString("name") to {
                    installing = null
                    act("Installiere …") {
                        SharedSaves.install(ctx, sys, path, s.optJSONArray("ids").ints(), p.getString("id")).also { if (!it.has("error")) it.put("done", "Installiert für ${it.optString("profile")}") }
                    }
                }
            }) { installing = null }
    }
}

/** " · von MacBook-Pro" / " · von diesem Gerät"; empty for older uploads without a device */
private fun devLabel(s: JSONObject, d: JSONObject): String {
    val dev = if (s.isNull("device")) "" else s.optString("device")
    return when {
        dev.isEmpty() -> ""
        dev == d.optString("thisDevice") -> " · von diesem Gerät"
        else -> " · von $dev"
    }
}

@Composable
private fun SaveRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, action: String?, onClick: () -> Unit) =
    FocusCard(Modifier.fillMaxWidth(), onClick = if (action != null) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp)); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 13.sp, color = Muted, maxLines = 1)
            }
            if (action != null) Text(action, fontSize = 14.sp, color = Accent)
        }
    }

/** enter a profile name (on-screen keyboard) */
@Composable
fun ProfileNameDialog(title: String, initial: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF0141519)).padding(24.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().firstFocus(first, true))
            Spacer(Modifier.height(16.dp))
            BigButton("Speichern", primary = true, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { onDone(name.trim()) }
            Spacer(Modifier.height(10.dp))
            BigButton("Zurück", modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

/** profile tiles for the Sync screen: one per profile (rename/delete), plus "Neues Profil" */
fun androidx.compose.foundation.lazy.grid.LazyGridScope.profileTiles(profiles: List<JSONObject>, onOpen: (JSONObject?) -> Unit) {
    header("Profile", "Wie bei der Switch: jedes Profil hat pro Spiel seinen eigenen Spielstand")
    profiles.forEach { p ->
        item { ConsoleTile(p.optString("name"), if (p.optString("id") == Profiles.MAIN) "erstes Profil" else "Slot „${p.optString("slot")}“ in RomM", badges = {
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { ProfileDot(p, 34) }
        }) { onOpen(p) } }
    }
    if (profiles.size < 8) item { ConsoleTile("Neues Profil", "", Icons.Filled.Add) { onOpen(null) } }
}

/** the dialogs behind [profileTiles] */
@Composable
fun ProfileDialogs(open: JSONObject?, adding: Boolean, onDone: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    fun act(block: () -> JSONObject) = scope.launch {
        val r = withContext(Dispatchers.IO) { block() }
        onDone(r.optString("error").ifEmpty { "Gespeichert" })
    }
    if (adding) ProfileNameDialog("Neues Profil", "", onDone = { n -> onClose(); act { Profiles.add(ctx, n) } }, onClose = onClose)
    if (open != null && renaming == null && deleting == null) {
        ConsoleMenu(open.optString("name"), options = buildList {
            add("Umbenennen" to { renaming = open })
            if (open.optString("id") != Profiles.MAIN) add("Löschen" to { deleting = open })
        }, onClose = onClose)
    }
    renaming?.let { p -> ProfileNameDialog("Umbenennen", p.optString("name"), onDone = { n -> renaming = null; onClose(); act { Profiles.rename(ctx, p.getString("id"), n) } }) { renaming = null } }
    deleting?.let { p ->
        ConsoleMenu("${p.optString("name")} löschen?", "Seine Spielstände bleiben als Sicherung und in RomM (Slot „${p.optString("slot")}“).",
            options = listOf("Löschen" to { deleting = null; onClose(); act { Profiles.remove(ctx, p.getString("id")) }; Unit })) { deleting = null }
    }
}
