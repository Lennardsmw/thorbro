package wtf.lennard.thorbro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import wtf.lennard.thorbro.Apps
import wtf.lennard.thorbro.Drivers
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Streaming

internal fun JSONArray?.objs(): List<JSONObject> = (0 until (this?.length() ?: 0)).map { this!!.getJSONObject(it) }

/** Moonlight (PC/Mac → Thor) and scrcpy (Thor → computer), as tiles */
@Composable
fun StreamScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var job by remember { mutableStateOf<Jobs.Job?>(null) }
    var prog by remember { mutableStateOf(0f) }
    var hosts by remember { mutableStateOf<List<JSONObject>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { st = withContext(Dispatchers.IO) { runCatching { Streaming.status(ctx) }.getOrNull() } }
    // the stream state lives in the service; follow it so only one button shows
    var live by remember { mutableStateOf(wtf.lennard.thorbro.Screen.running) }
    var starting by remember { mutableStateOf(wtf.lennard.thorbro.Screen.starting) }
    WhileVisible { while (true) { live = wtf.lennard.thorbro.Screen.running; starting = wtf.lennard.thorbro.Screen.starting; delay(500) } }
    val s = st
    val installed = s?.optBoolean("installed") == true
    val update = s?.optBoolean("updateAvailable") == true

    fun install() {
        val j = Jobs.start("Moonlight installieren") { Streaming.install(ctx, it) }
        job = j
        scope.launch { while (!j.done) { prog = j.progress.toFloat(); delay(300) }; delay(2500); job = null; tick++ }
    }

    TileGrid(first) {
        header("Vom PC spielen", "Moonlight auf dem Thor, Sunshine auf dem Computer")
        item {
            val j = job
            when {
                j != null -> ConsoleTile(if (j.done) (j.error ?: "Am Thor bestätigen") else "Lädt …", "${(prog * 100).toInt()} %",
                    Icons.Filled.Download, progress = prog, modifier = Modifier.firstFocus(first, true)) {}
                installed -> ConsoleTile("Moonlight", "Version ${s?.optString("version")}", Icons.Filled.PlayArrow, accent = true,
                    modifier = Modifier.firstFocus(first, true)) { Apps.launch(ctx, Streaming.MOONLIGHT) }
                else -> ConsoleTile("Moonlight installieren", s?.optString("latest")?.let { "Version $it" } ?: "", Icons.Filled.Download,
                    accent = true, modifier = Modifier.firstFocus(first, true)) { install() }
            }
        }
        if (installed && update && job == null) item { ConsoleTile("Aktualisieren", "auf ${s?.optString("latest")}", Icons.Filled.SystemUpdate) { install() } }
        item {
            ConsoleTile(if (searching) "Sucht …" else "PCs suchen", hosts?.let { "${it.size} gefunden" } ?: "Sunshine im WLAN", Icons.Filled.Search) {
                if (searching) return@ConsoleTile
                searching = true
                scope.launch { hosts = withContext(Dispatchers.IO) { runCatching { Streaming.hosts(ctx).objs() }.getOrDefault(emptyList()) }; searching = false }
            }
        }
        hosts.orEmpty().forEach { h ->
            item { ConsoleTile(h.optString("name"), h.optString("address"), Icons.Filled.Computer) { menu = "host:" + h.toString() } }
        }
        header("Thor am PC zeigen", "im Browser am PC, ohne Installation")
        item {
            if (live) ConsoleTile("Übertragung stoppen", "läuft gerade", Icons.Filled.Tv, accent = true) { wtf.lennard.thorbro.Screen.stop(); live = false }
            else if (starting) ConsoleTile("Startet …", "am Thor bestätigen", Icons.Filled.Tv) {}
            else ConsoleTile("Bildschirm übertragen", "im Browser am PC", Icons.Filled.Tv) {
                ctx.startActivity(android.content.Intent(ctx, wtf.lennard.thorbro.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(wtf.lennard.thorbro.MainActivity.EXTRA_CAPTURE, true))
                starting = true
                menu = "screen"
            }
        }
    }

    when (val m = menu) {
        null -> {}
        "screen" -> {
            val ip = ipv4().first ?: "<Thor-IP>"
            ConsoleMenu("Am PC im Browser öffnen", "http://$ip:${wtf.lennard.thorbro.ThorService.PORT}\nCode: ${wtf.lennard.thorbro.Prefs.token(ctx)}\n→ Bildschirm",
                mono = true, options = emptyList()) { menu = null; tick++ }
        }
        else -> {
            val h = JSONObject(m.removePrefix("host:"))
            ConsoleMenu(h.optString("name"), "In Moonlight antippen, dann die PIN am PC unter ${h.optString("web")} eingeben.",
                options = if (installed) listOf("Moonlight öffnen" to { Apps.launch(ctx, Streaming.MOONLIGHT); menu = null }) else emptyList()) { menu = null }
        }
    }
}

/** GPU drivers (Turnip / Qualcomm) for Eden, Azahar, Dolphin, as tiles */
@Composable
fun DriversScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    var d by remember { mutableStateOf<JSONObject?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val failed = remember { mutableStateMapOf<String, String>() }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tick) { d = withContext(Dispatchers.IO) { runCatching { Drivers.overview(ctx, refresh) }.getOrElse { JSONObject().put("error", it.message) } }; refresh = false }
    val o = d
    if (o == null) { Line("Lade Treiber …", Muted); return }
    val local = o.optJSONArray("downloaded").objs()
    val have = local.map { it.optString("name") }.toSet()
    val emus = o.optJSONArray("emulators").objs().filter { it.optBoolean("installed") }

    TileGrid(first) {
        header("Geladen", listOfNotNull(o.optString("soc"), o.optString("gpu").ifEmpty { null }).joinToString(" · "))
        item { ConsoleTile("Listen neu laden", "von GitHub", Icons.Filled.Refresh, modifier = Modifier.firstFocus(first, true)) { refresh = true; tick++ } }
        local.forEach { f ->
            val m = f.optJSONObject("driver")
            item { ConsoleTile(m?.optString("name")?.ifEmpty { null } ?: f.optString("name"), m?.optString("version") ?: "kein Treiberpaket",
                Icons.Filled.CheckCircle, accent = true) { menu = f } }
        }
        header("Im Emulator einbauen", "Emulator öffnen → GPU-Treiber → Installieren → Ordner ThorBro/GPU-Treiber")
        emus.forEach { e -> item { ConsoleTile(e.optString("name"), "öffnen", Icons.Filled.VideogameAsset) { Apps.launch(ctx, e.optString("pkg")) } } }
        o.optJSONArray("sources").objs().forEach { s ->
            header(s.optString("name"), s.optString("error").ifEmpty { s.optString("note") })
            s.optJSONArray("releases").objs().take(3).flatMap { r -> r.optJSONArray("assets").objs().map { r to it } }.take(6).forEach { (r, a) ->
                val name = a.optString("name")
                item {
                    val p = progress[name]
                    ConsoleTile(name.removeSuffix(".zip").removeSuffix(".adpkg").replace('_', ' '),
                        when { name in have -> "geladen"; failed[name] != null -> failed[name]!!; p != null -> "${(p * 100).toInt()} %"
                            else -> "${r.optString("date")} · ${fmtBytes(a.optLong("size"))}" },
                        if (name in have) Icons.Filled.CheckCircle else Icons.Filled.Memory, progress = p) {
                        if (name in have || p != null) return@ConsoleTile
                        val job = Jobs.start("Treiber laden") { Drivers.download(a.optString("url"), it) }
                        scope.launch {
                            while (!job.done) { progress[name] = job.progress.toFloat(); delay(300) }
                            progress.remove(name); job.error?.let { failed[name] = it }; tick++
                        }
                    }
                }
            }
        }
    }

    menu?.let { f ->
        ConsoleMenu(f.optJSONObject("driver")?.optString("name") ?: f.optString("name"), f.optString("name") + " · " + fmtBytes(f.optLong("size")),
            options = listOf("Löschen" to { Drivers.delete(f.optString("name")); menu = null; tick++ })) { menu = null }
    }
}
