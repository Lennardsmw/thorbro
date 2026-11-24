package wtf.lennard.thorbro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/** glass look: black background, milky translucent panels, white as the highlight */
val Accent = Color(0xFFF4F6FA)          // highlight (white)
val OnAccent = Color(0xFF0B0C0F)        // text/icons on the highlight
val Panel = Color(0x1AFFFFFF)           // milky glass
val Bg = Color(0xFF07080A)              // deep black (placeholders)
val Muted = Color(0xFFA7ADB8)
val Ok = Color(0xFF7FE0A8)
val Bad = Color(0xFFFF8A80)
val GlassLine = Color(0x26FFFFFF)

/** black background with soft milky light, for every page */
fun Modifier.glassBackground() = this.drawBehind {
    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF101114), Color(0xFF050506))))
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0x22FFFFFF), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.05f), radius = size.width * 0.55f),
        radius = size.width * 0.55f, center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.05f))
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0x14DDE6FF), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.95f), radius = size.width * 0.5f),
        radius = size.width * 0.5f, center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.95f))
}

/** milky glass fill: a little brighter at the top, like light on frosted glass */
val GlassFill = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x0DFFFFFF)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunito(w: Int) = androidx.compose.ui.text.font.Font(
    wtf.lennard.thorbro.R.font.nunito, FontWeight(w),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
)

/** rounded console-style font (Nunito, OFL) */
val Nunito = androidx.compose.ui.text.font.FontFamily(nunito(400), nunito(600), nunito(700), nunito(800))

@Composable
fun ThorTheme(content: @Composable () -> Unit) {
    val base = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.n() = copy(fontFamily = Nunito)
    val type = androidx.compose.material3.Typography(
        displayLarge = base.displayLarge.n(), displayMedium = base.displayMedium.n(), displaySmall = base.displaySmall.n(),
        headlineLarge = base.headlineLarge.n(), headlineMedium = base.headlineMedium.n(), headlineSmall = base.headlineSmall.n(),
        titleLarge = base.titleLarge.n(), titleMedium = base.titleMedium.n(), titleSmall = base.titleSmall.n(),
        bodyLarge = base.bodyLarge.n(), bodyMedium = base.bodyMedium.n(), bodySmall = base.bodySmall.n(),
        labelLarge = base.labelLarge.n().copy(fontWeight = FontWeight.Bold), labelMedium = base.labelMedium.n(), labelSmall = base.labelSmall.n(),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Color(0xFF15171B), onPrimary = OnAccent,
            onBackground = Color.White, onSurface = Color.White),
        typography = type,
    ) {
        // text outside a Surface would otherwise be black on the dark background
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White,
            androidx.compose.material3.LocalTextStyle provides type.bodyLarge.copy(color = Color.White),
            content = content,
        )
    }
}

/**
 * One look for everything you can select with the D-pad, mouse or finger:
 * the element lifts a little, gets a white frame and is drawn above its neighbours.
 * Exactly ONE focus stop per element (clickable is already focusable; never add .focusable() too).
 */
class Fx(val src: MutableInteractionSource, val active: Boolean, val pressed: Boolean)

@Composable
fun rememberFx(): Fx {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    return Fx(src, focused || hovered, pressed)
}

/**
 * Applies the focus look and the click. Put size/padding-free modifiers BEFORE it and
 * background/content AFTER it (it clips to [shape]).
 * [lift] = scale while active, [frame] = draw the white border, [restLine] = border when idle.
 */
fun Modifier.fx(
    fx: Fx,
    shape: Shape,
    lift: Float = 1.06f,
    frame: Boolean = true,
    restLine: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val target = when { !enabled -> 1f; fx.pressed -> lift - (lift - 1f) * 0.6f - 0.02f; fx.active -> lift; else -> 1f }
    val scale by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "fx")
    val line by animateDpAsState(if (fx.active && frame) 3.dp else if (restLine) 1.dp else 0.dp, tween(140), label = "fxLine")
    val color by animateColorAsState(if (fx.active && frame) Color(0xF2FFFFFF) else if (restLine) GlassLine else Color.Transparent, tween(140), label = "fxColor")
    this.zIndex(if (fx.active) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(line, color, shape)
        .clip(shape)
        .hoverable(fx.src, enabled)
        .clickable(interactionSource = fx.src, indication = null, enabled = enabled, onClick = onClick)
}

/** A panel that shows a clear frame when the gamepad focus is on it (or on something inside). */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fx = rememberFx()
    var inside by remember { mutableStateOf(false) }
    val on = fx.active || inside
    val shape = RoundedCornerShape(20.dp)
    val line by animateDpAsState(if (on) 2.dp else 1.dp, tween(140), label = "cardLine")
    val color by animateColorAsState(if (on) Color(0xD9FFFFFF) else GlassLine, tween(140), label = "cardColor")
    var m = modifier.zIndex(if (on) 1f else 0f).onFocusChanged { inside = it.hasFocus && !it.isFocused }
    m = if (onClick != null) m.fx(fx, shape, lift = 1.015f, frame = false, restLine = false, onClick = onClick)
        else m.clip(shape)
    Column(m.background(GlassFill).border(line, color, shape).padding(16.dp), content = content)
}

@Composable
fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

@Composable
fun Label(text: String) = Text(text, color = Muted, fontSize = 13.sp)

@Composable
fun Big(text: String, color: Color = Color.White) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)

@Composable
fun Line(text: String, color: Color = Color.White) = Text(text, fontSize = 15.sp, color = color)

@Composable
fun Act(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(text) }
    else OutlinedButton(onClick, enabled = enabled, border = BorderStroke(1.dp, Color(0x40FFFFFF)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x14FFFFFF))) { Text(text, color = Color.White) }
}

@Composable
fun Acts(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
fun StatusDot(ok: Boolean) = Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (ok) Ok else Bad).padding(5.dp))

fun fmtBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val u = listOf("KB", "MB", "GB", "TB"); var v = n.toDouble(); var i = -1
    do { v /= 1024; i++ } while (v >= 1024 && i < u.size - 1)
    return (if (v < 10) "%.1f" else "%.0f").format(v) + " " + u[i]
}

/**
 * Like LaunchedEffect, but only while ThorBro is on screen: polling loops pause when a game runs
 * or the display is off (they used to keep ticking in the background and cost battery).
 */
@Composable
fun WhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, *keys) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() } }
}
