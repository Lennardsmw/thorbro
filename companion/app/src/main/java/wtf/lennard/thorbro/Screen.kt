package wtf.lennard.thorbro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * Mirrors the main screen into JPEG frames for the browser (Android only lets apps capture the
 * primary display). Android asks the user on the device each time capturing starts.
 */
object Screen {
    @Volatile var frame: ByteArray? = null
        private set
    @Volatile var frameAt = 0L
        private set
    @Volatile var running = false
        private set
    /** Android's "start casting?" dialog is open / the projection is being set up */
    @Volatile private var startingAt = 0L
    /** true for at most 30 s, so a lost dialog result cannot block the button for good */
    var starting: Boolean
        get() = startingAt > 0 && System.currentTimeMillis() - startingAt < 30_000
        set(v) { startingAt = if (v) System.currentTimeMillis() else 0L }
    @Volatile var width = 0
    @Volatile var height = 0

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    /** called by the service once the user allowed capturing */
    @Synchronized
    fun start(ctx: Context, resultCode: Int, data: Intent) {
        if (running) { starting = false; return }
        try { startInner(ctx, resultCode, data) } finally { starting = false }
    }

    private fun startInner(ctx: Context, resultCode: Int, data: Intent) {
        stop()
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: return
        val ht = HandlerThread("thorbro-screen").apply { start() }
        val handler = Handler(ht.looper)
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stop() } }, handler)
        val m = ctx.resources.displayMetrics
        // half resolution keeps it smooth over Wi-Fi
        val w = (m.widthPixels / 2) and 0xFFFE
        val h = (m.heightPixels / 2) and 0xFFFE
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var last = 0L
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - last < 80) return@setOnImageAvailableListener // ~12 fps
                last = now
                val plane = img.planes[0]
                val rowPad = plane.rowStride - plane.pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPad / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val crop = if (rowPad > 0) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                frame = ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                frameAt = now
                if (crop !== bmp) crop.recycle()
                bmp.recycle()
            } finally { img.close() }
        }, handler)
        display = p.createVirtualDisplay("thorbro", w, h, m.densityDpi / 2, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        projection = p; reader = r; thread = ht; width = w; height = h
        running = true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { thread?.quitSafely() }
        display = null; reader = null; projection = null; thread = null
    }
}
