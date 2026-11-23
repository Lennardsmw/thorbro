package wtf.lennard.thorbro.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import wtf.lennard.thorbro.Jobs
import wtf.lennard.thorbro.Online

/**
 * Opened by an online invite link (thorbro://join/…, or the web page it is shared as). Asks once, then connects
 * through the encrypted tunnel; Azahar keeps joining 127.0.0.1. Exported, so it never acts without the button.
 */
class JoinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.dataString ?: intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return finish()
        val inv = Online.parse(link)
        setContent {
            ThorTheme {
                var job by remember { mutableStateOf<Jobs.Job?>(null) }
                var msg by remember { mutableStateOf("") }
                var prog by remember { mutableStateOf(0f) }
                var result by remember { mutableStateOf<Pair<String, String>?>(if (inv == null) "Einladung kaputt" to "Der Link ist unvollständig. Bitte die ganze Nachricht noch mal schicken lassen." else null) }
                val first = remember { FocusRequester() }
                LaunchedEffect(job, result) { delay(150); runCatching { first.requestFocus() } }
                LaunchedEffect(job) {
                    val j = job ?: return@LaunchedEffect
                    while (!j.done) { msg = j.message.ifEmpty { msg }; prog = j.progress.toFloat(); delay(250) }
                    result = if (j.error != null) "Ging nicht" to j.error!! else "Verbunden mit ${inv?.name}" to (j.result?.optString("hint") ?: "")
                }
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(480.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF0141519)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${inv?.name ?: "Jemand"} lädt dich online ein", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("3DS-Raum übers Internet · direkt und verschlüsselt", fontSize = 15.sp, color = Muted)
                        val r = result
                        when {
                            r != null -> {
                                Text(r.first, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(r.second, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                BigButton("OK", primary = true, modifier = Modifier.firstFocus(first, true)) { finish() }
                            }
                            job != null -> {
                                Text(msg.ifEmpty { "Verbinde …" }, fontSize = 15.sp)
                                LinearProgressIndicator(progress = { prog }, modifier = Modifier.width(432.dp))
                            }
                            else -> {
                                Text("In Azahar bleibt alles wie immer: Mehrspieler → Raum beitreten → 127.0.0.1.", fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    BigButton("Beitreten", primary = true, modifier = Modifier.firstFocus(first, true)) {
                                        job = Jobs.start("Online beitreten") { Online.join(this@JoinActivity, link, it) }
                                    }
                                    BigButton("Nein") { finish() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
