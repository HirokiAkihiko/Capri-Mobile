package com.capri.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Paper: white, hairlines, black type, one soft pink accent
val BG = Color(0xFFFFFFFF)
val PANEL = Color(0xFFFFFFFF)
val INK = Color(0xFF111111)
val TEXT = INK
val MUTED = Color(0xFF7A7A7A)
val EDGE = Color(0xFFE8E8E8)
val SOFT = Color(0xFFF4F4F4)
val PINK = Color(0xFFE2558A)     // the accent: dots, tags, progress
val PINK_BG = Color(0xFFFFF0F6)
val GREEN = Color(0xFF1E9E5A)    // "in use" / ok
val CYAN = INK                    // older screens' second accent: plain ink in Paper
val SANS = FontFamily(Font(R.font.manrope_regular, FontWeight.Normal), Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold))
val SERIF = FontFamily(Font(R.font.fraunces_semibold, FontWeight.SemiBold))
val MONO = SANS  // the old mono labels read cleaner in Manrope now

/** The GitHub repo (shown on the About tab). */
const val REPO_URL = "https://github.com/Capri-cmd/Capri-Mobile"

/** The one screen: her home, her brain, your stash, setup, and the credits. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        // the mic may have been allowed in the phone's settings: while the app's in front, she may add it too
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CapriService.micAllowed(this)
        }
    }
}

private enum class Tab(val label: String, val icon: String) {
    HOME("Home", "home"), BRAIN("Brain", "brain"), STASH("Stash", "gift"), SETUP("Setup", "gear"), ABOUT("About", "info")
}

@Composable
private fun App() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var tick by remember { mutableIntStateOf(0) }  // permissions / her state, re-read on resume and every couple of seconds
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {
            kotlinx.coroutines.delay(2000)
            tick++
        }
    }
    Column(Modifier.fillMaxSize().background(BG)) {
        Header(tick, tab)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.HOME -> HomeTab(tick) { tab = it }
                Tab.BRAIN -> BrainSection(CapriService.running, { t, b, c -> Card(t, b, content = c) },
                    { l, c, f -> NeonButton(l, c, onClick = f) })
                Tab.STASH -> StashTab(tick)
                Tab.SETUP -> SetupTab(tick) { tick++ }
                Tab.ABOUT -> AboutTab()
            }
            Spacer(Modifier.height(16.dp))
        }
        TabBar(tab) { tab = it }
    }
}

// -- pieces -------------------------------------------------------------------------------------------------------
@Composable
private fun Header(tick: Int, tab: Tab) {
    val on = remember(tick) { CapriService.running }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (tab == Tab.HOME) "Capri" else tab.label, fontFamily = SERIF, fontWeight = FontWeight.SemiBold, fontSize = 30.sp,
            color = INK, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (on) PINK else Color(0xFFCCCCCC), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (on) "Online" else "Offline", fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = INK)
        }
    }
}

@Composable
private fun TabBar(tab: Tab, pick: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(BG)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(EDGE))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in Tab.entries) {
                val sel = t == tab
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { pick(t) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LineIcon(t.icon, if (sel) INK else Color(0xFF9A9A9A), 22.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, fontFamily = SANS, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 11.sp, color = if (sel) INK else Color(0xFF9A9A9A))
                }
            }
        }
    }
}

/** Thin line icons, drawn (no icon pack): home, brain chip, gift, gear, info, mic. */
@Composable
fun LineIcon(kind: String, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = st)
        when (kind) {
            "home" -> p { moveTo(3 * s, 11 * s); lineTo(12 * s, 4 * s); lineTo(21 * s, 11 * s); moveTo(5 * s, 10 * s)
                lineTo(5 * s, 20 * s); lineTo(19 * s, 20 * s); lineTo(19 * s, 10 * s) }
            "brain" -> {
                drawRoundRect(color, Offset(6 * s, 6 * s), Size(12 * s, 12 * s), androidx.compose.ui.geometry.CornerRadius(3 * s), style = st)
                p { for (x in listOf(9f, 15f)) { moveTo(x * s, 2 * s); lineTo(x * s, 6 * s); moveTo(x * s, 18 * s); lineTo(x * s, 22 * s)
                    moveTo(2 * s, x * s); lineTo(6 * s, x * s); moveTo(18 * s, x * s); lineTo(22 * s, x * s) } }
            }
            "gift" -> {
                drawRoundRect(color, Offset(4 * s, 9 * s), Size(16 * s, 11 * s), androidx.compose.ui.geometry.CornerRadius(2 * s), style = st)
                p { moveTo(12 * s, 9 * s); lineTo(12 * s, 20 * s); moveTo(4 * s, 13 * s); lineTo(20 * s, 13 * s)
                    moveTo(12 * s, 9 * s); cubicTo(10 * s, 4 * s, 6 * s, 5 * s, 7 * s, 7.5f * s); cubicTo(8 * s, 9 * s, 12 * s, 9 * s, 12 * s, 9 * s)
                    cubicTo(14 * s, 4 * s, 18 * s, 5 * s, 17 * s, 7.5f * s); cubicTo(16 * s, 9 * s, 12 * s, 9 * s, 12 * s, 9 * s) }
            }
            "gear" -> {
                drawCircle(color, 3.2f * s, Offset(12 * s, 12 * s), style = st)
                drawCircle(color, 7.5f * s, Offset(12 * s, 12 * s), style = st)
                p { for (i in 0 until 8) { val a = Math.toRadians(45.0 * i)
                    moveTo((12 + 7.5 * kotlin.math.cos(a)).toFloat() * s, (12 + 7.5 * kotlin.math.sin(a)).toFloat() * s)
                    lineTo((12 + 10 * kotlin.math.cos(a)).toFloat() * s, (12 + 10 * kotlin.math.sin(a)).toFloat() * s) } }
            }
            "info" -> {
                drawCircle(color, 9 * s, Offset(12 * s, 12 * s), style = st)
                p { moveTo(12 * s, 11 * s); lineTo(12 * s, 16 * s) }
                drawCircle(color, 1.1f * s, Offset(12 * s, 8 * s))
            }
            "mic" -> {
                drawRoundRect(color, Offset(9 * s, 3 * s), Size(6 * s, 11 * s), androidx.compose.ui.geometry.CornerRadius(3 * s), style = st)
                p { moveTo(5 * s, 11 * s); cubicTo(5 * s, 19 * s, 19 * s, 19 * s, 19 * s, 11 * s); moveTo(12 * s, 18 * s); lineTo(12 * s, 21 * s) }
            }
        }
    }
}

/** A Paper card: white, hairline border, a small serif-free title. [accent] is kept for older call sites (unused). */
@Composable
fun Card(title: String, body: String = "", accent: Color = PINK, content: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().background(PANEL, RoundedCornerShape(12.dp)).border(1.dp, EDGE, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title.isNotEmpty()) Text(title.lowercase().replaceFirstChar { it.uppercase() }, color = MUTED, fontFamily = SANS,
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp)
        if (body.isNotEmpty()) Text(body, color = INK, fontFamily = SANS, fontSize = 14.sp, lineHeight = 20.sp)
        content()
    }
}

/** Buttons: dark = primary (black), light = secondary (soft gray). Colours from older call sites are mapped to these. */
@Composable
fun NeonButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val primary = color == PINK || color == INK || color == GREEN
    val bg = if (primary) INK else SOFT
    Button(onClick, modifier, colors = ButtonDefaults.buttonColors(containerColor = bg,
        contentColor = if (bg.luminance() < 0.5f) Color.White else INK), shape = RoundedCornerShape(8.dp)) {
        Text(label, fontFamily = SANS, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Toggle(title: String, body: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(value, set, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = INK,
            uncheckedTrackColor = SOFT, uncheckedBorderColor = EDGE, uncheckedThumbColor = Color(0xFFBDBDBD)))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(PANEL, RoundedCornerShape(10.dp)).border(1.dp, EDGE, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(value, color = INK, fontFamily = SERIF, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(label, color = MUTED, fontFamily = SANS, fontSize = 11.sp)
    }
}

// -- tabs ---------------------------------------------------------------------------------------------------------
@Composable
private fun HomeTab(tick: Int, go: (Tab) -> Unit) {
    val ctx = LocalContext.current
    var on by remember(tick) { mutableStateOf(CapriService.running) }
    val canDraw = remember(tick) { Settings.canDrawOverlays(ctx) }
    val face = remember { ctx.assets.open("face.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }
    val brain = remember(tick) { ModelStore.active(ctx) }
    val earned = remember(tick) { Badge.entries.count { Achievements.unlocked(ctx, it) } }
    val gifts = remember(tick) { Gift.entries.sumOf { Prefs(ctx).count(it) } }
    val memories = remember(tick) { Memory.get(ctx).all().take(3) }

    Row(Modifier.fillMaxWidth().background(PANEL, RoundedCornerShape(12.dp)).border(1.dp, EDGE, RoundedCornerShape(12.dp))
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(face, "Capri", Modifier.size(78.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (on) "She's out, choom." else if (canDraw) "Ready when you are." else "One step first.",
                fontFamily = SERIF, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = INK)
            Text("Brain: " + (brain?.label ?: "none yet") + if (brain != null && ModelStore.eyesInstalled(ctx, brain)) " + eyes" else "",
                fontFamily = SANS, fontSize = 12.5.sp, color = MUTED)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                when {
                    !canDraw -> NeonButton("Allow", INK) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    on -> {
                        NeonButton("Jack out", INK) { ctx.stopService(Intent(ctx, CapriService::class.java)); on = false }
                        NeonButton("Nap", SOFT) {
                            ctx.startService(Intent(ctx, CapriService::class.java).setAction(CapriService.ACTION_NAP))
                        }
                    }
                    else -> NeonButton("Summon", INK) {
                        val i = Intent(ctx, CapriService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                        on = true
                    }
                }
            }
        }
    }
    if (!canDraw) Text("Capri needs \"display over other apps\" so she can float on your screen.", color = MUTED,
        fontFamily = SANS, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Badges", "$earned/${Badge.entries.size}", Modifier.weight(1f).clickable { go(Tab.STASH) })
        Stat("Gifts", "$gifts", Modifier.weight(1f).clickable { go(Tab.STASH) })
        Stat("Days", "${Achievements.count(ctx, "days")}", Modifier.weight(1f))
    }
    if (brain == null) Card("Brain", "Give her a brain and she talks back, offline, on your phone.") {
        NeonButton("Pick a brain", INK) { go(Tab.BRAIN) }
    } else Card("Talk to her", "Hold her on screen and talk, let go when you're done. Or type in the Brain tab.") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { go(Tab.BRAIN) }) {
            Box(Modifier.weight(1f).background(SOFT, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
                Text("Say something...", color = MUTED, fontFamily = SANS, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(42.dp).background(INK, RoundedCornerShape(8.dp))
                .clickable { ctx.startActivity(Intent(ctx, VoiceActivity::class.java)) },  // talk: the phone's voice popup
                contentAlignment = Alignment.Center) {
                LineIcon("mic", Color.White, 20.dp)
            }
        }
    }
    if (memories.isNotEmpty()) Card("She remembers") {
        for (m in memories) Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
            Box(Modifier.padding(top = 7.dp).size(5.dp).background(PINK, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(m.text, color = INK, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    Card("How to play") {
        val moves = listOf(
            "Poke" to "head: annoyed. body: a hop. double tap: a grin. five quick pokes: she storms off",
            "Hold" to "talk to her (with a brain) / nap (without)",
            "Drag" to "pick her up, let go to throw. fling her into an edge and she peeks",
            "Shake" to "she gets dizzy. a hard shake throws her",
            "Tilt" to "she slides to the low side",
            "Look" to "say \"look at this\" and she checks your screen",
            "Nap" to "tap the sleeping chip to wake her",
        )
        for ((k, v) in moves) Row(Modifier.padding(vertical = 2.dp)) {
            Text(k, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(56.dp))
            Text(v, color = MUTED, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun StashTab(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val earned = remember(tick) { Badge.entries.count { Achievements.unlocked(ctx, it) } }
    if (!prefs.game) Text("The game is off (Setup). Your progress is still here.", color = MUTED, fontFamily = SANS, fontSize = 13.sp)
    Card("Achievements", "$earned of ${Badge.entries.size} unlocked") {
        LinearProgressIndicator(progress = { earned / Badge.entries.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(4.dp)), color = INK, trackColor = SOFT, drawStopIndicator = {})
        for (row in Badge.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in row) {
                val got = remember(tick) { Achievements.unlocked(ctx, b) }
                val prog = remember(tick) { Achievements.progress(ctx, b) }
                val icon = remember(b, got) {
                    android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888).also {
                        Achievements.draw(android.graphics.Canvas(it), b, 144f, got)
                    }.asImageBitmap()
                }
                Column(Modifier.weight(1f).border(1.dp, EDGE, RoundedCornerShape(10.dp)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, b.title, Modifier.size(48.dp))
                    Text(if (got) b.title else "Locked", color = if (got) INK else MUTED, fontFamily = SANS,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                    Text(b.how, color = MUTED, fontFamily = SANS, fontSize = 10.5.sp, lineHeight = 13.sp, maxLines = 3, minLines = 3)
                    if (!got && b.goal > 1) {
                        LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                            color = PINK, trackColor = SOFT, drawStopIndicator = {})
                        Text("${Achievements.count(ctx, b.counter)}/${b.goal}", color = MUTED, fontFamily = SANS, fontSize = 10.sp)
                    }
                }
            }
        }
    }
    Card("Gift stash", "What she dropped for you. ${remember(tick) { Gift.entries.sumOf { prefs.count(it) } }} collected.") {
        for (row in Gift.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in row) {
                val n = remember(tick) { prefs.count(g) }
                val icon = remember(g) { giftBitmap(g, 120).asImageBitmap() }
                Column(Modifier.weight(1f).border(1.dp, EDGE, RoundedCornerShape(10.dp)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, g.label, Modifier.size(40.dp), alpha = if (n > 0) 1f else 0.25f)
                    Text(if (n > 0) g.label else "???", color = if (n > 0) INK else MUTED, fontFamily = SANS,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
                    Text("x$n · ${g.rarity}", color = if (g.rarity == "legendary") PINK else MUTED, fontFamily = SANS, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SetupTab(tick: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val canDraw = remember(tick) { Settings.canDrawOverlays(ctx) }
    val access = remember(tick) { CapriAccess.enabled(ctx) }
    var micOk by remember(tick) { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micOk = it; refresh()
        if (it) CapriService.micAllowed(ctx)  // she may listen now too
    }
    var roam by remember { mutableStateOf(prefs.roam) }
    var talk by remember { mutableStateOf(prefs.talk) }
    var blips by remember { mutableStateOf(prefs.blips) }
    var tilt by remember { mutableStateOf(prefs.tilt) }
    var shake by remember { mutableStateOf(prefs.shake) }
    var game by remember { mutableStateOf(prefs.game) }
    var feed by remember { mutableStateOf(prefs.feed) }
    var size by remember { mutableFloatStateOf(prefs.sizeDp.toFloat()) }
    var bubbleStyle by remember { mutableIntStateOf(prefs.bubbleStyle) }

    Card("Permissions") {
        @Composable
        fun line(name: String, ok: Boolean, why: String, fix: () -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(8.dp).background(if (ok) GREEN else PINK, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(why, color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!ok) NeonButton("Allow", INK, onClick = fix)
            }
        }
        line("Draw over apps", canDraw, "so she can float on your screen") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        line("Microphone", micOk, "so she hears you while you hold her") { mic.launch(Manifest.permission.RECORD_AUDIO) }
        line("Accessibility", access, "sit on icons, react to your apps, look at your screen") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!access) {
            Text("Greyed out? App info > ⋮ > Allow restricted settings, then try again.", color = MUTED, fontFamily = SANS, fontSize = 12.sp)
            NeonButton("App info", SOFT) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }
    Card("Her behaviour") {
        Toggle("Roam", "zaps to a new spot every half a minute or so", roam) { roam = it; prefs.roam = it }
        Toggle("Talk", "greetings, reactions, battery and charger lines", talk) { talk = it; prefs.talk = it }
        Toggle("Blips", "soft ticks while she types", blips) { blips = it; prefs.blips = it }
        Toggle("Tilt slide", "she slides to the low side when you tilt your phone", tilt) { tilt = it; prefs.tilt = it }
        Toggle("Shake", "shake her dizzy; a hard shake throws her", shake) { shake = it; prefs.shake = it }
        Toggle("Game", "gifts she drops and achievements to unlock. Off: just her", game) { game = it; prefs.game = it }
        Toggle("Scroll with me", "in TikTok, Reels, Shorts, Reddit, X she reads the captions and comments and " +
            "sometimes reacts. Needs a brain + accessibility. Never stored.", feed) { feed = it; prefs.feed = it }
    }
    Card("Chat bubble", "How her speech bubble looks on your screen.") {
        Row(Modifier.fillMaxWidth().background(SOFT, RoundedCornerShape(10.dp)).padding(3.dp)) {
            listOf("Classic", "Light", "Dark").forEachIndexed { i, label ->
                val sel = bubbleStyle == i
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) PANEL else SOFT).then(if (sel) Modifier.border(1.dp, EDGE, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { bubbleStyle = i; prefs.bubbleStyle = i }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (sel) INK else MUTED, fontFamily = SANS,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp)
                }
            }
        }
        Text("Classic is her original call box. Light and Dark are clean cards.", color = MUTED, fontFamily = SANS, fontSize = 12.sp)
    }
    Card("Size", "${size.toInt()} dp tall") {
        Slider(value = size, onValueChange = { size = it }, valueRange = 90f..240f,
            onValueChangeFinished = {
                prefs.sizeDp = size.toInt()
                if (CapriService.running) {  // she reloads at the new size
                    ctx.stopService(Intent(ctx, CapriService::class.java))
                    val i = Intent(ctx, CapriService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                }
            },
            colors = SliderDefaults.colors(thumbColor = INK, activeTrackColor = INK, inactiveTrackColor = EDGE))
    }
}

@Composable
private fun AboutTab() {
    val uri = LocalUriHandler.current
    Card("Fan-made", "Capri is an unofficial fan project, made for fun and given away free. It is not made, endorsed " +
        "or sponsored by CD PROJEKT RED or Studio Trigger, and it's not for sale.")
    Card("Credits") {
        val credits = listOf(
            "Capri, Cyberpunk & Edgerunners" to "Capri, Cyberpunk: Edgerunners and Cyberpunk 2077 are trademarks and characters of CD PROJEKT S.A. Cyberpunk is a trademark of R. Talsorian Games. All rights to them stay with their owners.",
            "Sprites" to "Capri's sprite art by Lachy (github.com/lachydotmcg), PolyForm Noncommercial 1.0.0. The app icon " +
                "and her nap are cut from it: no official art, audio or logos are in this app.",
            "Model training" to "The CapriAI2 models, the ones trained to be Capri, by ghasty3 (github.com/ghasty3).",
            "Brains" to "llama.cpp by ggml-org (MIT). Each model keeps its own license (Qwen, MiniCPM, Gemma, NVIDIA Nemotron): see its Hugging Face page.",
            "Fonts" to "Manrope and Fraunces (SIL Open Font License).",
            "This app" to "Code under the PolyForm Noncommercial License 1.0.0: free to use and share, never for sale.",
            "Made by" to "the Capri crew. First Capri on a phone, ever.",
        )
        for ((k, v) in credits) Column(Modifier.padding(vertical = 3.dp)) {
            Text(k, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(v, color = MUTED, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
    Card("Privacy", "Everything runs on your phone. No accounts, no analytics, no servers. Her brain is offline once " +
        "downloaded; the only network use is downloading brains you pick. Her memories stay in the app's private storage " +
        "(see them in the Brain tab). Accessibility (optional) is only used to find icons to sit on, see which app is open, " +
        "read a feed's captions and look at your screen when you ask. Speech goes through your phone's own recognizer.")
    NeonButton("Source and releases on GitHub", INK) { uri.openUri(REPO_URL) }
    Text("v0.1 alpha · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = MUTED, fontFamily = SANS, fontSize = 11.sp,
        modifier = Modifier.padding(start = 2.dp))
}
