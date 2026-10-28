package com.capri.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.concurrent.thread


/** Her brain: pick a model (smallest first), download it, use it, talk to her. */
@Composable
fun BrainSection(running: Boolean, card: @Composable (String, String, @Composable () -> Unit) -> Unit,
                 button: @Composable (String, Color, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dl by DownloadService.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }  // re-reads files / status every second
    var status by remember { mutableStateOf(CapriService.brainStatus) }
    var reply by remember { mutableStateOf(CapriService.lastReply) }
    var name by remember { mutableStateOf(prefs.userName) }
    var ask by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf("") }
    var micOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    val ram = remember { ModelStore.ramGb(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
            status = CapriService.brainStatus
            reply = CapriService.lastReply
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) thread(name = "capri-import") { importGguf(ctx, uri) { importing = it } }
    }

    card("Pick a brain", "She downloads it to your phone and runs it offline. Smallest first. " +
        "Your phone: ~$ram GB RAM. Now: $status") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            @Suppress("UNUSED_EXPRESSION") tick
            val active = prefs.brain
            for (m in ModelStore.all(ctx)) {
                val have = m.url.isEmpty() || ModelStore.installed(ctx, m)
                val partial = ModelStore.part(ctx, m).let { if (it.exists()) it.length() else 0L }
                val busy = dl.id == m.id && dl.error.isEmpty()
                Column(
                    Modifier.fillMaxWidth().border(1.dp, if (active == m.id && have) GREEN else EDGE)
                        .background(PANEL).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, color = TEXT, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("%.2f GB".format(m.bytes / 1e9), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
                    }
                    Text(m.note + (if (m.capri) "  ·  CAPRI" else "") +
                        (if (m.minRamGb > 0) "  ·  ${m.minRamGb}+ GB RAM" else ""),
                        color = if (m.minRamGb > ram) PINK else MUTED, fontSize = 12.sp)
                    if (busy || (dl.id == m.id && dl.total > 0)) {
                        LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                            modifier = Modifier.fillMaxWidth(), color = PINK, trackColor = EDGE)
                        Text(if (busy) "%.2f / %.2f GB  ·  %.1f MB/s".format(dl.done / 1e9, dl.total / 1e9, dl.speed / 1e6)
                            else "stopped: ${dl.error}", color = MUTED, fontSize = 11.sp, fontFamily = SANS)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            have && active == m.id -> Text("IN USE", color = GREEN, fontFamily = SANS,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                            have -> button("Use", GREEN) { prefs.brain = m.id; CapriService.brainChanged(ctx); tick++ }
                            busy -> button("Pause", MUTED) { DownloadService.cancel(ctx) }
                            else -> button(if (partial > 0) "Resume" else "Download", PINK) { DownloadService.start(ctx, m) }
                        }
                        if (have || partial > 0) button("Delete", Color(0xFF3A2A44)) {
                            if (busy) DownloadService.cancel(ctx)
                            ModelStore.file(ctx, m).delete(); ModelStore.part(ctx, m).delete()
                            if (prefs.brain == m.id) { prefs.brain = ""; CapriService.brainChanged(ctx) }
                            tick++
                        }
                    }
                    if (m.hasEyes) {  // her eyes for this brain: optional, lets her look at your screen
                        val eyesOn = ModelStore.eyesInstalled(ctx, m)
                        val eyesPart = java.io.File(ModelStore.eyes(ctx, m).path + ".part").let { if (it.exists()) it.length() else 0L }
                        val eyesBusy = dl.id == m.id + ":eyes" && dl.error.isEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, EDGE).padding(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("+ EYES  ·  %.0f MB".format(m.eyesBytes / 1e6), color = CYAN, fontFamily = SANS,
                                    fontSize = 13.sp)
                                Text(if (eyesOn) "she can look at your screen" else if (eyesBusy) "%.0f / %.0f MB".format(
                                    dl.done / 1e6, dl.total / 1e6) else "lets her look at your screen (Android 11+)",
                                    color = MUTED, fontSize = 11.sp)
                            }
                            when {
                                eyesOn -> button("Remove", Color(0xFF3A2A44)) {
                                    ModelStore.eyes(ctx, m).delete(); CapriService.brainChanged(ctx); tick++
                                }
                                eyesBusy -> button("Pause", MUTED) { DownloadService.cancel(ctx) }
                                else -> button(if (eyesPart > 0) "Resume" else "Get", CYAN) {
                                    DownloadService.start(ctx, m, eyes = true)
                                }
                            }
                        }
                    }
                }
            }
            button("Add your own .gguf", CYAN) { pick.launch(arrayOf("application/octet-stream", "*/*")) }
            if (importing.isNotEmpty()) Text(importing, color = MUTED, fontSize = 12.sp)
        }
    }

    card("TALK TO HER", if (micOk) "Hold her on screen and talk, let go when you're done. Or type here." +
        if (running) "" else " (summon her first)"
    else "Allow the mic so she can hear you while you hold her. Typing works without it.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!micOk) button("Allow mic", PINK) { mic.launch(Manifest.permission.RECORD_AUDIO) }
            val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TEXT, unfocusedTextColor = TEXT,
                focusedBorderColor = PINK, unfocusedBorderColor = EDGE, cursorColor = PINK,
                focusedLabelColor = CYAN, unfocusedLabelColor = MUTED)
            OutlinedTextField(name, { name = it; prefs.userName = it.trim() }, label = { Text("What she calls you") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ask, { ask = it }, label = { Text("Say something") }, colors = colors,
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                button("Send", PINK) {
                    if (ask.isNotBlank() && running) {
                        ctx.startService(Intent(ctx, CapriService::class.java).setAction(CapriService.ACTION_ASK)
                            .putExtra("text", ask.trim()))
                        ask = ""
                    }
                }
                button("Clear chat", Color(0xFF3A2A44)) { prefs.chat = "[]" }
            }
            if (reply.isNotEmpty()) Text("CAPRI: $reply", color = CYAN, fontSize = 14.sp)
        }
    }

    // her long-term memory: everything she wrote down, yours to read and delete
    val memory = remember { Memory.get(ctx) }
    var remembers by remember { mutableStateOf(prefs.remember) }
    var sure by remember { mutableStateOf(false) }
    val facts = remember(tick) { memory.all() }
    card("HER MEMORY", "What she remembers about you and the people in your life: from your chats and what you show " +
        "her. Only on this phone, never sent anywhere. ${facts.size} memories.") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remember things", color = TEXT, fontFamily = SANS, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(remembers, { remembers = it; prefs.remember = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Color.White,
                        checkedTrackColor = INK))
            }
            if (facts.isEmpty()) Text("Nothing yet. Tell her about your day, or hold her and say \"look at this\".",
                color = MUTED, fontSize = 12.sp)
            for (f in facts.take(60)) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().border(1.dp, EDGE).padding(start = 10.dp)) {
                Text("> " + f.text, color = TEXT, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton({ memory.forget(f.text); tick++ }) {
                    Text("x", color = PINK, fontFamily = SANS, fontSize = 16.sp)
                }
            }
            if (facts.isNotEmpty()) button(if (sure) "Tap again: wipe it all" else "Forget everything",
                if (sure) PINK else Color(0xFF3A2A44)) {
                if (sure) { memory.forgetAll(); sure = false; tick++ } else sure = true
            }
        }
    }
}

/** Copies a picked .gguf into her models folder (the file has to be on the phone for llama.cpp to map it). */
private fun importGguf(ctx: android.content.Context, uri: Uri, progress: (String) -> Unit) {
    val cr = ctx.contentResolver
    var name = "model.gguf"
    var size = -1L
    cr.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
        }
    }
    if (!name.endsWith(".gguf", true)) { progress("That's not a .gguf file."); return }
    if ("mmproj" in name.lowercase()) { progress("That's a vision add-on (mmproj), not a brain."); return }
    val out = File(ModelStore.dir(ctx), name)
    val tmp = File(out.path + ".part")
    try {
        cr.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (done - last > 50_000_000) { last = done; progress("Copying $name: %.2f GB".format(done / 1e9) +
                        if (size > 0) " of %.2f".format(size / 1e9) else "") }
                }
            }
        }
        tmp.renameTo(out)
        progress("Added $name. Tap Use on it.")
    } catch (e: Exception) {
        tmp.delete()
        progress("Couldn't copy it: ${e.message}")
    }
}
