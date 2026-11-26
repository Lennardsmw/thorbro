package wtf.lennard.thorbro.ui

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Netplay

/**
 * Opened from an invite notification: joins right away (Wi-Fi Direct, game transfer, emulator start) and shows only the
 * progress. It is a foreground activity, so starting the emulator is allowed; it closes itself once the game runs.
 */
class InviteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inv = runCatching { JSONObject(intent.getStringExtra("invite") ?: "") }.getOrNull() ?: return finish()
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification", 0))
        val share = inv.optString("mode") == "share"
        val noGame = share || inv.optString("mode") == "server" // nothing starts: show the result
        val job = Jobs.start("Einladung annehmen") { Netplay.accept(this, inv, it) }
        setContent {
            ThorTheme {
                var msg by remember { mutableStateOf("Verbinde mit ${inv.optString("name")} …") }
                var prog by remember { mutableStateOf(0f) }
                var result by remember { mutableStateOf<Pair<String, String>?>(null) }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    while (!job.done) { msg = job.message.ifEmpty { msg }; prog = job.progress.toFloat(); delay(250) }
                    if (job.error == null && !noGame) { finish(); return@LaunchedEffect } // the game is starting
                    result = if (job.error != null) "Ging nicht" to job.error!! else (if (share) "Spiel erhalten" else "Verbunden") to (job.result?.optString("hint") ?: "")
                    delay(150); runCatching { first.requestFocus() }
                }
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(460.dp).clip(RoundedCornerShape(22.dp)).background(Sheet).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when { share -> "${inv.optString("name")} schickt dir ein Spiel"; noGame -> "Spielserver von ${inv.optString("name")}"; else -> "${inv.optString("name")} lädt ein" },
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(inv.optString("game"), fontSize = 16.sp, color = Muted)
                        val r = result
                        if (r == null) {
                            Text(msg, fontSize = 15.sp)
                            LinearProgressIndicator(progress = { prog }, modifier = Modifier.width(412.dp))
                        } else {
                            Text(r.first, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(r.second, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            BigButton("OK", primary = true, modifier = Modifier.firstFocus(first, true)) { finish() }
                        }
                    }
                }
            }
        }
    }
}
