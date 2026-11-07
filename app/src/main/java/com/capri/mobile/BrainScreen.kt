package com.capri.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.concurrent.thread

/** Her brain: one on the phone (pick, download, use), or LM Studio on your PC. Then what she remembers. */
@Composable
fun BrainTab() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var page by remember { mutableIntStateOf(if (prefs.brain == ModelStore.LM.id) 1 else if (prefs.brain == ModelStore.NV.id) 2 else 0) }
    var status by remember { mutableStateOf(CapriService.brainStatus) }
    LaunchedEffect(Unit) {
        while (true) {
            status = CapriService.brainStatus
            delay(500)
        }
    }
    Segments(listOf("This phone", "LM Studio", "NVIDIA"), page) { page = it }
    if (CapriService.running) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Box(Modifier.size(7.dp).background(if ("online" in status) GREEN else PINK, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(status, color = MUTED, fontFamily = SANS, fontSize = 12.5.sp)
    }
    when (page) {
        0 -> {
            KeepSafe()
            PhoneBrains(prefs)
            EarsCard()
        }
        1 -> LmStudioCard(prefs)
        else -> NvidiaCard(prefs)
    }
    MemoryCard(prefs)
}

/** Brains in a folder that survives deleting the app; a new install finds them there. */
@Composable
private fun KeepSafe() {
    val ctx = LocalContext.current
    var safe by remember { mutableStateOf(ModelStore.canShare(ctx)) }
    var moving by remember { mutableStateOf("") }
    val old = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { safe = ModelStore.canShare(ctx) }
    LaunchedEffect(Unit) {
        var was = safe
        while (true) {
            delay(700)
            safe = ModelStore.canShare(ctx)
            if (safe && !was) thread(name = "capri-move") {  // just allowed: bring her brains over, pick up any left from before
                ModelStore.moveToShared(ctx) { moving = it }
                CapriService.brainChanged(ctx)
            }
            was = safe
        }
    }
    if (safe) Card("Brains are safe") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Kept", GREEN)
            Spacer(Modifier.width(10.dp))
            Text("In ${ModelStore.sharedPath} on your phone. They stay if you delete the app, and a new install finds them.",
                color = MUTED, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        if (moving.isNotEmpty()) Text(moving, color = INK, fontFamily = SANS, fontSize = 12.sp)
    } else Card("Keep brains safe", "Deleting the app deletes her brains. Keep them in a Capri folder on your phone: they " +
        "stay when you delete the app, and a new install picks them up with no downloading. Had Capri before? Tap this and she finds them.") {
        CapriButton("Keep my brains safe", Modifier.fillMaxWidth(), color = GREEN) {
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            } else old.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

@Composable
private fun PhoneBrains(prefs: Prefs) {
    val ctx = LocalContext.current
    val dl by DownloadService.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }  // re-reads files every second
    var importing by remember { mutableStateOf("") }
    val ram = remember { ModelStore.ramGb(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) thread(name = "capri-import") { importGguf(ctx, uri) { importing = it } }
    }
    Card("Pick a brain", "She downloads it and runs it offline, on your phone. This phone has about $ram GB of RAM.") {
        @Suppress("UNUSED_EXPRESSION") tick
        val active = prefs.brain
        for (m in ModelStore.all(ctx)) {
            val have = m.url.isEmpty() || ModelStore.installed(ctx, m)
            val partial = ModelStore.part(ctx, m).let { if (it.exists()) it.length() else 0L }
            val busy = dl.id == m.id && dl.error.isEmpty()
            val inUse = have && active == m.id
            Column(Modifier.fillMaxWidth().background(PANEL_HI, chamfer(10.dp)).border(1.dp, if (inUse) GREEN else EDGE, chamfer(10.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("%.1f GB".format(m.bytes / 1e9), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
                }
                Text(m.note + (if (m.minRamGb > 0) "  ·  ${m.minRamGb}+ GB RAM" else ""),
                    color = if (m.minRamGb > ram) PINK else MUTED, fontFamily = SANS, fontSize = 12.sp)
                if (busy || (dl.id == m.id && dl.total > 0)) {
                    LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                        modifier = Modifier.fillMaxWidth(), color = PINK, trackColor = EDGE)
                    Text(if (busy) "%.2f / %.2f GB  ·  %.1f MB/s".format(dl.done / 1e9, dl.total / 1e9, dl.speed / 1e6)
                        else "stopped: ${dl.error}", color = MUTED, fontFamily = SANS, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        inUse -> Pill("In use", GREEN)
                        have -> CapriButton("Use", color = GREEN) { prefs.brain = m.id; CapriService.brainChanged(ctx); tick++ }
                        busy -> CapriButton("Pause", quiet = true) { DownloadService.cancel(ctx) }
                        else -> CapriButton(if (partial > 0) "Resume" else "Download") { DownloadService.start(ctx, m) }
                    }
                    if (have || partial > 0) CapriButton("Delete", quiet = true, color = MUTED) {
                        if (busy) DownloadService.cancel(ctx)
                        ModelStore.file(ctx, m).delete(); ModelStore.part(ctx, m).delete()
                        if (prefs.brain == m.id) { prefs.brain = ""; CapriService.brainChanged(ctx) }
                        tick++
                    }
                }
                if (m.hasEyes) {  // her eyes for this brain: optional, lets her look at your screen
                    val eyesOn = ModelStore.eyesInstalled(ctx, m)
                    val eyesPart = File(ModelStore.eyes(ctx, m).path + ".part").let { if (it.exists()) it.length() else 0L }
                    val eyesBusy = dl.id == m.id + ":eyes" && dl.error.isEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, EDGE, chamfer(8.dp)).padding(9.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("+ Eyes  ·  %.0f MB".format(m.eyesBytes / 1e6), color = INK, fontFamily = SANS, fontSize = 13.sp)
                            Text(if (eyesOn) "she can look at your screen" else if (eyesBusy) "%.0f / %.0f MB".format(
                                dl.done / 1e6, dl.total / 1e6) else "lets her look at your screen (Android 11+)",
                                color = MUTED, fontFamily = SANS, fontSize = 11.sp)
                        }
                        when {
                            eyesOn -> CapriButton("Remove", quiet = true, color = MUTED) {
                                ModelStore.eyes(ctx, m).delete(); CapriService.brainChanged(ctx); tick++
                            }
                            eyesBusy -> CapriButton("Pause", quiet = true) { DownloadService.cancel(ctx) }
                            else -> CapriButton(if (eyesPart > 0) "Resume" else "Get", color = GREEN) {
                                DownloadService.start(ctx, m, eyes = true)
                            }
                        }
                    }
                }
            }
        }
        CapriButton("Add your own .gguf", Modifier.fillMaxWidth(), quiet = true) { pick.launch(arrayOf("application/octet-stream", "*/*")) }
        if (importing.isNotEmpty()) Text(importing, color = MUTED, fontFamily = SANS, fontSize = 12.sp)
    }
}

/** LM Studio on your PC: any model you can run there, over your own Wi-Fi. A vision model lets her see your screen. */
@Composable
private fun LmStudioCard(prefs: Prefs) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(prefs.lmUrl) }
    var models by remember { mutableStateOf<List<LmStudio.Info>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(prefs.lmModel) }
    var using by remember { mutableStateOf(prefs.brain == ModelStore.LM.id) }

    fun connect(address: String) {
        working = true
        note = "Connecting..."
        thread(name = "capri-lm") {
            val client = LmStudio(address)
            val found = runCatching { client.models() }.getOrDefault(emptyList())
            models = found
            working = false
            note = if (found.isEmpty()) "Nothing answered at ${client.base}. Is its server on, with \"Serve on Local Network\"?"
            else "Connected to ${client.base}"
            if (found.isNotEmpty()) { url = client.base; prefs.lmUrl = client.base }
        }
    }
    LaunchedEffect(Unit) { if (url.isNotBlank()) connect(url) }

    Card("LM Studio on your PC", "Run any model on your PC and she thinks with it, over your own Wi-Fi. Nothing to download here.") {
        val steps = listOf(
            "On your PC, open LM Studio and load a model. A vision model lets her see your screen.",
            "Developer tab: start the server and turn on \"Serve on Local Network\".",
            "Phone and PC on the same Wi-Fi, then tap Find.",
        )
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", color = INK, fontFamily = SANS, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                Text(s, color = MUTED, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        OutlinedTextField(url, { url = it }, label = { Text("Address, like 192.168.1.5") }, singleLine = true,
            colors = capriField(), shape = chamfer(9.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton("Find on Wi-Fi", Modifier.weight(1f), quiet = true, enabled = !working) {
                working = true
                note = "Looking on your Wi-Fi..."
                thread(name = "capri-lm-find") {
                    val hit = runCatching { LmStudio.find() }.getOrNull()
                    if (hit == null) {
                        working = false
                        note = "Didn't find it. Check \"Serve on Local Network\", or type your PC's address."
                    } else connect(hit)
                }
            }
            CapriButton("Connect", Modifier.weight(1f), enabled = !working && url.isNotBlank()) { connect(url) }
        }
        if (note.isNotEmpty()) Text(note, color = if (models.isEmpty()) MUTED else INK, fontFamily = SANS, fontSize = 12.sp, lineHeight = 17.sp)
        for (m in models) {
            val picked = model == m.id || (model.isEmpty() && m === models.first())
            Row(Modifier.fillMaxWidth().background(PANEL_HI, chamfer(9.dp)).border(1.dp, if (picked) GREEN else EDGE, chamfer(9.dp))
                .clickable { model = m.id; prefs.lmModel = m.id; if (using) CapriService.brainChanged(ctx) }.padding(11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(m.id, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                if (m.vision) Text("Sees", color = PINK, fontFamily = SANS, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                if (m.loaded) Text("Loaded", color = CYAN, fontFamily = SANS, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (models.isNotEmpty()) {
            if (using) Pill("In use", GREEN)
            else CapriButton("Use LM Studio", Modifier.fillMaxWidth(), color = GREEN) {
                prefs.brain = ModelStore.LM.id
                using = true
                CapriService.brainChanged(ctx)
            }
        }
    }
}

/** NVIDIA's cloud: a huge model for free, with a key you make yourself on their site. */
@Composable
private fun NvidiaCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var key by remember { mutableStateOf(prefs.nvKey) }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf(prefs.brain == ModelStore.NV.id) }
    Card("Nemotron Ultra 550B", "A huge model in NVIDIA's cloud, free with your own key. It needs internet, and what you say to her goes to NVIDIA. " +
        "She sees through NVIDIA's vision model on the same key, with nothing to download.") {
        val steps = listOf("Open NVIDIA's page and make a free account.", "Tap \"Get API Key\" there and copy it.", "Paste it here.")
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", color = PINK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                Text(s, color = MUTED, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        CapriButton("Get my free key", Modifier.fillMaxWidth(), quiet = true) { uri.openUri(ModelStore.NV_KEY_PAGE) }
        OutlinedTextField(key, { key = it.trim(); prefs.nvKey = key; note = "" }, label = { Text("API key, starts with nvapi-") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors = capriField(), shape = chamfer(9.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton("Test key", Modifier.weight(1f), quiet = true, enabled = !working && key.isNotBlank()) {
                working = true
                note = "Testing..."
                thread(name = "capri-nv") {
                    note = runCatching {
                        LmStudio(ModelStore.NV_URL, key).chat(ModelStore.NV_MODEL, arrayOf("user"), arrayOf("Say hi in one word."), null, 8, 0.2f) { true }
                        "Your key works."
                    }.getOrElse { "That didn't work: ${it.message}" }
                    working = false
                }
            }
            if (using) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Pill("In use", GREEN) }
            else CapriButton("Use it", Modifier.weight(1f), color = GREEN, enabled = key.isNotBlank()) {
                prefs.brain = ModelStore.NV.id
                using = true
                CapriService.brainChanged(ctx)
            }
        }
        if (note.isNotEmpty()) Text(note, color = INK, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 17.sp)
        var glance by remember { mutableStateOf(prefs.cloudGlance) }
        Toggle("Let her look on her own", "with this on, React on her own works in every app here too: your screen, " +
            "chats and DMs included, goes to NVIDIA every minute or two. Off: only public feeds like TikTok, and only their text.", glance) {
            glance = it
            prefs.cloudGlance = it
        }
    }
}

/** Her own ears, the same as the desktop's. Without them she uses the phone's speech recognizer. */
@Composable
private fun EarsCard() {
    val ctx = LocalContext.current
    val dl by DownloadService.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val m = ModelStore.ASR
    @Suppress("UNUSED_EXPRESSION") tick
    val ready = ModelStore.earsReady(ctx)
    val total = m.bytes + m.eyesBytes
    val busy = (dl.id == m.id || dl.id == m.id + ":eyes") && dl.error.isEmpty()
    Card("Her ears", "Qwen3-ASR: hears names, slang and accents in 52 languages, right on your phone. Without it she uses your phone's speech recognizer.") {
        if (busy) {
            LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f }, modifier = Modifier.fillMaxWidth(), color = PINK, trackColor = EDGE)
            Text("%.0f / %.0f MB".format(dl.done / 1e6, dl.total / 1e6), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                ready -> Pill("Ready", GREEN)
                busy -> CapriButton("Pause", quiet = true) { DownloadService.cancel(ctx) }
                else -> CapriButton("Download  ·  %.1f GB".format(total / 1e9)) {
                    DownloadService.start(ctx, m, eyes = ModelStore.installed(ctx, m))
                }
            }
            if (ready) CapriButton("Delete", quiet = true, color = MUTED) {
                ModelStore.file(ctx, m).delete(); ModelStore.eyes(ctx, m).delete(); tick++
            }
        }
    }
}

/** Her long-term memory: everything she wrote down, yours to read and delete. */
@Composable
private fun MemoryCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val memory = remember { Memory.get(ctx) }
    var remembers by remember { mutableStateOf(prefs.remember) }
    var sure by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val facts = remember(tick) { memory.all() }
    Card("Her memory", "What she remembers about you and the people in your life. ${facts.size} ${if (facts.size == 1) "memory" else "memories"}, only on this phone.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remember things", color = INK, fontFamily = SANS, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(remembers, { remembers = it; prefs.remember = it }, colors = capriSwitch())
        }
        if (facts.isEmpty()) Text("Nothing yet. Tell her about your day, or hold her and say \"look at this\".",
            color = MUTED, fontFamily = SANS, fontSize = 12.sp)
        for (f in facts.take(60)) Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(PANEL_HI, chamfer(7.dp)).padding(start = 10.dp)) {
            Text(f.text, color = INK, fontFamily = SANS, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            Text("X", color = PINK, fontFamily = MONO, fontSize = 15.sp, modifier = Modifier.clickable { memory.forget(f.text); tick++ }.padding(12.dp))
        }
        if (facts.isNotEmpty()) CapriButton(if (sure) "Tap again: wipe it all" else "Forget everything", quiet = !sure) {
            if (sure) { memory.forgetAll(); sure = false; tick++ } else sure = true
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
