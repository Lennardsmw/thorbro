package wtf.lennard.thorbro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import wtf.lennard.thorbro.PadModel

/**
 * Detailed drawings of the controller models: real silhouettes, shaded shells, gloss, coloured buttons.
 * Own artwork (no vendor images), drawn on a canvas so it stays sharp at every size.
 */
@Composable
fun PadArt(model: PadModel, modifier: Modifier, light: Color? = null) {
    Canvas(modifier) {
        // keep the drawing's own aspect ratio (about 3:2) inside whatever box we get
        val ar = if (model == PadModel.JOYCON_L || model == PadModel.JOYCON_R) 0.55f else if (model == PadModel.THOR) 1.0f else 1.5f
        val w0 = minOf(size.width, size.height * ar); val h0 = w0 / ar
        val ox = (size.width - w0) / 2; val oy = (size.height - h0) / 2
        val g = G(ox, oy, w0, h0)
        when (model) {
            PadModel.DUALSENSE, PadModel.DUALSENSE_EDGE -> dualSense(g, light ?: Color(0xFF3D8BFF), edge = model == PadModel.DUALSENSE_EDGE)
            PadModel.DUALSHOCK4, PadModel.DUALSHOCK3 -> dualShock(g, light ?: Color(0xFF3D8BFF), ds4 = model == PadModel.DUALSHOCK4)
            PadModel.XBOX -> xbox(g, Color(0xFF2B2E33))
            PadModel.SWITCH_PRO -> switchPro(g)
            PadModel.EIGHTBITDO -> eightBitDo(g)
            PadModel.JOYCON_L -> joycon(g, left = true)
            PadModel.JOYCON_R -> joycon(g, left = false)
            PadModel.THOR -> thor(g)
            PadModel.GENERIC -> xbox(g, Color(0xFF34383F), generic = true)
        }
    }
}

/** maps 0..1 coordinates into the fitted box */
private class G(val ox: Float, val oy: Float, val w: Float, val h: Float) {
    fun p(x: Float, y: Float) = Offset(ox + x * w, oy + y * h)
    fun s(x: Float) = x * w
}

// ---------- shared parts ----------

private fun DrawScope.shell(g: G, path: Path, top: Color, bottom: Color, edge: Color = Color(0x55000000)) {
    // soft drop shadow
    drawPath(path, Color(0x66000000), style = Stroke(g.s(0.03f)))
    drawPath(path, Brush.verticalGradient(listOf(top, bottom), g.oy, g.oy + g.h))
    // gloss on the upper half
    clipPath(path) {
        drawRect(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)), g.oy, g.oy + g.h * 0.45f), g.p(0f, 0f), Size(g.w, g.h * 0.45f))
    }
    drawPath(path, edge, style = Stroke(g.s(0.006f)))
}

private fun DrawScope.stick(g: G, cx: Float, cy: Float, r: Float, cap: Color = Color(0xFF1E2024)) {
    val c = g.p(cx, cy); val rr = g.s(r)
    drawCircle(Color(0xFF0B0C0E), rr * 1.18f, c)                       // well
    drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3D44), cap), c - Offset(rr * 0.3f, rr * 0.3f), rr * 1.3f), rr, c)
    drawCircle(Color(0x22FFFFFF), rr * 0.72f, c, style = Stroke(rr * 0.12f)) // grip ring
    drawCircle(Color(0x30FFFFFF), rr * 0.25f, c - Offset(rr * 0.3f, rr * 0.35f))
}

private fun DrawScope.button(g: G, cx: Float, cy: Float, r: Float, col: Color, mark: Color? = null) {
    val c = g.p(cx, cy); val rr = g.s(r)
    drawCircle(Color(0x88000000), rr * 1.12f, c + Offset(0f, rr * 0.12f))
    drawCircle(Brush.radialGradient(listOf(col.lighter(0.35f), col), c - Offset(rr * 0.35f, rr * 0.4f), rr * 1.4f), rr, c)
    if (mark != null) drawCircle(mark, rr * 0.38f, c, style = Stroke(rr * 0.16f))
}

private fun DrawScope.diamond(g: G, cx: Float, cy: Float, d: Float, r: Float, cols: List<Color>, marks: List<Color?> = listOf(null, null, null, null)) {
    // order: top, right, bottom, left
    button(g, cx, cy - d, r, cols[0], marks[0]); button(g, cx + d * 0.66f, cy, r, cols[1], marks[1])
    button(g, cx, cy + d, r, cols[2], marks[2]); button(g, cx - d * 0.66f, cy, r, cols[3], marks[3])
}

private fun DrawScope.dpad(g: G, cx: Float, cy: Float, s: Float, col: Color = Color(0xFF1E2024)) {
    val c = g.p(cx, cy); val a = g.s(s); val t = a * 0.36f
    drawCircle(Color(0x33000000), a * 1.25f, c)
    val br = Brush.verticalGradient(listOf(col.lighter(0.25f), col), c.y - a, c.y + a)
    drawRoundRect(br, Offset(c.x - t, c.y - a), Size(t * 2, a * 2), CornerRadius(t * 0.4f))
    drawRoundRect(br, Offset(c.x - a, c.y - t), Size(a * 2, t * 2), CornerRadius(t * 0.4f))
    drawCircle(Color(0x33000000), t * 0.5f, c)
}

private fun DrawScope.pill(g: G, x: Float, y: Float, w: Float, h: Float, col: Color) =
    drawRoundRect(col, g.p(x, y), Size(g.s(w), g.h * h), CornerRadius(g.h * h / 2))

private fun Color.lighter(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/** classic two-grip silhouette; [wide] widens the shoulders (Xbox), [drop] how far the grips hang down */
private fun gripPath(g: G, wide: Float = 0f, drop: Float = 0.98f, waist: Float = 0.62f): Path = Path().apply {
    fun m(x: Float, y: Float) = g.p(x, y)
    val a = m(0.20f - wide, 0.10f); moveTo(a.x, a.y)
    m(0.50f, 0.07f).let { e -> val c = m(0.35f, 0.06f); quadraticTo(c.x, c.y, e.x, e.y) }
    m(0.80f + wide, 0.10f).let { e -> val c = m(0.65f, 0.06f); quadraticTo(c.x, c.y, e.x, e.y) }
    m(0.985f, 0.62f).let { e -> val c1 = m(0.95f + wide, 0.14f); val c2 = m(1.0f, 0.42f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.84f, drop).let { e -> val c1 = m(0.975f, 0.84f); val c2 = m(0.92f, drop + 0.01f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.66f, waist + 0.08f).let { e -> val c1 = m(0.76f, drop - 0.02f); val c2 = m(0.72f, waist + 0.12f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.34f, waist + 0.08f).let { e -> val c = m(0.50f, waist); quadraticTo(c.x, c.y, e.x, e.y) }
    m(0.16f, drop).let { e -> val c1 = m(0.28f, waist + 0.12f); val c2 = m(0.24f, drop - 0.02f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    m(0.015f, 0.62f).let { e -> val c1 = m(0.08f, drop + 0.01f); val c2 = m(0.025f, 0.84f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    a.let { e -> val c1 = m(0.0f, 0.42f); val c2 = m(0.05f - wide, 0.14f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) }
    close()
}

private fun DrawScope.shoulders(g: G, col: Color, wide: Float = 0f) {
    pill(g, 0.17f - wide, 0.035f, 0.20f, 0.07f, col)
    pill(g, 0.63f + wide, 0.035f, 0.20f, 0.07f, col)
}

// ---------- models ----------

private fun DrawScope.dualSense(g: G, light: Color, edge: Boolean) {
    val white = Color(0xFFF2F2F4); val black = Color(0xFF17181B)
    shoulders(g, Color(0xFFDADADF))
    val body = gripPath(g)
    shell(g, body, white, Color(0xFFC9C9CF))
    // black centre plate between the grips
    val plate = Path().apply {
        moveTo(g.p(0.26f, 0.30f).x, g.p(0.26f, 0.30f).y)
        g.p(0.74f, 0.30f).let { lineTo(it.x, it.y) }
        g.p(0.68f, 0.70f).let { e -> val c = g.p(0.76f, 0.62f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(0.32f, 0.70f).let { e -> val c = g.p(0.50f, 0.64f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(0.26f, 0.30f).let { e -> val c = g.p(0.24f, 0.62f); quadraticTo(c.x, c.y, e.x, e.y) }
        close()
    }
    drawPath(plate, Brush.verticalGradient(listOf(Color(0xFF2A2C31), black), g.oy, g.oy + g.h))
    // touchpad with light strips
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFDCDCE2)), g.oy + g.h * 0.12f, g.oy + g.h * 0.40f),
        g.p(0.335f, 0.12f), Size(g.s(0.33f), g.h * 0.27f), CornerRadius(g.s(0.03f)))
    drawRoundRect(light, g.p(0.32f, 0.13f), Size(g.s(0.012f), g.h * 0.25f), CornerRadius(g.s(0.01f)))
    drawRoundRect(light, g.p(0.668f, 0.13f), Size(g.s(0.012f), g.h * 0.25f), CornerRadius(g.s(0.01f)))
    dpad(g, 0.20f, 0.36f, 0.055f, Color(0xFFE8E8EC))
    val sym = Color(0xFF6B6F78)
    diamond(g, 0.80f, 0.36f, 0.075f, 0.033f, List(4) { Color(0xFFEDEDF0) }, listOf(sym, sym, sym, sym))
    stick(g, 0.365f, 0.56f, 0.065f); stick(g, 0.635f, 0.56f, 0.065f)
    button(g, 0.50f, 0.50f, 0.022f, black)                                  // PS button
    if (edge) { pill(g, 0.24f, 0.80f, 0.05f, 0.03f, black); pill(g, 0.71f, 0.80f, 0.05f, 0.03f, black) }
}

private fun DrawScope.dualShock(g: G, light: Color, ds4: Boolean) {
    shoulders(g, Color(0xFF1C1E22))
    if (ds4) drawRoundRect(light, g.p(0.36f, 0.055f), Size(g.s(0.28f), g.h * 0.03f), CornerRadius(g.s(0.01f)))
    shell(g, gripPath(g, drop = 0.96f), Color(0xFF3A3D44), Color(0xFF1A1C20))
    if (ds4) drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2E3137), Color(0xFF1C1E22)), g.oy + g.h * 0.12f, g.oy + g.h * 0.40f),
        g.p(0.335f, 0.12f), Size(g.s(0.33f), g.h * 0.27f), CornerRadius(g.s(0.03f)))
    dpad(g, 0.20f, 0.36f, 0.055f, Color(0xFF2A2D33))
    diamond(g, 0.80f, 0.36f, 0.075f, 0.033f, List(4) { Color(0xFF26282D) },
        listOf(Color(0xFF3FD1A1), Color(0xFFFF5A6E), Color(0xFF6FA8FF), Color(0xFFF07AD8)))
    stick(g, 0.365f, 0.58f, 0.065f); stick(g, 0.635f, 0.58f, 0.065f)
    button(g, 0.50f, 0.52f, 0.022f, Color(0xFF15171A))
}

private fun DrawScope.xbox(g: G, body: Color, generic: Boolean = false) {
    shoulders(g, body.lighter(0.1f), wide = 0.03f)
    shell(g, gripPath(g, wide = 0.03f, drop = 0.97f, waist = 0.66f), body.lighter(0.12f), Color(0xFF16181B))
    // Xbox guide button with glow
    if (!generic) {
        drawCircle(Brush.radialGradient(listOf(Color(0x99FFFFFF), Color(0x00FFFFFF)), g.p(0.5f, 0.20f), g.s(0.06f)), g.s(0.06f), g.p(0.5f, 0.20f))
        button(g, 0.50f, 0.20f, 0.032f, Color(0xFFE9EBEF))
    } else button(g, 0.50f, 0.20f, 0.028f, Color(0xFF55595F))
    button(g, 0.44f, 0.32f, 0.014f, Color(0xFF1A1C1F)); button(g, 0.56f, 0.32f, 0.014f, Color(0xFF1A1C1F)) // view / menu
    stick(g, 0.24f, 0.33f, 0.07f)                                   // offset sticks
    dpad(g, 0.37f, 0.56f, 0.05f, Color(0xFF26282C))
    stick(g, 0.63f, 0.56f, 0.07f)
    val abxy = if (generic) List(4) { Color(0xFF2A2D32) } else listOf(Color(0xFFF3C319), Color(0xFFE5404B), Color(0xFF3DB24B), Color(0xFF2E8BEA))
    diamond(g, 0.77f, 0.33f, 0.072f, 0.032f, abxy)
}

private fun DrawScope.switchPro(g: G) {
    shoulders(g, Color(0xFF1E2024), wide = 0.02f)
    val body = gripPath(g, wide = 0.02f, drop = 0.97f, waist = 0.64f)
    shell(g, body, Color(0xFF3B3E45), Color(0xFF141518))
    // translucent look: faint inner sheen
    clipPath(body) { drawRect(Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x14FFFFFF), Color(0x00FFFFFF)), g.p(0.2f, 0.1f), g.p(0.8f, 0.9f)), g.p(0f, 0f), Size(g.w, g.h)) }
    stick(g, 0.24f, 0.33f, 0.068f)
    dpad(g, 0.37f, 0.56f, 0.05f, Color(0xFF26282C))
    stick(g, 0.63f, 0.56f, 0.068f)
    diamond(g, 0.77f, 0.33f, 0.072f, 0.031f, List(4) { Color(0xFF2B2D32) })
    button(g, 0.42f, 0.28f, 0.012f, Color(0xFF1A1C1F)); button(g, 0.58f, 0.28f, 0.012f, Color(0xFF1A1C1F))
    button(g, 0.555f, 0.40f, 0.018f, Color(0xFF55595F)); button(g, 0.445f, 0.40f, 0.014f, Color(0xFF2A2C30)) // home / capture
}

private fun DrawScope.eightBitDo(g: G) {
    // SN30-Pro style: rounded bar body, short grips
    val body = gripPath(g, drop = 0.90f, waist = 0.70f)
    shoulders(g, Color(0xFFBDB8AE))
    shell(g, body, Color(0xFFF1EDE4), Color(0xFFCFC9BD))
    dpad(g, 0.22f, 0.36f, 0.06f, Color(0xFF2C2D31))
    diamond(g, 0.78f, 0.36f, 0.075f, 0.034f, listOf(Color(0xFF8E86D6), Color(0xFF6D63C4), Color(0xFF6D63C4), Color(0xFF8E86D6)))
    pill(g, 0.42f, 0.42f, 0.06f, 0.035f, Color(0xFF8F8A80)); pill(g, 0.52f, 0.42f, 0.06f, 0.035f, Color(0xFF8F8A80))
    stick(g, 0.37f, 0.62f, 0.058f); stick(g, 0.63f, 0.62f, 0.058f)
}

private fun DrawScope.joycon(g: G, left: Boolean) {
    val col = if (left) Color(0xFF13B6E8) else Color(0xFFFF4D5E)
    val path = Path().apply {
        val r = g.s(0.5f)
        if (left) {
            moveTo(g.p(1f, 0.02f).x, g.p(1f, 0.02f).y)
            g.p(1f, 0.98f).let { lineTo(it.x, it.y) }
            g.p(0.5f, 0.98f).let { lineTo(it.x, it.y) }
            addArcPath(g, left = true, r = r)
        } else {
            moveTo(g.p(0f, 0.02f).x, g.p(0f, 0.02f).y)
            g.p(0f, 0.98f).let { lineTo(it.x, it.y) }
            g.p(0.5f, 0.98f).let { lineTo(it.x, it.y) }
            addArcPath(g, left = false, r = r)
        }
        close()
    }
    shell(g, path, col.lighter(0.15f), col.copy(red = col.red * 0.75f, green = col.green * 0.75f, blue = col.blue * 0.75f))
    if (left) { stick(g, 0.5f, 0.30f, 0.20f); diamond(g, 0.5f, 0.62f, 0.13f, 0.075f, List(4) { Color(0xFF1C1E22) }) }
    else { diamond(g, 0.5f, 0.30f, 0.13f, 0.075f, List(4) { Color(0xFF1C1E22) }); stick(g, 0.5f, 0.62f, 0.20f) }
}

/** rounded outer side of a Joy-Con */
private fun Path.addArcPath(g: G, left: Boolean, r: Float) {
    if (left) {
        g.p(0f, 0.80f).let { e -> val c = g.p(0f, 0.98f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(0f, 0.20f).let { lineTo(it.x, it.y) }
        g.p(0.5f, 0.02f).let { e -> val c = g.p(0f, 0.02f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(1f, 0.02f).let { lineTo(it.x, it.y) }
    } else {
        g.p(1f, 0.80f).let { e -> val c = g.p(1f, 0.98f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(1f, 0.20f).let { lineTo(it.x, it.y) }
        g.p(0.5f, 0.02f).let { e -> val c = g.p(1f, 0.02f); quadraticTo(c.x, c.y, e.x, e.y) }
        g.p(0f, 0.02f).let { lineTo(it.x, it.y) }
    }
}

/** the Thor, opened: big top screen in the lid, the base with the small second screen, sticks and buttons */
private fun DrawScope.thor(g: G) {
    val dark = Color(0xFF26292E); val darker = Color(0xFF131417)
    // lid
    val lid = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(g.p(0.06f, 0.02f).x, g.p(0f, 0.02f).y, g.p(0.94f, 0f).x, g.p(0f, 0.47f).y, CornerRadius(g.s(0.05f)))) }
    shell(g, lid, dark.lighter(0.08f), darker)
    drawRoundRect(Color(0xFF07080A), g.p(0.10f, 0.05f), Size(g.s(0.80f), g.h * 0.39f), CornerRadius(g.s(0.02f)))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF203A66), Color(0xFF0D1626), Color(0xFF2B1E4A)), g.p(0.11f, 0.06f), g.p(0.89f, 0.43f)),
        g.p(0.11f, 0.06f), Size(g.s(0.78f), g.h * 0.37f), CornerRadius(g.s(0.015f)))
    drawRect(Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x00FFFFFF)), g.p(0.11f, 0.06f), g.p(0.5f, 0.3f)), g.p(0.11f, 0.06f), Size(g.s(0.78f), g.h * 0.37f))
    // hinge
    drawRoundRect(darker, g.p(0.08f, 0.465f), Size(g.s(0.84f), g.h * 0.03f), CornerRadius(g.s(0.01f)))
    // base
    val base = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(g.p(0.02f, 0f).x, g.p(0f, 0.50f).y, g.p(0.98f, 0f).x, g.p(0f, 0.98f).y, CornerRadius(g.s(0.08f)))) }
    shell(g, base, dark.lighter(0.1f), darker)
    // second screen in the middle of the base
    drawRoundRect(Color(0xFF07080A), g.p(0.33f, 0.54f), Size(g.s(0.34f), g.h * 0.38f), CornerRadius(g.s(0.015f)))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF1C2E4E), Color(0xFF0E1320)), g.p(0.34f, 0.55f), g.p(0.66f, 0.91f)),
        g.p(0.345f, 0.555f), Size(g.s(0.31f), g.h * 0.35f), CornerRadius(g.s(0.01f)))
    stick(g, 0.13f, 0.62f, 0.055f); dpad(g, 0.20f, 0.82f, 0.04f)
    diamond(g, 0.84f, 0.64f, 0.055f, 0.022f, List(4) { Color(0xFF34373D) }); stick(g, 0.80f, 0.84f, 0.055f)
}
