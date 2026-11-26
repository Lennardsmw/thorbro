package wtf.lennard.thorbro.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * The new design (DESIGN.md). Lives next to the old one; UiMode.newDesign picks which Root runs.
 * Pages here never change the old screens – they reuse their logic (GameDetail, GameSaves, SendSheet, FriendsPanel …).
 */

/** the top bar of the new design: areas + friends button (L2) */
@Composable
fun NewTopBar(area: Area) = TopBar(area) { Spacer(Modifier.width(12.dp)); FriendsButton() }

@Composable
fun NewRoot(screen: Screen?) {
    val page = when {
        screen == null -> "Start"
        screen == Screen.GAMES && NewGamesNav.folder.value != null -> "Spiele: " + NewGamesNav.folder.value
        else -> screen.label
    }
    if (wtf.lennard.thorbro.FrameStats.page != page) { wtf.lennard.thorbro.Logs.i("ui", "Seite (neu): $page"); wtf.lennard.thorbro.FrameStats.page = page }
    var detail by remember { mutableStateOf<HomeGame?>(null) }
    val ctx = LocalContext.current
    if (screen == null) {
        HomeScreenNew(showDetail = { detail = it })
    } else {
        val area = Area.of(screen)
        // a new page starts with its head visible and the default hint
        androidx.compose.runtime.LaunchedEffect(screen, NewGamesNav.folder.value) { Header.hidden = false }
        Column(Modifier.fillMaxSize().glassBackground()) {
            androidx.compose.animation.AnimatedVisibility(!Header.hidden) {
                NewTopBar(area)
            }
            androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxWidth()) {
            if (area.pages.size > 1) SideRail(area, screen)
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = if (area.pages.size > 1) 8.dp else 28.dp, end = 28.dp, top = 4.dp, bottom = 4.dp)) {
                when (screen) {
                    Screen.GAMES -> GamesScreenNew()
                    Screen.STORE -> StoreScreenNew()
                    Screen.SYNC -> SyncScreenNew()
                    Screen.NETPLAY, Screen.STREAM -> TogetherScreenNew()
                    Screen.SETTINGS -> SystemScreenNew()
                    Screen.CONTROLLER -> ControllerScreenNew()
                    Screen.DRIVERS -> DriversScreen()
                }
            }
            }
            ButtonHints("A" to (Hints.a ?: "Auswählen"), "B" to "Zurück", "L1 R1" to "Bereich")
        }
    }
    detail?.let { g ->
        val lib by produceState<JSONObject?>(null, g) {
            value = withContext(Dispatchers.IO) {
                runCatching { wtf.lennard.thorbro.Library.status(ctx).optJSONObject("games")?.optJSONObject(g.rom.getString("path")) }.getOrNull()
            }
        }
        GameDetail(g.system, g.rom, g.meta, lib) { detail = null }
    }
}

/**
 * Keys of the new design (DESIGN.md: Tasten), before the old handling:
 * L1/R1 = previous/next area, Select = activity drawer, B = one level up (folder → consoles → Start).
 * false = not handled here; the old handling (controller panels, B → Start) runs next.
 */
fun newNavKey(code: Int, s: Screen?): Boolean {
    // the controller tester and the L+R player order need the shoulder buttons themselves
    val padBusy = s == Screen.CONTROLLER && (Pads.lrMode || ControllerNav.panel.value != null)
    return when (code) {
        KeyEvent.KEYCODE_BUTTON_R1 -> !padBusy && run { Nav.step(1); true }
        KeyEvent.KEYCODE_BUTTON_L1 -> !padBusy && run { Nav.step(-1); true }
        KeyEvent.KEYCODE_BUTTON_SELECT -> { ActivityUi.open = !ActivityUi.open; true }
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> when {
            ActivityUi.open -> { ActivityUi.open = false; true }
            s == Screen.GAMES && NewGamesNav.folder.value != null -> { NewGamesNav.folder.value = null; true }
            s == Screen.STORE && StoreNav.query.value.isNotEmpty() -> { StoreNav.query.value = ""; true }
            s != null && s != Screen.CONTROLLER -> { Nav.go(null); true }
            else -> false
        }
        else -> false
    }
}
