package wtf.lennard.thorbro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wtf.lennard.thorbro.Qr

/** a QR code with a caption below */
@Composable
fun QrImage(text: String, caption: String, size: Dp = 200.dp) {
    val m = remember(text) { runCatching { Qr.matrix(text) }.getOrNull() } ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).background(Color.White)) {
            val cell = this.size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y])
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
        Text(caption, fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.width(size).padding(top = 6.dp))
    }
}
