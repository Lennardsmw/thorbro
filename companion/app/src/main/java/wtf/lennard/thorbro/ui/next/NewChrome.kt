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
import androidx.compose.ui.focus.onFocusChanged

/*
 * Page chrome of the new design that reacts to the content:
 * the hint bar says what A does on the focused element, and the head (tabs, chips) scrolls away with the content.
 */

/** what A does right now ("Öffnen", "Laden", "Spielen" …); null = the page default */
object Hints {
    var a by mutableStateOf<String?>(null)
}

/** while this element has the focus, the hint bar shows "A [text]" */
fun Modifier.hintA(text: String): Modifier = this.onFocusChanged { s ->
    if (s.hasFocus) Hints.a = text else if (Hints.a == text) Hints.a = null
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
