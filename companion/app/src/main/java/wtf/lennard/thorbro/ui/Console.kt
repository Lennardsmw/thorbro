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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Console-style building blocks: square tiles you move between with the d-pad, a big focus frame,
 * one short title and at most one short status line. No text walls, no small buttons.
 */
@Composable
fun ConsoleTile(
    title: String,
    sub: String = "",
    icon: ImageVector? = null,
    image: File? = null,
    progress: Float? = null,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    badges: @Composable (BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fx = rememberFx()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(1f)
            .fx(fx, shape, onClick = onClick)
            .then(if (accent) Modifier.background(Color(0x33FFFFFF)) else Modifier.background(GlassFill)),
    ) {
        if (image != null) {
            Img(image, 400, Modifier.fillMaxSize(), ContentScale.Crop, title)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            }
        } else {
            // icon takes the free space above the text, so a two-line title can never run into it
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, null, Modifier.heightIn(max = 60.dp).fillMaxHeight(0.75f).aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
                if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).background(Accent))
        }
        badges?.invoke(this)
    }
}

/** small round status icon in a tile corner */
@Composable
fun Badge(icon: ImageVector, on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(26.dp).clip(CircleShape).background(if (on) Color(0xE6FFFFFF) else Color(0x99000000)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (on) Color.Black else Color(0x80FFFFFF))
    }
}

/** grid of tiles; the first focusable tile gets focus when the screen opens */
@Composable
fun TileGrid(first: FocusRequester? = null, min: Int = 170, content: LazyGridScope.() -> Unit) {
    if (first != null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { first.requestFocus() } }
    val (st, fling) = snapGrid()
    CenteredFocus {
        // room around the edge so the enlarged, framed focus tile is never cut off
        LazyVerticalGrid(GridCells.Adaptive(min.dp), state = st, flingBehavior = fling, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

fun LazyGridScope.header(text: String, sub: String = "") = item(span = { GridItemSpan(maxLineSpan) }) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun Modifier.firstFocus(f: FocusRequester?, isFirst: Boolean) = if (f != null && isFirst) this.focusRequester(f) else this

/** a console menu: title, an optional short line, big buttons stacked; B closes */
@Composable
fun ConsoleMenu(title: String, text: String = "", mono: Boolean = false, options: List<Pair<String, () -> Unit>>,
                qrs: List<Pair<String, String>> = emptyList(), onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF0141519)).padding(24.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (text.isNotEmpty()) Text(text, fontSize = if (mono) 15.sp else 15.sp, color = if (mono) Accent else Muted,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null, modifier = Modifier.padding(top = 8.dp))
            if (qrs.isNotEmpty()) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) { qrs.forEach { (text, caption) -> QrImage(text, caption, if (qrs.size > 1) 190.dp else 220.dp) } }
            Spacer(Modifier.height(16.dp))
            (options + ("Zurück" to onClose)).forEachIndexed { i, (label, act) ->
                BigButton(label, modifier = Modifier.firstFocus(first, i == 0)) { act() }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** big console button: white when focused, optional icon; [primary] is white-ish even without focus */
@Composable
fun BigButton(label: String, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fx = rememberFx()
    val focused = fx.active
    val shape = RoundedCornerShape(14.dp)
    val bg = when { focused -> Modifier.background(Accent); primary -> Modifier.background(Color(0x40FFFFFF)); else -> Modifier.background(GlassFill) }
    Box(modifier.height(56.dp).fx(fx, shape, lift = 1.04f, frame = false, enabled = enabled, onClick = onClick).then(bg),
        contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp)) {
            val fg = if (focused) OnAccent else if (enabled) Color.White else Muted
            if (icon != null) { Icon(icon, null, Modifier.size(22.dp), tint = fg); Spacer(Modifier.width(10.dp)) }
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** hides status and navigation bar in a dialog's own window (dialogs do not inherit it) */
@Composable
fun ImmersiveDialog() {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(view) {
        val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        immersive(w)
    }
}

fun immersive(w: android.view.Window) {
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
    androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * Console shelves: one horizontal row per group. Rows start at the left and the focused tile snaps there;
 * the focused row sits in the middle of the screen, and touch scrolling snaps to whole tiles/rows.
 */
class Shelf<T>(val title: String, val sub: String, val items: List<T>)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun <T> Shelves(
    shelves: List<Shelf<T>>,
    key: (T) -> Any,
    first: FocusRequester? = null,
    tile: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (first != null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { first.requestFocus() } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileW = minOf(maxHeight * 0.42f, 210.dp)
        val rowH = tileW + 72.dp
        val vPad = ((maxHeight - rowH) / 2).coerceAtLeast(0.dp)
        val hPad = 14.dp   // rows start at the left; the focused tile snaps to the left edge (room for the focus frame)
        val col = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.foundation.lazy.LazyColumn(
            state = col, contentPadding = PaddingValues(vertical = vPad),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(col),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            shelves.forEachIndexed { r, shelf ->
                item(key = "shelf-$r-${shelf.title}") {
                    val row = androidx.compose.foundation.lazy.rememberLazyListState()
                    Column(Modifier.height(rowH)) {
                        androidx.compose.foundation.layout.Row(Modifier.padding(start = hPad, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                            Text(shelf.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (shelf.sub.isNotEmpty()) Text("  " + shelf.sub, fontSize = 14.sp, color = Muted, maxLines = 1)
                        }
                        androidx.compose.foundation.lazy.LazyRow(
                            state = row, contentPadding = PaddingValues(horizontal = hPad, vertical = 14.dp),
                            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(row),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            shelf.items.forEachIndexed { i, it ->
                                item(key = key(it)) {
                                    tile(it, Modifier.width(tileW)
                                        .firstFocus(first, r == 0 && i == 0)
                                        .onFocusChanged { f ->
                                            if (f.isFocused) scope.launch {
                                                launch { row.animateScrollToItem(i) }
                                                launch { col.animateScrollToItem(r) }
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** marks a dialog as open while it is on screen; the flag can no longer get stuck however the dialog closes */
@Composable
fun DialogOpen() = androidx.compose.runtime.DisposableEffect(Unit) {
    ThorActivity.openDialogs++
    onDispose { ThorActivity.openDialogs-- }
}

// ---------- snappy scrolling ----------

/** the focused item glides to the middle of the list/grid instead of creeping along the edge */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val CenterFocus = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // item bigger than the viewport: show its start; otherwise center it
        return if (size >= containerSize) offset else offset - (containerSize - size) / 2f
    }
}

/** wrap a vertical list or grid: d-pad focus stays centered */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CenteredFocus(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenterFocus, content = content)

/** list state + fling that snaps to whole items */
@Composable
fun snapList(): Pair<androidx.compose.foundation.lazy.LazyListState, androidx.compose.foundation.gestures.FlingBehavior> {
    val s = androidx.compose.foundation.lazy.rememberLazyListState()
    return s to androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(s)
}

@Composable
fun snapGrid(): Pair<androidx.compose.foundation.lazy.grid.LazyGridState, androidx.compose.foundation.gestures.FlingBehavior> {
    val s = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    return s to androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(s)
}
