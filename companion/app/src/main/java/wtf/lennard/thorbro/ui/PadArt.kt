package wtf.lennard.thorbro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import wtf.lennard.thorbro.PadModel

/**
 * Controller icons in one glyph style: thick outline, filled buttons, one colour (white on the dark UI).
 * Own drawings, one per family: Xbox, PlayStation 4/5, Switch Pro, Joy-Con, Steam Controller, 8BitDo, Thor.
 */
@Composable
fun PadArt(model: PadModel, modifier: Modifier, light: Color? = null, tint: Color = Color.White) {
    Canvas(modifier) {
        val ar = when (model) { PadModel.JOYCON_L, PadModel.JOYCON_R -> 0.5f; PadModel.THOR -> 1.0f; else -> 1.45f }
        val w0 = minOf(size.width, size.height * ar); val h0 = w0 / ar
        val g = G((size.width - w0) / 2, (size.height - h0) / 2, w0, h0, tint)
        when (model) {
            PadModel.XBOX -> xbox(g)
            PadModel.DUALSENSE, PadModel.DUALSENSE_EDGE -> playstation(g, ps5 = true)
            PadModel.DUALSHOCK4, PadModel.DUALSHOCK3 -> playstation(g, ps5 = false)
            PadModel.SWITCH_PRO -> switchPro(g)
            PadModel.JOYCON_L -> joycon(g, left = true)
            PadModel.JOYCON_R -> joycon(g, left = false)
            PadModel.STEAM -> steam(g)
            PadModel.EIGHTBITDO -> eightBitDo(g)
            PadModel.THOR -> thor(g)
            PadModel.GENERIC -> generic(g)
        }
    }
}

private class G(val ox: Float, val oy: Float, val w: Float, val h: Float, val c: Color) {
    fun p(x: Float, y: Float) = Offset(ox + x * w, oy + y * h)
    fun s(x: Float) = x * w
    val line get() = Stroke(width = w * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin get() = Stroke(width = w * 0.028f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

// ---------- parts ----------

private fun DrawScope.dot(g: G, x: Float, y: Float, r: Float) = drawCircle(g.c, g.s(r), g.p(x, y))
private fun DrawScope.ring(g: G, x: Float, y: Float, r: Float) = drawCircle(g.c, g.s(r), g.p(x, y), style = g.thin)

private fun DrawScope.plus(g: G, x: Float, y: Float, a: Float) {
    val c = g.p(x, y); val l = g.s(a); val t = l * 0.36f
    drawRoundRect(g.c, Offset(c.x - t, c.y - l), Size(t * 2, l * 2), CornerRadius(t * 0.35f))
    drawRoundRect(g.c, Offset(c.x - l, c.y - t), Size(l * 2, t * 2), CornerRadius(t * 0.35f))
}

private fun DrawScope.face(g: G, x: Float, y: Float, d: Float, r: Float) {
    dot(g, x, y - d, r); dot(g, x + d, y, r); dot(g, x, y + d, r); dot(g, x - d, y, r)
}

private fun DrawScope.pill(g: G, x: Float, y: Float, w: Float, h: Float) =
    drawRoundRect(g.c, g.p(x - w / 2, y - h / 2), Size(g.s(w), g.h * h), CornerRadius(g.h * h / 2))

/** two-grip outline; [flat] = straighter top (Switch / generic), [drop] = how far the grips hang */
private fun body(g: G, drop: Float = 0.93f, waist: Float = 0.66f, shoulder: Float = 0.16f): Path = Path().apply {
    fun m(x: Float, y: Float) = g.p(x, y)
    val a = m(shoulder, 0.16f); moveTo(a.x, a.y)
    m(1f - shoulder, 0.16f).let { e -> val c = m(0.5f, 0.10f); quadraticTo(c.x, c.y, e.x, e.y) }
    m(0.97f, 0.70f).let { e -> val c1 = m(0.93f, 0.18f); val c2 = m(0.99f, 0.48f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.83f, drop).let { e -> val c1 = m(0.96f, 0.88f); val c2 = m(0.90f, drop + 0.02f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.67f, waist + 0.04f).let { e -> val c1 = m(0.76f, drop - 0.02f); val c2 = m(0.72f, waist + 0.06f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.33f, waist + 0.04f).let { e -> val c = m(0.5f, waist - 0.02f); quadraticTo(c.x, c.y, e.x, e.y) }
    m(0.17f, drop).let { e -> val c1 = m(0.28f, waist + 0.06f); val c2 = m(0.24f, drop - 0.02f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.03f, 0.70f).let { e -> val c1 = m(0.10f, drop + 0.02f); val c2 = m(0.04f, 0.88f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    a.let { e -> val c1 = m(0.01f, 0.48f); val c2 = m(0.07f, 0.18f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    close()
}

/** shoulder bumpers as short rounded strokes above the body */
private fun DrawScope.bumpers(g: G, shoulder: Float = 0.16f) {
    drawLine(g.c, g.p(shoulder + 0.02f, 0.09f), g.p(shoulder + 0.15f, 0.09f), g.s(0.05f), StrokeCap.Round)
    drawLine(g.c, g.p(0.98f - shoulder - 0.15f, 0.09f), g.p(0.98f - shoulder, 0.09f), g.s(0.05f), StrokeCap.Round)
}

// ---------- controllers ----------

private fun DrawScope.xbox(g: G) {
    bumpers(g); drawPath(body(g), g.c, style = g.line)
    dot(g, 0.50f, 0.27f, 0.045f)                       // guide button
    dot(g, 0.27f, 0.38f, 0.07f)                        // left stick (up left)
    dot(g, 0.42f, 0.38f, 0.017f); pill(g, 0.50f, 0.45f, 0.06f, 0.035f); dot(g, 0.58f, 0.38f, 0.017f) // view / share / menu
    plus(g, 0.37f, 0.57f, 0.055f)                      // d-pad
    dot(g, 0.63f, 0.57f, 0.07f)                        // right stick
    face(g, 0.75f, 0.37f, 0.055f, 0.027f)              // A B X Y
}

private fun DrawScope.playstation(g: G, ps5: Boolean) {
    bumpers(g, 0.18f); drawPath(body(g, drop = 0.95f, waist = 0.68f, shoulder = 0.18f), g.c, style = g.line)
    // touchpad
    drawRoundRect(g.c, g.p(0.36f, 0.20f), Size(g.s(0.28f), g.h * 0.21f), CornerRadius(g.s(0.03f)), style = g.thin)
    if (ps5) { drawLine(g.c, g.p(0.335f, 0.22f), g.p(0.335f, 0.39f), g.s(0.012f), StrokeCap.Round); drawLine(g.c, g.p(0.665f, 0.22f), g.p(0.665f, 0.39f), g.s(0.012f), StrokeCap.Round) }
    else drawLine(g.c, g.p(0.42f, 0.115f), g.p(0.58f, 0.115f), g.s(0.02f), StrokeCap.Round)   // DS4 light bar
    plus(g, 0.22f, 0.38f, 0.055f)                      // d-pad
    // face buttons as rings (PlayStation look)
    listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f).forEach { (dx, dy) -> ring(g, 0.78f + dx * 0.055f, 0.38f + dy * 0.055f, 0.022f) }
    dot(g, 0.37f, 0.58f, 0.065f); dot(g, 0.63f, 0.58f, 0.065f) // sticks side by side
    dot(g, 0.50f, 0.52f, 0.02f)                        // PS button
    dot(g, 0.29f, 0.24f, 0.012f); dot(g, 0.71f, 0.24f, 0.012f) // share / options
}

private fun DrawScope.switchPro(g: G) {
    bumpers(g); drawPath(body(g, drop = 0.92f, waist = 0.66f), g.c, style = g.line)
    dot(g, 0.27f, 0.38f, 0.068f)                       // left stick
    plus(g, 0.37f, 0.58f, 0.05f)
    dot(g, 0.63f, 0.58f, 0.068f)                       // right stick
    face(g, 0.75f, 0.37f, 0.055f, 0.026f)
    // minus / plus, home (round), capture (square)
    drawLine(g.c, g.p(0.40f, 0.27f), g.p(0.44f, 0.27f), g.s(0.014f), StrokeCap.Round)
    drawLine(g.c, g.p(0.56f, 0.27f), g.p(0.60f, 0.27f), g.s(0.014f), StrokeCap.Round); drawLine(g.c, g.p(0.58f, 0.24f), g.p(0.58f, 0.30f), g.s(0.014f), StrokeCap.Round)
    ring(g, 0.555f, 0.40f, 0.018f)
    drawRoundRect(g.c, g.p(0.43f, 0.385f), Size(g.s(0.03f), g.s(0.03f)), CornerRadius(g.s(0.006f)))
}

private fun DrawScope.steam(g: G) {
    bumpers(g, 0.14f); drawPath(body(g, drop = 0.94f, waist = 0.70f, shoulder = 0.14f), g.c, style = g.line)
    ring(g, 0.26f, 0.38f, 0.10f); plus(g, 0.26f, 0.38f, 0.035f)  // left trackpad (d-pad marks)
    ring(g, 0.74f, 0.38f, 0.10f)                                 // right trackpad
    dot(g, 0.50f, 0.30f, 0.035f)                                 // steam button
    dot(g, 0.39f, 0.62f, 0.06f)                                  // stick
    face(g, 0.62f, 0.62f, 0.045f, 0.021f)                        // A B X Y
    dot(g, 0.42f, 0.36f, 0.013f); dot(g, 0.58f, 0.36f, 0.013f)   // view / menu
}

private fun DrawScope.eightBitDo(g: G) {
    bumpers(g, 0.18f); drawPath(body(g, drop = 0.86f, waist = 0.70f, shoulder = 0.18f), g.c, style = g.line)
    plus(g, 0.24f, 0.38f, 0.06f)
    face(g, 0.76f, 0.38f, 0.058f, 0.028f)
    pill(g, 0.44f, 0.42f, 0.06f, 0.03f); pill(g, 0.56f, 0.42f, 0.06f, 0.03f)
    dot(g, 0.38f, 0.62f, 0.055f); dot(g, 0.62f, 0.62f, 0.055f)
}

private fun DrawScope.generic(g: G) {
    bumpers(g); drawPath(body(g), g.c, style = g.line)
    plus(g, 0.25f, 0.40f, 0.06f)
    face(g, 0.75f, 0.40f, 0.058f, 0.027f)
    dot(g, 0.38f, 0.60f, 0.06f); dot(g, 0.62f, 0.60f, 0.06f)
}

private fun DrawScope.joycon(g: G, left: Boolean) {
    val r = g.s(0.5f)
    val x0 = g.ox + g.s(0.08f); val x1 = g.ox + g.s(0.92f); val y0 = g.oy + g.h * 0.04f; val y1 = g.oy + g.h * 0.96f
    val path = Path().apply {
        if (left) {
            moveTo(x1, y0); lineTo(x1, y1); lineTo(x0 + r, y1)
            quadraticTo(x0, y1, x0, y1 - r); lineTo(x0, y0 + r); quadraticTo(x0, y0, x0 + r, y0); close()
        } else {
            moveTo(x0, y0); lineTo(x0, y1); lineTo(x1 - r, y1)
            quadraticTo(x1, y1, x1, y1 - r); lineTo(x1, y0 + r); quadraticTo(x1, y0, x1 - r, y0); close()
        }
    }
    drawPath(path, g.c, style = g.line)
    if (left) { dot(g, 0.5f, 0.30f, 0.17f); face(g, 0.5f, 0.62f, 0.12f, 0.06f) }
    else { face(g, 0.5f, 0.30f, 0.12f, 0.06f); dot(g, 0.5f, 0.62f, 0.17f) }
}

/** the Thor opened: lid with the big screen, base with the small screen, sticks and buttons */
private fun DrawScope.thor(g: G) {
    drawRoundRect(g.c, g.p(0.10f, 0.04f), Size(g.s(0.80f), g.h * 0.40f), CornerRadius(g.s(0.05f)), style = g.line)
    drawRoundRect(g.c, g.p(0.17f, 0.10f), Size(g.s(0.66f), g.h * 0.28f), CornerRadius(g.s(0.02f)), style = g.thin)
    drawRoundRect(g.c, g.p(0.04f, 0.50f), Size(g.s(0.92f), g.h * 0.46f), CornerRadius(g.s(0.09f)), style = g.line)
    drawRoundRect(g.c, g.p(0.35f, 0.57f), Size(g.s(0.30f), g.h * 0.32f), CornerRadius(g.s(0.02f)), style = g.thin)
    dot(g, 0.17f, 0.63f, 0.055f); plus(g, 0.20f, 0.83f, 0.04f)
    face(g, 0.82f, 0.64f, 0.045f, 0.021f); dot(g, 0.80f, 0.85f, 0.055f)
}
