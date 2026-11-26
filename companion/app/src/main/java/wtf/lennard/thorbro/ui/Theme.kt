package wtf.lennard.thorbro.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wtf.lennard.thorbro.Prefs
import wtf.lennard.thorbro.R

/**
 * Selectable looks. Every color the UI uses comes from the current [Palette]; the getters in Common.kt
 * (Accent, Panel, Ink, …) read it, so switching the theme recomposes everything.
 */
data class Palette(
    val id: String,
    val label: String,
    val bgTop: Color, val bgBottom: Color, val glow: Boolean,
    val ink: Color,              // text and icons
    val muted: Color,
    val accent: Color,           // selection fill (focused button)
    val onAccent: Color,
    val primary: Color,          // the one main action
    val onPrimary: Color,
    val panel: Color,            // flat tile fill
    val fillTop: Color, val fillBottom: Color,
    val line: Color, val lineW: Dp,   // resting outline
    val frame: Color, val frameW: Dp, // focus frame
    val soft: Color,             // highlighted row / chip
    val sheet: Color,            // dialogs and side panels
    val card: Color,             // rows inside a sheet
    val hintBar: Color,
    val ok: Color, val bad: Color,
    val shadow: Color?,          // hard offset shadow under focused elements (doodle)
    val primaryShadow: Color?,
    val headFont: FontFamily?,   // titles; null = body font
    val mascot: Boolean,
)

val GlassPalette = Palette(
    id = "glass", label = "Glas",
    bgTop = Color(0xFF101114), bgBottom = Color(0xFF050506), glow = true,
    ink = Color.White, muted = Color(0xFFA7ADB8),
    accent = Color(0xFFF4F6FA), onAccent = Color(0xFF0B0C0F),
    primary = Color(0xFFF4F6FA), onPrimary = Color(0xFF0B0C0F),
    panel = Color(0x1AFFFFFF), fillTop = Color(0x24FFFFFF), fillBottom = Color(0x0DFFFFFF),
    line = Color(0x26FFFFFF), lineW = 1.dp, frame = Color(0xF2FFFFFF), frameW = 3.dp,
    soft = Color(0x33FFFFFF), sheet = Color(0xF0141519), card = Color(0xFF1C1E23), hintBar = Color(0x99000000),
    ok = Color(0xFF7FE0A8), bad = Color(0xFFFF8A80),
    shadow = null, primaryShadow = null, headFont = null, mascot = false,
)

/** warm, flat, hand-drawn: forest background, cream outlines, sage selection, orange for the main action */
val DoodlePalette = Palette(
    id = "doodle", label = "Doodle",
    bgTop = Color(0xFF1A1E1F), bgBottom = Color(0xFF1A1E1F), glow = false,
    ink = Color(0xFFEFE7D2), muted = Color(0xFFB4B6AA),
    accent = Color(0xFFA5B98C), onAccent = Color(0xFF1A1E1F),
    primary = Color(0xFFF0A94B), onPrimary = Color(0xFF1A1E1F),
    panel = Color(0xFF252A2A), fillTop = Color(0xFF252A2A), fillBottom = Color(0xFF252A2A),
    line = Color(0xC7EFE7D2), lineW = 2.dp, frame = Color(0xFFFFF8E6), frameW = 3.dp,
    soft = Color(0x29A5B98C), sheet = Color(0xFA252A2A), card = Color(0xFF2F3535), hintBar = Color(0xFF141718),
    ok = Color(0xFFA5B98C), bad = Color(0xFFFF9C84),
    shadow = Color(0xFF4A5C38), primaryShadow = Color(0xFF7A4A0F), headFont = FontFamily.Serif, mascot = true,
)

object Theme {
    val all = listOf(GlassPalette, DoodlePalette)
    var p by mutableStateOf(GlassPalette)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        p = byId(Prefs.of(ctx).getString("theme", null))
    }

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: GlassPalette

    fun set(ctx: Context, id: String) {
        p = byId(id)
        Prefs.of(ctx).edit().putString("theme", p.id).apply()
    }

    /** the next theme in the list (settings tile cycles through them) */
    fun next(ctx: Context) = set(ctx, all[(all.indexOf(p) + 1) % all.size].id)
}

/** hard offset shadow (doodle buttons): the shape again, shifted down-right, in [color] */
@Suppress("UNUSED_PARAMETER")
fun Modifier.hardShadow(shape: Shape, color: Color?, offset: Dp = 4.dp): Modifier = this // flat: the offset shadow is gone (redesign)

/** the little ThorBro robot (doodle theme); moods: base, ask, busy, done, tired, stuffed, wait */
enum class Mood(val res: Int, val line: String) {
    BASE(R.drawable.mascot_base, "Mir geht's prima!"),
    ASK(R.drawable.mascot_ask, "Hm, da stimmt was nicht."),
    BUSY(R.drawable.mascot_busy, "Bin gerade beschäftigt …"),
    DONE(R.drawable.mascot_done, "Erledigt!"),
    TIRED(R.drawable.mascot_tired, "Ich brauche Strom …"),
    STUFFED(R.drawable.mascot_stuffed, "Ich bin ziemlich voll."),
    WAIT(R.drawable.mascot_wait, "Ich warte …"),
}

@Composable
fun Mascot(mood: Mood, size: Dp, modifier: Modifier = Modifier) =
    Image(painterResource(mood.res), mood.line, modifier.size(size))
