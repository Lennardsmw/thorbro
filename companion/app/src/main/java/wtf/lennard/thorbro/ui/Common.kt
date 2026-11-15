package wtf.lennard.thorbro.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF5A3C)
val Panel = Color(0xFF1B1E24)
val Bg = Color(0xFF111317)
val Muted = Color(0xFF9097A5)
val Ok = Color(0xFF4CC27F)
val Bad = Color(0xFFFF6B57)

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
        colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel, onPrimary = Color.White,
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

/** A panel that shows a clear frame when the gamepad focus is on it (or on something inside). */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.clip(shape).background(Panel)
        .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0xFF2C3039)), shape)
    if (onClick != null) m = m.clickable(interactionSource = src, indication = null, onClick = onClick)
    Column(m.padding(16.dp), content = content)
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
    if (primary) Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(text) }
    else OutlinedButton(onClick, enabled = enabled) { Text(text, color = Color.White) }
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
