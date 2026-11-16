package wtf.lennard.thorbro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import wtf.lennard.thorbro.PadModel

/** a simple drawing of the controller model (own artwork, not vendor images) */
@Composable
fun PadArt(model: PadModel, modifier: Modifier, light: Color? = null) {
    Canvas(modifier) {
        when (model) {
            PadModel.THOR -> thor()
            PadModel.JOYCON_L -> joycon(left = true)
            PadModel.JOYCON_R -> joycon(left = false)
            PadModel.DUALSENSE, PadModel.DUALSENSE_EDGE -> pad(Color(0xFFECECEF), Color(0xFF1C1E22), symmetric = true, touchpad = true, light = light ?: Color(0xFF2E7BFF))
            PadModel.DUALSHOCK4, PadModel.DUALSHOCK3 -> pad(Color(0xFF2A2D33), Color(0xFF8A90A0), symmetric = true, touchpad = model == PadModel.DUALSHOCK4, topLight = light ?: Color(0xFF2E7BFF))
            PadModel.XBOX -> pad(Color(0xFF2E3138), Color(0xFF9AA0AE), symmetric = false, xboxButton = true)
            PadModel.SWITCH_PRO -> pad(Color(0xFF26282D), Color(0xFF9AA0AE), symmetric = false)
            PadModel.EIGHTBITDO -> pad(Color(0xFFE6E2D8), Color(0xFF55585F), symmetric = true)
            PadModel.GENERIC -> pad(Color(0xFF30343C), Color(0xFF9AA0AE), symmetric = false)
        }
    }
}

private fun DrawScope.stick(c: Offset, r: Float, line: Color) {
    drawCircle(Color(0xFF15171A), r, c)
    drawCircle(line, r * 0.62f, c, style = Stroke(r * 0.12f))
}

private fun DrawScope.dpad(c: Offset, s: Float, col: Color) {
    drawRoundRect(col, Offset(c.x - s * 0.35f, c.y - s), Size(s * 0.7f, s * 2f), CornerRadius(s * 0.15f))
    drawRoundRect(col, Offset(c.x - s, c.y - s * 0.35f), Size(s * 2f, s * 0.7f), CornerRadius(s * 0.15f))
}

private fun DrawScope.face(c: Offset, s: Float, col: Color) {
    listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f)).forEach { drawCircle(col, s * 0.42f, Offset(c.x + it.x * s, c.y + it.y * s)) }
}

/** a two-grip gamepad; symmetric = sticks side by side at the bottom (PlayStation style) */
private fun DrawScope.pad(body: Color, detail: Color, symmetric: Boolean, touchpad: Boolean = false, light: Color? = null, topLight: Color? = null, xboxButton: Boolean = false) {
    val w = size.width; val h = size.height
    val outline = Color(0xFF5A606C)
    // grips + body
    drawOval(body, Offset(w * 0.02f, h * 0.30f), Size(w * 0.40f, h * 0.68f))
    drawOval(body, Offset(w * 0.58f, h * 0.30f), Size(w * 0.40f, h * 0.68f))
    drawRoundRect(body, Offset(w * 0.08f, h * 0.10f), Size(w * 0.84f, h * 0.58f), CornerRadius(h * 0.28f))
    drawRoundRect(outline, Offset(w * 0.08f, h * 0.10f), Size(w * 0.84f, h * 0.58f), CornerRadius(h * 0.28f), style = Stroke(w * 0.008f))
    topLight?.let { drawRoundRect(it, Offset(w * 0.30f, h * 0.06f), Size(w * 0.40f, h * 0.05f), CornerRadius(h * 0.03f)) }
    if (touchpad) {
        drawRoundRect(Color(0xFF15171A).copy(alpha = if (body.luminance() > 0.5f) 0.18f else 0.6f), Offset(w * 0.34f, h * 0.15f), Size(w * 0.32f, h * 0.24f), CornerRadius(h * 0.05f))
        light?.let {
            drawRoundRect(it, Offset(w * 0.31f, h * 0.15f), Size(w * 0.018f, h * 0.24f), CornerRadius(w * 0.01f))
            drawRoundRect(it, Offset(w * 0.672f, h * 0.15f), Size(w * 0.018f, h * 0.24f), CornerRadius(w * 0.01f))
        }
    }
    val r = h * 0.11f
    if (symmetric) {
        dpad(Offset(w * 0.22f, h * 0.38f), h * 0.09f, detail)
        face(Offset(w * 0.78f, h * 0.38f), h * 0.085f, detail)
        stick(Offset(w * 0.37f, h * 0.60f), r, outline)
        stick(Offset(w * 0.63f, h * 0.60f), r, outline)
    } else {
        stick(Offset(w * 0.23f, h * 0.38f), r, outline)
        face(Offset(w * 0.77f, h * 0.38f), h * 0.085f, detail)
        dpad(Offset(w * 0.37f, h * 0.60f), h * 0.085f, detail)
        stick(Offset(w * 0.63f, h * 0.60f), r, outline)
    }
    if (xboxButton) drawCircle(Color(0xFFDADDE3), h * 0.065f, Offset(w * 0.5f, h * 0.24f))
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

private fun DrawScope.joycon(left: Boolean) {
    val w = size.width; val h = size.height
    val bw = w * 0.32f; val x = (w - bw) / 2
    val col = if (left) Color(0xFF2FB6E8) else Color(0xFFF0455A)
    drawRoundRect(col, Offset(x, h * 0.04f), Size(bw, h * 0.92f), CornerRadius(bw * 0.5f))
    val cx = w / 2
    if (left) { stick(Offset(cx, h * 0.32f), bw * 0.26f, Color(0x55000000)); dpad(Offset(cx, h * 0.62f), bw * 0.2f, Color(0xFF15171A)) }
    else { face(Offset(cx, h * 0.32f), bw * 0.2f, Color(0xFF15171A)); stick(Offset(cx, h * 0.62f), bw * 0.26f, Color(0x55000000)) }
}

/** the handheld itself: wide body, screen in the middle, sticks and buttons left and right */
private fun DrawScope.thor() {
    val w = size.width; val h = size.height
    val body = Color(0xFF2A2D33); val outline = Color(0xFF5A606C)
    drawRoundRect(body, Offset(w * 0.02f, h * 0.18f), Size(w * 0.96f, h * 0.64f), CornerRadius(h * 0.2f))
    drawRoundRect(outline, Offset(w * 0.02f, h * 0.18f), Size(w * 0.96f, h * 0.64f), CornerRadius(h * 0.2f), style = Stroke(w * 0.008f))
    drawRoundRect(Color(0xFF0E1013), Offset(w * 0.25f, h * 0.24f), Size(w * 0.5f, h * 0.52f), CornerRadius(h * 0.04f))
    drawRoundRect(Accent.copy(alpha = 0.35f), Offset(w * 0.27f, h * 0.27f), Size(w * 0.46f, h * 0.46f), CornerRadius(h * 0.03f))
    stick(Offset(w * 0.12f, h * 0.38f), h * 0.08f, outline)
    dpad(Offset(w * 0.15f, h * 0.62f), h * 0.06f, Color(0xFF9AA0AE))
    face(Offset(w * 0.88f, h * 0.38f), h * 0.06f, Color(0xFF9AA0AE))
    stick(Offset(w * 0.85f, h * 0.62f), h * 0.08f, outline)
}
