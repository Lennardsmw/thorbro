package wtf.lennard.thorbro.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

/** loads a local image at the size it is shown (some cover files are several MB) */
@Composable
fun Img(file: File?, px: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit, desc: String? = null) {
    if (file == null) return
    val ctx = LocalContext.current
    AsyncImage(ImageRequest.Builder(ctx).data(file).size(px).build(), desc, modifier, contentScale = scale)
}
