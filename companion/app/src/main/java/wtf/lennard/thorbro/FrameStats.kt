package wtf.lennard.thorbro

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** measures how long frames take per page, to find where the UI stutters (read via /api/debug/frames) */
object FrameStats {
    @Volatile var page = "Start"
    private class S { var frames = 0; var janky = 0; var worst = 0.0; var total = 0.0
        val phases = DoubleArray(6) /* input, animation, layout, draw, sync, gpu (only for janky frames) */ }
    private val stats = ConcurrentHashMap<String, S>()
    private val thread by lazy { HandlerThread("frames").apply { start() } }

    fun attach(a: Activity) {
        a.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
            val s = stats.getOrPut(page) { S() }
            synchronized(s) {
                s.frames++; s.total += ms; if (ms > s.worst) s.worst = ms
                if (ms > 20) {
                    s.janky++
                    listOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
                        FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.GPU_DURATION)
                        .forEachIndexed { i, k -> s.phases[i] += m.getMetric(k) / 1e6 }
                }
            }
        }, Handler(thread.looper))
    }

    fun json(): JSONObject {
        val o = JSONObject()
        val names = listOf("input", "animation", "layout", "draw", "sync", "gpu")
        stats.forEach { (p, s) -> synchronized(s) {
            o.put(p, JSONObject().put("frames", s.frames).put("janky", s.janky).put("worstMs", s.worst.toInt())
                .put("avgMs", if (s.frames > 0) Math.round(s.total / s.frames * 10) / 10.0 else 0.0)
                .put("jankyPhasesAvgMs", JSONObject().apply { names.forEachIndexed { i, n -> put(n, if (s.janky > 0) Math.round(s.phases[i] / s.janky * 10) / 10.0 else 0.0) } }))
        } }
        return o
    }

    fun reset() = stats.clear()
}
