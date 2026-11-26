package wtf.lennard.thorbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.lennard.thorbro.Romm

/** add (or change) the RomM server right on the Thor: address → code to approve in RomM → connected */
@Composable
fun RommServerDialog(onDone: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { Romm.status(ctx) }
    var url by remember { mutableStateOf(st.optString("url").ifEmpty { "https://games.lennard.wtf" }) }
    var lan by remember { mutableStateOf(st.optString("lanUrl")) }
    var code by remember { mutableStateOf<JSONObject?>(st.optJSONObject("pending")) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    // while a code is shown: wait until RomM has approved it
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        while (true) {
            delay(2000)
            val s = withContext(Dispatchers.IO) { Romm.status(ctx) }
            if (s.optBoolean("connected")) { onDone("Mit RomM verbunden"); return@LaunchedEffect }
            if (s.isNull("pending") || s.optJSONObject("pending") == null) { code = null; err = "Code abgelaufen – nochmal verbinden"; return@LaunchedEffect }
        }
    }

    DialogOpen()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.width(620.dp).clip(RoundedCornerShape(22.dp)).background(Sheet).padding(24.dp)) {
            Text(if (st.optBoolean("connected")) "RomM-Server" else "RomM-Server hinzufügen", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val c = code
            if (c != null) {
                Spacer(Modifier.height(12.dp))
                Text("In RomM bestätigen (am Handy oder PC):", color = Muted)
                Text(c.optString("verifyUrl"), fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                Text(c.optString("userCode"), fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
                Text("Warte auf Bestätigung …", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                BigButton("Abbrechen", modifier = Modifier.fillMaxWidth().firstFocus(first, true), onClick = onClose)
                return@Column
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("Adresse") },
                modifier = Modifier.fillMaxWidth().firstFocus(first, true))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(lan, { lan = it.trim() }, singleLine = true, label = { Text("Heimnetz-Adresse (leer = aus)") },
                modifier = Modifier.fillMaxWidth())
            if (err.isNotEmpty()) Text(err, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            BigButton(if (busy) "Verbinde …" else if (st.optBoolean("connected")) "Neu verbinden" else "Verbinden", primary = true,
                enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                busy = true; err = ""
                val base = if (url.startsWith("http")) url else "https://$url"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        Romm.setLanUrl(ctx, lan)
                        runCatching { Romm.connect(ctx, base) }.getOrElse { JSONObject().put("error", it.message ?: "RomM nicht erreichbar") }
                    }
                    busy = false
                    if (r.has("error")) err = r.optString("error") else code = r
                }
            }
            if (st.optBoolean("connected")) {
                Spacer(Modifier.height(10.dp))
                BigButton("Nur Heimnetz-Adresse speichern", modifier = Modifier.fillMaxWidth()) { Romm.setLanUrl(ctx, lan); onDone("Heimnetz-Adresse gespeichert") }
                Spacer(Modifier.height(10.dp))
                BigButton("Trennen", modifier = Modifier.fillMaxWidth()) { Romm.disconnect(ctx); onDone("Von RomM getrennt") }
            }
            Spacer(Modifier.height(10.dp))
            BigButton("Zurück", modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}
