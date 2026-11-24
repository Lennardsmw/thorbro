package wtf.lennard.thorbro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import wtf.lennard.thorbro.Library
import wtf.lennard.thorbro.Media
import org.json.JSONObject
import wtf.lennard.thorbro.Background

/** top right, over every screen: what runs in the background (offload queue, downloads, RomM upload) with n/m */
@Composable
fun BackgroundOverlay(modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    WhileVisible { while (true) { items = Background.list().objs(); delay(500) } }
    AnimatedVisibility(items.isNotEmpty(), modifier, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 }) {
        Column(Modifier.padding(top = 58.dp, end = 24.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { ActivityCard(it) }
        }
    }
}

/** the game's picture: RomM cover for downloads, boxart (or the console icon) for local games */
@Composable
private fun gameImage(a: JSONObject): java.io.File? {
    val ctx = LocalContext.current
    val romId = a.optInt("romId"); val file = a.optString("file"); val sys = a.optString("system")
    val img by produceState<java.io.File?>(null, romId, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    romId > 0 -> Library.coverFile(ctx, romId)
                    file.isNotEmpty() && sys.isNotEmpty() -> Media.coverForFile(ctx, sys, java.io.File(file)) ?: Media.icon(ctx, sys)
                    else -> null
                }
            }.getOrNull()
        }
    }
    return img
}

/** [icon] game title / kind n/m, then speed · size · time left, then the progress bar */
@Composable
private fun ActivityCard(a: JSONObject) {
    val done = a.optBoolean("done")
    val err = a.optString("error").takeIf { it.isNotEmpty() && it != "null" }
    val p = a.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
    val total = a.optInt("total")
    val game = a.optString("game").takeIf { !done && it.isNotEmpty() }
    val img = if (game != null) gameImage(a) else null
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xE6101114)).border(1.dp, GlassLine, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                if (img != null) Img(img, 160, Modifier.fillMaxSize(), ContentScale.Crop, game)
                else Icon(when { err != null && done -> Icons.Filled.ErrorOutline; done -> Icons.Filled.CheckCircle
                    a.optString("kind") == "download" -> Icons.Filled.CloudDownload; else -> Icons.Filled.CloudUpload },
                    null, Modifier.size(24.dp), tint = if (err != null && done) Bad else if (done) Ok else Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game ?: a.optString("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (game != null) a.optString("title") + (if (total > 1) " · ${a.optInt("index")}/$total" else "")
                    else a.optString("name"), fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (game == null && (total > 1 || (total == 1 && !done))) Text("${a.optInt("index")}/$total", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (!done) {
            val transfer = a.optString("transfer").takeIf { it.isNotEmpty() && it != "null" }
            val step = a.optString("step").takeIf { it.isNotEmpty() && it != a.optString("name") }
            Text(transfer ?: step ?: a.optString("name").takeIf { game != null && it != game } ?: "",
                fontSize = 13.sp, color = Color(0xD9FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p).height(5.dp).background(Accent))
                }
                Text("  ${(p * 100).toInt()} %", fontSize = 12.sp, color = Muted)
            }
        } else if (err != null) Text(err, fontSize = 12.sp, color = Bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
