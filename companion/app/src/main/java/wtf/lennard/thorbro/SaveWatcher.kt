package wtf.lennard.thorbro

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Notices when a game saves (in-game save or autosave) and syncs right away:
 * a local snapshot plus the upload to RomM. It waits until the files stopped changing,
 * and only uploads on Wi-Fi / unmetered networks.
 */
object SaveWatcher {
    private const val CHECK_MS = 30_000L
    @Volatile private var running = false
    @Volatile var last: JSONObject = JSONObject().put("state", "aus")
        private set

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun stamps(ctx: Context): Map<String, String> =
        RommSync.localSaves(ctx).associate { s ->
            val f = s.file
            val stamp = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.joinToString(",") { "${it.length()}:${it.lastModified()}" } else "${f.length()}:${f.lastModified()}"
            f.path to stamp
        }

    fun start(ctx: Context) {
        if (running) return
        running = true
        thread(name = "thorbro-savewatch", isDaemon = true) {
            var known = runCatching { stamps(ctx) }.getOrDefault(emptyMap())
            var pending = false
            last = JSONObject().put("state", "beobachtet ${known.size} Spielstände")
            var rounds = 0
            while (running) {
                Thread.sleep(CHECK_MS)
                // every 5 minutes: fetch saves made on other devices (e.g. the Mac)
                if (++rounds % 10 == 0 && RommSync.auto(ctx) && onWifi(ctx) && Romm.status(ctx).optBoolean("connected")) {
                    val r = runCatching { RommSync.pullSaves(ctx) }.getOrNull()
                    if (r != null && r.optInt("downloaded") > 0) {
                        last = JSONObject().put("at", System.currentTimeMillis()).put("state", "${r.optInt("downloaded")} Spielstand/-stände von RomM geholt")
                        known = runCatching { stamps(ctx) }.getOrDefault(known)
                    }
                    // errors stay visible in the sync status instead of vanishing
                    val errs = r?.optJSONArray("errors")
                    if (errs != null && errs.length() > 0)
                        last = JSONObject().put("at", System.currentTimeMillis()).put("state", "Fehler beim Holen: ${errs.optString(0)}").put("errors", errs)
                }
                val now = runCatching { stamps(ctx) }.getOrNull() ?: continue
                val changed = now != known
                known = now
                if (changed) { pending = true; continue } // wait one more round until writing is finished
                if (!pending) continue
                pending = false
                runCatching { Saves.backup(ctx, null) }
                val res = JSONObject().put("at", System.currentTimeMillis())
                if (RommSync.auto(ctx) && Romm.status(ctx).optBoolean("connected")) {
                    if (onWifi(ctx)) {
                        runCatching { RommSync.pullSaves(ctx) }
                        val r = runCatching { RommSync.syncSaves(ctx, null) }.getOrElse { JSONObject().put("error", it.message) }
                        res.put("state", if (r.has("error")) "Fehler: ${r.optString("error")}" else "synchronisiert: ${r.optInt("uploaded")} hochgeladen").put("result", r)
                    } else {
                        pending = true // try again when Wi-Fi is back
                        res.put("state", "Spielstand geändert, warte auf WLAN")
                    }
                } else res.put("state", "lokal gesichert (RomM-Sync aus)")
                last = res
            }
        }
    }

    fun stop() { running = false }
}
