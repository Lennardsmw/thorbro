package wtf.lennard.thorbro.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Page chrome of the new design that reacts to the content:
 * the hint bar says what A does on the focused element, and the head (tabs, chips) scrolls away with the content.
 */

/**
 * What A does right now ("Öffnen", "Laden", "Spielen" …); null = the page default.
 * Every element with [hintA] that holds the focus is in [held]; the newest one wins. Losing focus or leaving
 * the screen removes it, so the order of focus events does not matter and nothing stays behind.
 */
object Hints {
    private val held = androidx.compose.runtime.mutableStateListOf<Pair<Any, String>>()
    val a: String? get() = held.lastOrNull()?.second
    internal fun hold(key: Any, text: String) { held.removeAll { it.first === key }; held.add(key to text) }
    internal fun drop(key: Any) { held.removeAll { it.first === key } }
}

/** while this element has the focus, the hint bar shows "A [text]" */
fun Modifier.hintA(text: String): Modifier = this.composed(factory = {
    val key = androidx.compose.runtime.remember { Any() }
    val focused = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(focused.value, text) { if (focused.value) Hints.hold(key, text) else Hints.drop(key) }
    DisposableEffect(key) { onDispose { Hints.drop(key) } }
    Modifier.onFocusChanged { focused.value = it.hasFocus }
})

/** request focus once a dialog window can take it: dialogs attach late, a single early request gets lost */
suspend fun androidx.compose.ui.focus.FocusRequester.focusSoon() {
    for (wait in longArrayOf(200, 350, 500)) { kotlinx.coroutines.delay(wait); runCatching { requestFocus() } }
}

/** the head of a page (top bar, sub page chips) is hidden while the content is scrolled down */
object Header {
    var hidden by mutableStateOf(false)
}

/** hide the head while [state] is scrolled past its first item; shown again at the top or when the page goes */
@Composable
fun HeaderFollows(state: LazyListState) {
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex > 0 }.collect { Header.hidden = it } }
    DisposableEffect(state) { onDispose { Header.hidden = false } }
}

@Composable
fun HeaderFollows(state: LazyGridState) {
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex > 0 }.collect { Header.hidden = it } }
    DisposableEffect(state) { onDispose { Header.hidden = false } }
}

/** a small secondary button (e.g. "Listen neu laden"): pill, icon + text, same focus look as everything else */
@Composable
fun SmallButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    androidx.compose.foundation.layout.Row(modifier.height(40.dp).fx(fx, androidx.compose.foundation.shape.RoundedCornerShape(50), lift = 1.05f, onClick = onClick)
        .background(if (fx.active) Accent else Panel).padding(horizontal = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp), tint = if (fx.active) OnAccent else Ink)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        androidx.compose.material3.Text(label, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (fx.active) OnAccent else Ink, maxLines = 1)
    }
}
