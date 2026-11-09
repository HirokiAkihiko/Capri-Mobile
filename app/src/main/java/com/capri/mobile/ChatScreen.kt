package com.capri.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray

private fun history(prefs: Prefs): List<Pair<String, String>> {
    val a = runCatching { JSONArray(prefs.chat) }.getOrDefault(JSONArray())
    return (0 until a.length()).map { a.getJSONObject(it).let { t -> t.getString("u") to t.getString("a") } }
}

/** Talking to her in the app: her big eyes up top (they talk with her), your chat below. */
@Composable
fun ChatTab(go: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var msgs by remember { mutableStateOf(history(prefs)) }
    var pending by remember { mutableStateOf("") }   // what you just said, until it's in her history
    var live by remember { mutableStateOf("") }      // her reply as it's written
    var status by remember { mutableStateOf(CapriService.brainStatus) }
    var running by remember { mutableStateOf(CapriService.running) }
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {  // while you're here her replies land in the chat, not in her bubble on top of it
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) CapriService.chatOpen = true
            if (e == Lifecycle.Event.ON_PAUSE) CapriService.chatOpen = false
        }
        owner.lifecycle.addObserver(obs)
        CapriService.chatOpen = true
        onDispose {
            CapriService.chatOpen = false
            owner.lifecycle.removeObserver(obs)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            status = CapriService.brainStatus
            running = CapriService.running
            live = CapriService.chatLine
            val h = history(prefs)
            if (h != msgs) {
                msgs = h
                if (pending.isNotEmpty() && h.lastOrNull()?.first == pending) {
                    pending = ""
                    CapriService.chatLine = ""
                    live = ""
                }
            }
        }
    }
    val writing = live.isNotEmpty() && live != "..." && live != "*looking*"
    LaunchedEffect(msgs.size, pending, live.length) {
        val n = list.layoutInfo.totalItemsCount
        if (n > 0) list.scrollToItem(n - 1)
    }
    val online = running && CapriService.brainOnline

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyes(talking = writing, annoyed = running && !online && CapriService.brainState == "stopped")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Capri", color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(if (running) status else tr("offline"), color = MUTED, fontFamily = SANS, fontSize = 12.sp, maxLines = 1)
            }
            if (msgs.isNotEmpty()) Text(tr("Clear"), color = PINK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clickable { prefs.chat = "[]"; CapriService.chatLine = ""; pending = "" }.padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(EDGE))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty() && pending.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Say something, choom."), color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(tr("Tell her about your day. She remembers."), color = MUTED, fontFamily = SANS, fontSize = 13.sp)
                }
            }
            items(msgs) { (u, a) ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    You(u)
                    Her(a)
                }
            }
            if (pending.isNotEmpty()) item { You(pending) }
            if (pending.isNotEmpty() || writing) item { if (writing) Her(live) else Thinking(live == "*looking*") }
        }
        when {
            !running -> Banner(tr("She's offline."), if (Settings.canDrawOverlays(ctx)) tr("Summon") else tr("Go to Home")) {
                if (Settings.canDrawOverlays(ctx)) summon(ctx) else go(Tab.HOME)
            }
            !online && CapriService.brainState == "none" -> Banner(tr("She has no brain yet."), tr("Pick one")) { go(Tab.BRAIN) }
            !online && CapriService.brainState == "watcher" -> Banner(tr("The Watcher doesn't talk."), tr("Her look")) { go(Tab.SETUP) }
            !online -> Banner(status, tr("Brain")) { go(Tab.BRAIN) }
            else -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(text, { text = it }, placeholder = { Text(tr("Say something..."), color = MUTED) }, maxLines = 4,
                    colors = capriField(), shape = chamfer(9.dp), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                val send = text.isNotBlank()
                Box(Modifier.size(52.dp).background(PINK, chamfer(9.dp)).clickable {
                    if (send) {
                        val line = text.trim()
                        text = ""
                        pending = line
                        CapriService.chatLine = ""
                        ctx.startService(Intent(ctx, CapriService::class.java).setAction(CapriService.ACTION_ASK).putExtra("text", line))
                    } else ctx.startActivity(Intent(ctx, VoiceActivity::class.java))
                }, contentAlignment = Alignment.Center) {
                    LineIcon(if (send) "send" else "mic", ON_NEON, 22.dp)
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, go: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MUTED, fontFamily = SANS, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 10.dp))
        CapriButton(action, onClick = go)
    }
}

@Composable
private fun You(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 290.dp).background(PINK, chamfer(10.dp)).padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(text, color = ON_NEON, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun Her(text: String) {
    Box(Modifier.widthIn(max = 300.dp).background(PANEL, chamfer(10.dp)).border(1.dp, EDGE, chamfer(10.dp))
        .padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(text, color = INK, fontFamily = SANS, fontSize = 14.5.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Thinking(looking: Boolean) {
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(320); n = (n + 1) % 4 } }
    Box(Modifier.background(PANEL, chamfer(10.dp)).border(1.dp, EDGE, chamfer(10.dp)).padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text((if (looking) "looking" else "thinking") + ".".repeat(n), color = MUTED, fontFamily = SANS, fontSize = 13.sp)
    }
}

/** Her eyes from her mini form: they blink, and talk while she does. */
@Composable
private fun Eyes(talking: Boolean, annoyed: Boolean) {
    val ctx = LocalContext.current
    val art = remember {
        listOf("idle", "blink", "talk", "talk2", "annoyed", "annoyed_talk").associateWith { name ->
            ctx.assets.open("eyes/$name.webp").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }
    }
    var tickN by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(110); tickN++ } }
    val blink = tickN % 40 >= 38
    val name = when {
        annoyed -> if (talking && tickN % 2 == 0) "annoyed_talk" else "annoyed"
        talking -> if (tickN % 4 < 2) "talk" else "talk2"
        blink -> "blink"
        else -> "idle"
    }
    val shown: ImageBitmap = art.getValue(name)
    Image(shown, "Capri", Modifier.size(64.dp), contentScale = ContentScale.Fit)
}
