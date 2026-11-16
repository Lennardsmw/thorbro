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
import wtf.lennard.thorbro.Emulators
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
    val systems by produceState<List<Sys>?>(null) {
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
            LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.getString("path") }) { r ->
                    val m = meta?.optJSONObject(r.getString("path")) ?: JSONObject()
                    GameCard(cur.id, r, m) { open = r }
                }
            }
        }
    }
    open?.let { r -> GameDetail(cur.id, r, meta?.optJSONObject(r.getString("path")) ?: JSONObject()) { open = null } }
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
private fun GameCard(sys: String, r: JSONObject, m: JSONObject, onOpen: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        val img = cover(sys, r, m)
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(Bg), contentAlignment = Alignment.Center) {
            if (img != null) Img(img, 360, Modifier.fillMaxSize())
            else Text(title(r, m), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title(r, m), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOf(m.optString("year"), r.optString("extra")).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun GameDetail(sys: String, r: JSONObject, m: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val canPlay = remember(sys) { Emulators.forSystem(ctx, sys) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(16.dp)).background(Panel).padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val img = cover(sys, r, m)
                Box(Modifier.width(200.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(Bg)) {
                    Img(img, 600, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Title(title(r, m))
                    listOf(m.optString("year"), m.optString("developer"), m.optString("genre")).filter { it.isNotEmpty() }.joinToString(" · ")
                        .takeIf { it.isNotEmpty() }?.let { Line(it, Muted) }
                    if (r.optString("extra").isNotEmpty()) Line(r.optString("extra"), Muted)
                    Spacer(Modifier.height(10.dp))
                    m.optString("desc").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    Acts {
                        if (canPlay != null && !r.optBoolean("noBase")) Act("▶ Spielen (${canPlay.name})", primary = true) {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { Emulators.launch(ctx, sys, File(r.getString("path"))) }
                                msg = res.optString("error")
                                if (!res.has("error")) onClose()
                            }
                        }
                        val fav = r.getString("path") in Favorites.paths
                        Act(if (fav) "★ Favorit entfernen" else "☆ Zu Favoriten") { Favorites.toggle(ctx, r.getString("path")) }
                        Act("Zurück", onClick = onClose)
                    }
                    if (msg.isNotEmpty()) Line(msg, Bad)
                }
            }
        }
    }
}

@Composable
private fun ConsoleGrid(list: List<Sys>, pick: (String) -> Unit) {
    val ctx = LocalContext.current
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { first.requestFocus() } }
    LazyVerticalGrid(GridCells.Adaptive(190.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Text("${entries(s).size} Spiele", color = Muted, fontSize = 13.sp)
            }
        }
    }
}
