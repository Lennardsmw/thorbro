package wtf.lennard.thorbro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small status screen on the Thor: address, pairing code, permissions, start/stop. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var toggle: Button
    private lateinit var permAllFiles: Button
    private lateinit var permOverlay: Button
    private lateinit var permInstall: Button
    private lateinit var updateButton: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 4, 0, pad / 4)
        }.also { col.addView(it) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }.also { col.addView(it) }

        text(26f, true).text = "ThorBro"
        status = text(16f)
        address = text(22f, true).apply { setTextIsSelectable(true) }
        text(14f).text = "Kopplungscode (im Browser oder in der Desktop-App eingeben):"
        code = text(30f, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.START }
        toggle = button("") { if (ThorService.running) ThorService.stop(this) else ThorService.start(this); later() }
        button("Neuen Code erzeugen") { Prefs.newToken(this); refresh() }
        updateButton = button("Nach Updates suchen") { checkUpdate() }

        text(18f, true).text = "Berechtigungen"
        permAllFiles = button("") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        permOverlay = button("") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permInstall = button("") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        col.addView(CheckBox(this).apply {
            text = "Beim Hochfahren automatisch starten"
            isChecked = Prefs.autostart(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAutostart(this@MainActivity, on) }
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!ThorService.running) ThorService.start(this)
    }

    override fun onResume() {
        super.onResume()
        later()
        // an install dialog that did not come to the front earlier
        Installer.pendingConfirm?.let { runCatching { startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun checkUpdate() {
        updateButton.isEnabled = false
        updateButton.text = "Suche Update …"
        kotlin.concurrent.thread {
            val r = Updater.update(applicationContext)
            ui.post {
                updateButton.isEnabled = true
                updateButton.text = when {
                    r.has("error") -> "Update: " + r.getString("error")
                    r.has("session") -> "Installiere ${r.getString("latest")} …"
                    else -> "Aktuell (${r.getString("current")}) – erneut suchen"
                }
            }
        }
    }

    private fun later() { refresh(); ui.postDelayed({ refresh() }, 800) }

    private fun refresh() {
        val p = Device.permissions(this)
        val on = ThorService.running
        status.text = if (on) "Server läuft. Im Browser öffnen:" else "Server ist gestoppt."
        address.text = if (on) "http://${Device.ip() ?: "keine WLAN-Adresse"}:${ThorService.PORT}" else ""
        code.text = Prefs.token(this)
        toggle.text = if (on) "Server stoppen" else "Server starten"
        fun mark(ok: Boolean, label: String) = (if (ok) "✓ " else "✗ ") + label
        permAllFiles.text = mark(p.getBoolean("allFiles"), "Zugriff auf alle Dateien (Spiele hochladen)")
        permOverlay.text = mark(p.getBoolean("overlay"), "Über anderen Apps anzeigen (Apps fernstarten)")
        permInstall.text = mark(p.getBoolean("install"), "Unbekannte Apps installieren (APKs)")
    }
}
