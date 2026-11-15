package wtf.lennard.thorbro

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player slots for controllers. A slot remembers the controller by its hardware descriptor (stable),
 * and whenever controllers connect, ThorBro writes the current Android device numbers into the
 * emulators' controller settings, so a reconnected or new pad works without assigning it again.
 * Button mappings in the emulator are kept; only the device of each player changes.
 */
object Players {
    const val SLOTS = 4
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var lastApply: String = ""
        private set

    /** [number] is Android's controller (player) number, which Dolphin uses in "Android/<number>/<name>" */
    class Pad(val id: Int, val name: String, val descriptor: String, val builtIn: Boolean, val number: Int)

    fun pads(): List<Pad> = InputDevice.getDeviceIds().toList().mapNotNull { id: Int -> InputDevice.getDevice(id) }
        .filter { d -> !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) }
        .map { d -> Pad(d.id, d.name, d.descriptor, !d.isExternal, d.controllerNumber) }

    private fun slots(ctx: Context): JSONObject = runCatching { JSONObject(Prefs.of(ctx).getString("players", "{}")!!) }.getOrDefault(JSONObject())

    fun assigned(ctx: Context): List<JSONObject?> = (1..SLOTS).map { slots(ctx).optJSONObject("p$it") }

    fun assign(ctx: Context, slot: Int, pad: InputDevice) {
        val s = slots(ctx)
        // a controller can only be one player
        (1..SLOTS).forEach { if (s.optJSONObject("p$it")?.optString("descriptor") == pad.descriptor) s.remove("p$it") }
        s.put("p$slot", JSONObject().put("descriptor", pad.descriptor).put("name", pad.name))
        Prefs.of(ctx).edit().putString("players", s.toString()).apply()
        apply(ctx)
    }

    fun clear(ctx: Context, slot: Int) {
        val s = slots(ctx); s.remove("p$slot")
        Prefs.of(ctx).edit().putString("players", s.toString()).apply()
        apply(ctx)
    }

    fun overview(ctx: Context): JSONObject {
        val connected = pads()
        val arr = JSONArray()
        assigned(ctx).forEachIndexed { i, a ->
            val live = a?.let { x -> connected.firstOrNull { it.descriptor == x.optString("descriptor") } }
            arr.put(JSONObject().put("slot", i + 1).put("name", a?.optString("name")).put("connected", live != null).put("number", live?.number))
        }
        return JSONObject().put("players", arr).put("lastApply", lastApply)
            .put("pads", JSONArray(connected.map { JSONObject().put("id", it.id).put("name", it.name).put("builtIn", it.builtIn).put("number", it.number) }))
    }

    // ---------- writing emulator settings ----------

    private val dolphin = File("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config")

    /** writes the current device of every assigned, connected player into the emulators */
    @Synchronized
    fun apply(ctx: Context) {
        val connected = pads()
        val devices = assigned(ctx).map { a -> a?.let { x -> connected.firstOrNull { it.descriptor == x.optString("descriptor") } } }
        val done = mutableListOf<String>()
        if (dolphin.isDirectory) {
            if (rewriteIni(File(dolphin, "GCPadNew.ini"), "GCPad", devices)) done += "Dolphin GameCube"
            if (rewriteIni(File(dolphin, "WiimoteNew.ini"), "Wiimote", devices)) done += "Dolphin Wii"
        }
        lastApply = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()) + " · " +
            (if (done.isEmpty()) "nichts zu ändern" else done.joinToString(", ") + " aktualisiert")
    }

    /**
     * Replaces "Device = Android/<n>/<name>" in [Section1..4] for players with a connected pad.
     * Sections that have no button mapping yet get the mapping of section 1 (same layout for all pads).
     */
    private fun rewriteIni(f: File, prefix: String, devices: List<Pad?>): Boolean {
        if (!f.isFile || devices.all { it == null }) return false
        val text = f.readText()
        val sections = LinkedHashMap<String, MutableList<String>>()
        var cur = ""
        sections[cur] = mutableListOf()
        text.lines().forEach { line ->
            val h = Regex("^\\[(.+)]$").find(line.trim())
            if (h != null) { cur = h.groupValues[1]; sections.getOrPut(cur) { mutableListOf() } } else sections[cur]!!.add(line)
        }
        val template = sections["${prefix}1"]?.filter { !it.startsWith("Device") && it.isNotBlank() } ?: emptyList()
        var changed = false
        devices.forEachIndexed { i, pad ->
            if (pad == null) return@forEachIndexed
            val name = "$prefix${i + 1}"
            val lines = sections.getOrPut(name) { mutableListOf() }
            if (pad.number <= 0) return@forEachIndexed // not a numbered gamepad: leave Dolphin's entry alone
            val dev = "Device = Android/${pad.number}/${pad.name}"
            val idx = lines.indexOfFirst { it.startsWith("Device") }
            if (idx >= 0) { if (lines[idx] != dev) { lines[idx] = dev; changed = true } } else { lines.add(0, dev); changed = true }
            if (lines.none { it.startsWith("Buttons/") } && template.isNotEmpty()) { lines.addAll(template); changed = true }
        }
        if (!changed) return false
        // keep one backup per day before ThorBro's first change
        val bak = File(f.parentFile, f.name + ".thorbro-" + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()))
        if (!bak.exists()) f.copyTo(bak)
        val out = StringBuilder()
        sections.forEach { (k, v) ->
            if (k.isNotEmpty()) out.append('[').append(k).append("]\n")
            v.dropLastWhile { it.isBlank() }.forEach { out.append(it).append('\n') }
        }
        f.writeText(out.toString())
        return true
    }

    // ---------- follow connects/disconnects ----------

    private var listener: InputManager.InputDeviceListener? = null

    fun watch(ctx: Context) {
        if (listener != null) return
        val im = ctx.getSystemService(InputManager::class.java)
        val run = Runnable { runCatching { apply(ctx) } }
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { handler.removeCallbacks(run); handler.postDelayed(run, 1500) }
            override fun onInputDeviceRemoved(id: Int) {}
            override fun onInputDeviceChanged(id: Int) { handler.removeCallbacks(run); handler.postDelayed(run, 1500) }
        }
        im.registerInputDeviceListener(l, handler)
        listener = l
        handler.postDelayed(run, 3000)
    }

    fun unwatch(ctx: Context) {
        listener?.let { ctx.getSystemService(InputManager::class.java).unregisterInputDeviceListener(it) }
        listener = null
    }
}
