package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Cocoon
import wtf.lennard.thorbro.Background
import wtf.lennard.thorbro.Emulators
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Library
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.mutableIntStateOf
import wtf.lennard.thorbro.Media
import wtf.lennard.thorbro.Roms
import java.io.File

private class Sys(val id: String, val name: String, val roms: List<JSONObject>)

/** Switch: one entry per game (base + updates + DLC); everything else as is */
private fun entries(s: Sys): List<JSONObject> {
    if (s.id != "switch") return s.roms
    return s.roms.groupBy { it.optString("baseId").ifEmpty { it.getString("path") } }.values.map { items ->
        val base = items.firstOrNull { it.optString("switchKind") == "base" } ?: items.first()
        val ups = items.count { it.optString("switchKind") == "update" }
        val dlc = items.count { it.optString("switchKind") == "dlc" }
        JSONObject(base.toString()).put("extra", listOfNotNull(if (ups > 0) "Update" else null, if (dlc > 0) "+$dlc DLC" else null).joinToString(" · "))
            .put("noBase", items.none { it.optString("switchKind") == "base" })
    }.sortedBy { it.getString("name").lowercase() }
}

@Composable
fun GamesScreen() {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val seen = remember { intArrayOf(Background.changes) }
    WhileVisible { while (true) { if (Background.changes != seen[0]) { seen[0] = Background.changes; Roms.invalidate(); reload++ }; kotlinx.coroutines.delay(1000) } }
    val lib by produceState<JSONObject?>(null, reload) { value = withContext(Dispatchers.IO) { runCatching { Library.status(ctx) }.getOrNull() } }
    val systems by produceState<List<Sys>?>(null, reload) {
        value = withContext(Dispatchers.IO) {
            val a = Roms.scan(ctx, false).getJSONArray("systems")
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("id") != "_unassigned" }.map { s ->
                Sys(s.getString("id"), s.getString("name"), s.getJSONArray("roms").let { r -> (0 until r.length()).map { r.getJSONObject(it) } })
            }
        }
    }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    val list = systems
    if (list == null) { Line("Suche Spiele …", Muted); return }
    if (list.isEmpty()) { Line("Keine Spiele gefunden. Lege sie unter ROMs/<Konsole> ab.", Muted); return }
    val selId = GamesNav.system.value
    val cur = list.firstOrNull { it.id == selId }
    if (cur == null) { ConsoleGrid(list) { GamesNav.system.value = it }; return }
    val meta by produceState<JSONObject?>(null, cur.id) { value = withContext(Dispatchers.IO) { runCatching { Media.metaFor(ctx, cur.id, cur.roms) }.getOrNull() } }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Title(cur.name)
            val items = remember(cur.id) { entries(cur) }
            val (gst, gfl) = snapGrid()
            CenteredFocus { LazyVerticalGrid(GridCells.Adaptive(150.dp), state = gst, flingBehavior = gfl, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.getString("path") }) { r ->
                    val m = meta?.optJSONObject(r.getString("path")) ?: JSONObject()
                    GameCard(cur.id, r, m, lib?.optJSONObject("games")?.optJSONObject(r.getString("path"))) { open = r }
                }
            } }
        }
    }
    open?.let { r ->
        // Switch: base, updates and DLC go together
        val group = if (cur.id == "switch" && r.optString("baseId").isNotEmpty()) cur.roms.filter { it.optString("baseId") == r.optString("baseId") } else listOf(r)
        GameDetail(cur.id, r, meta?.optJSONObject(r.getString("path")) ?: JSONObject(),
            lib = lib?.optJSONObject("games")?.optJSONObject(r.getString("path")), group = group,
            onChanged = { Roms.invalidate(); reload++ }) { open = null }
    }
}

@Composable
private fun cover(sys: String, r: JSONObject, m: JSONObject): File? {
    val ctx = LocalContext.current
    val f by produceState<File?>(null, r.getString("path"), m.optString("cover")) {
        value = withContext(Dispatchers.IO) {
            if (sys == "ports") return@withContext wtf.lennard.thorbro.Ports.icon(ctx, r.optString("package"))
            Cocoon.media(ctx, sys, r.getString("path"), "icon")
                ?: m.optString("cover").takeIf { it.isNotEmpty() }?.let { runCatching { Media.cover(ctx, sys, it) }.getOrNull() }
        }
    }
    return f
}

private fun title(r: JSONObject, m: JSONObject) = m.optString("title").ifEmpty { r.getString("name").substringBefore(" (").substringBefore(" [") }

@Composable
private fun GameCard(sys: String, r: JSONObject, m: JSONObject, lib: JSONObject?, onOpen: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        val img = cover(sys, r, m)
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(Bg), contentAlignment = Alignment.Center) {
            // every cover fills the same 3:4 frame (cropped), app icons of ports sit centered instead
            if (img != null && sys == "ports") Img(img, 360, Modifier.fillMaxSize(0.6f))
            else if (img != null) Img(img, 360, Modifier.fillMaxSize(), ContentScale.Crop)
            else Text(title(r, m), color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(8.dp))
            if (lib != null) Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(Icons.Filled.Cloud, lib.optBoolean("inRomm"))
                if (lib.optString("save") != "none") Badge(Icons.Filled.Save, lib.optString("save") == "synced")
            }
        }
        Spacer(Modifier.height(6.dp))
        // fixed text block: always two title lines and one info line, so all cards are the same height
        Text(title(r, m), fontSize = 14.sp, lineHeight = 18.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOf(fmtBytes(r.optLong("size")), m.optString("year"), r.optString("extra")).filter { it.isNotEmpty() }.joinToString(" · ")
        Text(sub.ifEmpty { " " }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GameDetail(sys: String, r: JSONObject, m: JSONObject, lib: JSONObject? = null, group: List<JSONObject> = listOf(r),
               onChanged: () -> Unit = {}, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val canPlay = remember(sys) { Emulators.forSystem(ctx, sys) }
    var confirm by remember { mutableStateOf(false) }
    var savesOpen by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Jobs.Job?>(null) }
    val start = rememberPlay(onError = { msg = it }, onStarted = onClose)
    val size = group.sumOf { it.optLong("size") }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { first.requestFocus() } }
    job?.let { j ->
        LaunchedEffect(j) {
            while (!j.done) { msg = "${j.message} · ${(j.progress * 100).toInt()} %"; kotlinx.coroutines.delay(400) }
            if (j.error != null) { msg = j.error!!; job = null } else { onChanged(); onClose() }
        }
    }
    // single-game upload to RomM: picks up a running one for this game (dialog reopened, started from the web panel)
    val paths = remember(group) { group.map { it.getString("path") }.toSet() }
    var upJob by remember { mutableStateOf(wtf.lennard.thorbro.RommSync.current?.takeIf { !it.done && it.file in paths }) }
    var upLabel by remember { mutableStateOf("") }
    var uploaded by remember(lib) { mutableStateOf(lib?.optBoolean("uploaded") == true) }
    upJob?.let { j ->
        LaunchedEffect(j) {
            while (!j.done) {
                upLabel = "Lädt hoch … ${(j.progress * 100).toInt()} %"
                msg = listOfNotNull(j.message.ifEmpty { null }, j.transfer()).joinToString(" · ")
                kotlinx.coroutines.delay(500)
            }
            val res = j.result
            msg = when {
                j.error != null -> j.error!!
                (res?.optJSONArray("errors")?.length() ?: 0) > 0 -> res!!.getJSONArray("errors").getString(0)
                else -> { uploaded = true; onChanged(); "In RomM hochgeladen – erscheint dort nach dem nächsten Scan" }
            }
            upJob = null
        }
    }
    val cover = cover(sys, r, m)
    val hero by produceState<File?>(null, r.getString("path")) {
        value = withContext(Dispatchers.IO) { if (sys == "ports") null else runCatching { Cocoon.media(ctx, sys, r.getString("path"), "hero") }.getOrNull() }
    }
    val sysIcon by produceState<File?>(null, sys) { value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, sys) }.getOrNull() } }
    val fav = r.getString("path") in Favorites.paths
    val facts = listOf(m.optString("year"), m.optString("developer"), m.optString("genre"), r.optString("extra")).filter { it.isNotEmpty() }

    @Composable
    fun Chips() = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(fmtBytes(size))
        if (lib != null) Chip(if (lib.optBoolean("inRomm")) "☁ in RomM" else "☁ nicht in RomM", lib.optBoolean("inRomm"))
        when (lib?.optString("save")) {
            "synced" -> Chip("💾 Spielstand in RomM", true); "pending" -> Chip("💾 Spielstand geändert"); "local" -> Chip("💾 nur auf dem Thor")
        }
    }

    @Composable
    fun Buttons(stacked: Boolean) {
        val play: (() -> Unit)? = if (canPlay != null && !r.optBoolean("noBase")) ({ start(sys, File(r.getString("path"))) }) else null
        val list = buildList<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> {
            play?.let { add(Triple("Spielen", Icons.Filled.PlayArrow, it)) }
            if (sys != "ports" || lib != null) add(Triple("Spielstände", Icons.Filled.Save, { savesOpen = true }))
            add(Triple(if (fav) "Favorit ✓" else "Favorit", if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, { Favorites.toggle(ctx, r.getString("path")) }))
            val queued = Background.isQueued(r.getString("path"))
            if (sys != "ports" && lib != null && !queued) add(Triple(if (lib.optBoolean("inRomm")) "Auslagern" else "Hochladen + auslagern", Icons.Filled.CloudUpload, { confirm = true }))
            if (queued) add(Triple("Wird ausgelagert …", Icons.Filled.CloudUpload, {}))
            if (sys != "ports" && lib != null && !lib.optBoolean("inRomm") && !queued) when {
                upJob != null -> add(Triple(upLabel.ifEmpty { "Lädt hoch …" }, Icons.Filled.CloudUpload, {}))
                uploaded -> add(Triple("In RomM hochgeladen ✓", Icons.Filled.CloudUpload, {}))
                else -> add(Triple("Zu RomM hochladen", Icons.Filled.CloudUpload, {
                    msg = "Verbinde mit RomM …"
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { wtf.lennard.thorbro.RommSync.uploadGame(ctx, paths) } }
                            .onSuccess { j -> if (j.name == "Zu RomM hochladen") upJob = j else msg = "RomM-Abgleich läuft schon – Fortschritt oben rechts" }
                            .onFailure { msg = it.message ?: "Fehler" }
                    }
                }))
            }
            add(Triple("Zurück", Icons.Filled.ArrowBack, onClose))
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.chunked(2).forEachIndexed { row, pair -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { i, (l, ic, a) ->
                    BigButton(l, ic, primary = row == 0 && i == 0 && play != null, modifier = Modifier.weight(1f).firstFocus(first, row == 0 && i == 0), onClick = a)
                }
            } }
        } else Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            list.forEachIndexed { i, (l, ic, a) ->
                BigButton(l, ic, primary = i == 0 && play != null, modifier = (if (i == 0 && play != null) Modifier.width(220.dp) else Modifier).firstFocus(first, i == 0), onClick = a)
            }
        }
    }

    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
            // backdrop: Cocoon hero art, else the cover blurred
            val back = hero ?: cover
            if (back != null) Img(back, 1280, Modifier.fillMaxSize().then(if (hero == null) Modifier.blur(40.dp) else Modifier), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color(0xF2050608), 0.55f to Color(0xCC050608), 1f to Color(0x66050608))))
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xE6050608))))
            val boxW = maxWidth
            val wide = maxWidth / maxHeight > 1.3f   // top screen 16:9; the bottom screen is almost square
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Box(Modifier.fillMaxHeight(0.82f).aspectRatio(3f / 4f).clip(RoundedCornerShape(18.dp)).background(Panel)) {
                        Img(cover, 900, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sysIcon != null) { Img(sysIcon, 96, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)) }
                            Text(facts.joinToString("  ·  "), color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(title(r, m), fontSize = 44.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 48.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
                        Chips()
                        m.optString("desc").takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 16.sp, color = Color(0xD9FFFFFF), lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 16.dp))
                        }
                        Spacer(Modifier.height(28.dp))
                        Buttons(stacked = false)
                        if (msg.isNotEmpty()) Text(msg, color = if (job != null || upJob != null || uploaded) Muted else Bad, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.width(boxW * 0.32f).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(Panel)) {
                            Img(cover, 600, Modifier.fillMaxSize(), ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (sysIcon != null) { Img(sysIcon, 96, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                                Text(facts.take(2).joinToString(" · "), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(title(r, m), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 32.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                            Chips()
                        }
                    }
                    m.optString("desc").takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 14.sp, color = Color(0xD9FFFFFF), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (msg.isNotEmpty()) Text(msg, color = if (job != null || upJob != null || uploaded) Muted else Bad, modifier = Modifier.padding(bottom = 10.dp))
                    Buttons(stacked = true)
                }
            }
        }
    }
    if (savesOpen) GameSaves(sys, r.getString("path"), title(r, m)) { savesOpen = false }
    if (confirm) OffloadConfirm(title(r, m), size, lib?.optBoolean("inRomm") == true, onYes = {
        confirm = false
        Background.offload(ctx, group.map { it.getString("path") }, title(r, m), size)
        onClose()
    }) { confirm = false }
}

@Composable
private fun Chip(text: String, on: Boolean = false) =
    Text(text, fontSize = 13.sp, color = if (on) Color.Black else Color.White, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xE6FFFFFF) else Color(0x33FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp))

@Composable
private fun OffloadConfirm(name: String, size: Long, inRomm: Boolean, onYes: () -> Unit, onClose: () -> Unit) =
    ConsoleMenu("$name auslagern?",
        (if (inRomm) "" else "Wird zuerst zu RomM hochgeladen. ") + "Danach wird es vom Thor gelöscht (${fmtBytes(size)} frei). " +
            "Spielstände bleiben auf dem Thor und kommen nach RomM. Zurück holen geht im Store.",
        options = listOf("Auslagern" to onYes), onClose = onClose)

@Composable
private fun ConsoleGrid(list: List<Sys>, pick: (String) -> Unit) {
    val ctx = LocalContext.current
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { first.requestFocus() } }
    val (cst, cfl) = snapGrid()
    CenteredFocus { LazyVerticalGrid(GridCells.Adaptive(190.dp), state = cst, flingBehavior = cfl, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list.size) { i ->
            val s = list[i]
            FocusCard(if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth(), onClick = { pick(s.id) }) {
                val icon by produceState<File?>(null, s.id) {
                    value = withContext(Dispatchers.IO) { runCatching { Media.icon(ctx, s.id) }.getOrNull() }
                }
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) Img(icon, 200, Modifier.size(90.dp)) else Text(s.name.take(2), fontSize = 40.sp, color = Muted)
                }
                Text(s.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entries(s).size} Spiele · ${fmtBytes(s.roms.sumOf { it.optLong("size") })}", color = Muted, fontSize = 13.sp)
            }
        }
    } }
}
