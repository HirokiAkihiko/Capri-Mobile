package com.capri.mobile

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.random.Random

// Clean and flat: panels on a soft page, and her own colors (straight from her art) as accents. Light or dark, from Setup.
object Look { var dark by mutableStateOf(false) }

private fun pick(light: Long, dark: Long) = Color(if (Look.dark) dark else light)
val BG get() = pick(0xFFFFF1F5, 0xFF121014)
val PANEL get() = pick(0xFFFFFFFF, 0xFF1C181F)
val PANEL_HI get() = pick(0xFFFFF1F5, 0xFF26212A)
val STAGE get() = pick(0xFFFFFFFF, 0xFF4A3F4F)  // behind her: light enough for her black to read
val INK get() = pick(0xFF000000, 0xFFF5F0F4)
val MUTED get() = pick(0xFF7A6A72, 0xFFA89BA3)
val EDGE get() = pick(0xFFF3D9E2, 0xFF352E39)
val PINK = Color(0xFFFF0048)     // her pink
val GREEN = Color(0xFF00FF72)    // her green: only ever a fill, with black on it
val CYAN = Color(0xFF007BFF)     // her eyes
val ON_NEON = Color(0xFFFFFFFF)  // text on her pink
val ON_GREEN = Color(0xFF000000)

val SANS = FontFamily(Font(R.font.manrope_regular, FontWeight.Normal), Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold))
val MONO = FontFamily(Font(R.font.share_tech_mono))

/** Her shape: two cut corners, the one bit of tech in the design. */
fun chamfer(cut: Dp = 12.dp) = CutCornerShape(topStart = 0.dp, topEnd = cut, bottomEnd = 0.dp, bottomStart = cut)

const val REPO_URL = "https://github.com/Capri-cmd/Capri-Mobile"
const val DONATE_URL = "https://linktr.ee/capriai"

/** The one screen: her home, chat, brain, setup and the credits. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.dark = Prefs(this).dark
        bars()
        setContent { App() }
    }

    /** Status bar icons that fit the theme. */
    fun bars() {
        val style = if (Look.dark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onPause() {
        super.onPause()
        CapriService.appOpen = false
    }

    override fun onResume() {
        super.onResume()
        CapriService.appOpen = true
        Updates.check(this)
        // the mic may have been allowed in the phone's settings: while the app's in front, she may add it too
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CapriService.micAllowed(this)
        }
    }
}

enum class Tab(val label: String, val icon: String) {
    HOME("Home", "home"), CHAT("Chat", "chat"), BRAIN("Brain", "brain"), SETUP("Setup", "gear")
}

fun summon(ctx: Context) {
    val i = Intent(ctx, CapriService::class.java)
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
}

/** A setting she loads at start changed: she comes back right away, without the intro. */
private fun restart(ctx: Context) {
    if (!CapriService.running) return
    ctx.stopService(Intent(ctx, CapriService::class.java))
    CapriService.quietStart = true
    summon(ctx)
}

@OptIn(ExperimentalLayoutApi::class)
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
            delay(2000)
            tick++
        }
    }
    Column(Modifier.fillMaxSize().background(BG).imePadding()) {
        Header(tick, tab)
        if (tab == Tab.CHAT) {
            Box(Modifier.weight(1f)) { ChatTab { tab = it } }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    Tab.HOME -> HomeTab(tick) { tab = it }
                    Tab.BRAIN -> BrainTab()
                    else -> SetupTab(tick) { tick++ }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (!WindowInsets.isImeVisible) TabBar(tab) { tab = it }
    }
}

// -- pieces -------------------------------------------------------------------------------------------------------
@Composable
private fun Header(tick: Int, tab: Tab) {
    val on = remember(tick) { CapriService.running }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (tab == Tab.HOME) "Capri" else tab.label, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
            color = INK, modifier = Modifier.weight(1f))
        Pill(if (on) "On screen" else "Offline", if (on) GREEN else MUTED)
    }
}

@Composable
fun Pill(label: String, color: Color) {
    val solid = color == GREEN
    Box(Modifier.background(if (solid) GREEN else PANEL, chamfer(6.dp)).border(1.dp, if (solid) GREEN else EDGE, chamfer(6.dp))
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (solid) ON_GREEN else color)
    }
}

@Composable
private fun TabBar(tab: Tab, pick: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(PANEL)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(EDGE))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp)) {
            for (t in Tab.entries) {
                val sel = t == tab
                Column(Modifier.weight(1f).clip(chamfer(8.dp)).clickable { pick(t) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LineIcon(t.icon, if (sel) PINK else MUTED, 22.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, fontFamily = SANS, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Normal,
                        fontSize = 11.sp, color = if (sel) PINK else MUTED)
                }
            }
        }
    }
}

/** Thin line icons, drawn (no icon pack). */
@Composable
fun LineIcon(kind: String, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = st)
        when (kind) {
            "home" -> p { moveTo(3 * s, 11 * s); lineTo(12 * s, 4 * s); lineTo(21 * s, 11 * s); moveTo(5 * s, 10 * s)
                lineTo(5 * s, 20 * s); lineTo(19 * s, 20 * s); lineTo(19 * s, 10 * s) }
            "chat" -> p { moveTo(4 * s, 5 * s); lineTo(20 * s, 5 * s); lineTo(20 * s, 16 * s); lineTo(11 * s, 16 * s)
                lineTo(6 * s, 20 * s); lineTo(6 * s, 16 * s); lineTo(4 * s, 16 * s); close() }
            "brain" -> {
                drawRoundRect(color, Offset(6 * s, 6 * s), Size(12 * s, 12 * s), androidx.compose.ui.geometry.CornerRadius(2 * s), style = st)
                p { for (x in listOf(9f, 15f)) { moveTo(x * s, 2 * s); lineTo(x * s, 6 * s); moveTo(x * s, 18 * s); lineTo(x * s, 22 * s)
                    moveTo(2 * s, x * s); lineTo(6 * s, x * s); moveTo(18 * s, x * s); lineTo(22 * s, x * s) } }
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
            "send" -> p { moveTo(4 * s, 12 * s); lineTo(20 * s, 12 * s); moveTo(13 * s, 5 * s); lineTo(20 * s, 12 * s); lineTo(13 * s, 19 * s) }
            "check" -> p { moveTo(5 * s, 12.5f * s); lineTo(10 * s, 17.5f * s); lineTo(19 * s, 7 * s) }
        }
    }
}

/** A panel: dark, hairline edge, cut corners, a small mono label. */
@Composable
fun Card(title: String = "", body: String = "", content: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().background(PANEL, chamfer()).border(1.dp, EDGE, chamfer()).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (title.isNotEmpty()) Label(title)
        if (body.isNotEmpty()) Text(body, color = INK, fontFamily = SANS, fontSize = 14.sp, lineHeight = 20.sp)
        content()
    }
}

@Composable
fun Label(text: String, color: Color = PINK) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

/** Primary: solid pink. Quiet: outlined. */
@Composable
fun CapriButton(label: String, modifier: Modifier = Modifier, quiet: Boolean = false, color: Color = PINK, enabled: Boolean = true,
                onClick: () -> Unit) {
    val shape = chamfer(9.dp)
    Box(modifier.clip(shape).background(if (quiet) Color.Transparent else if (enabled) color else EDGE, shape)
        .border(1.dp, if (quiet) (if (color == GREEN) EDGE else color) else Color.Transparent, shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontFamily = SANS, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
            color = if (quiet) (if (color == GREEN) INK else color) else if (!enabled) MUTED else if (color == GREEN) ON_GREEN else ON_NEON)
    }
}

@Composable
fun capriField() = OutlinedTextFieldDefaults.colors(focusedTextColor = INK, unfocusedTextColor = INK, focusedBorderColor = PINK,
    unfocusedBorderColor = EDGE, cursorColor = PINK, focusedLabelColor = PINK, unfocusedLabelColor = MUTED,
    focusedContainerColor = PANEL, unfocusedContainerColor = PANEL)

@Composable
fun capriSwitch() = SwitchDefaults.colors(checkedThumbColor = ON_NEON, checkedTrackColor = PINK, uncheckedTrackColor = PANEL_HI,
    uncheckedBorderColor = EDGE, uncheckedThumbColor = MUTED)

@Composable
fun Toggle(title: String, body: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(value, set, colors = capriSwitch())
    }
}

/** A row of choices, one picked. */
@Composable
fun Segments(options: List<String>, selected: Int, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(PANEL, chamfer(9.dp)).border(1.dp, EDGE, chamfer(9.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val sel = selected == i
            Box(Modifier.weight(1f).clip(chamfer(7.dp)).background(if (sel) PINK else Color.Transparent)
                .clickable { pick(i) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (sel) ON_NEON else MUTED, fontFamily = SANS,
                    fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Normal, fontSize = 13.sp)
            }
        }
    }
}

/** Her, alive in the app: her idle loop with a blink now and then. */
@Composable
private fun Capri(skin: String, modifier: Modifier) {
    val ctx = LocalContext.current
    fun strip(name: String) = ctx.assets.open("skins/$skin/$name.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    val idle = remember(skin) { strip("idle") }
    val blink = remember(skin) { strip("blink") }
    var frame by remember { mutableIntStateOf(0) }
    var blinking by remember { mutableIntStateOf(-1) }
    LaunchedEffect(skin) {
        var until = 2 + Random.nextInt(4)
        var n = 0
        while (true) {
            delay(125)
            frame = (frame + 1) % 16
            if (blinking >= 0) {
                blinking = if (blinking < 3) blinking + 1 else -1
            } else if (++n >= until * 8) {
                n = 0
                until = 2 + Random.nextInt(4)
                blinking = 0
            }
        }
    }
    Canvas(modifier) {
        val art = if (blinking >= 0) blink else idle
        val f = if (blinking >= 0) blinking else frame
        val h = size.height
        val w = h * 240f / 384f
        drawImage(art, IntOffset(f * 240, 0), IntSize(240, 384), IntOffset(((size.width - w) / 2).toInt(), 0),
            IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.High)
    }
}

/** Thin corner brackets around her: the only decoration on the page. */
@Composable
private fun Brackets(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val l = 22.dp.toPx()
        val t = 1.5.dp.toPx()
        for ((x, y, dx, dy) in listOf(listOf(0f, 0f, 1f, 1f), listOf(w, 0f, -1f, 1f), listOf(0f, h, 1f, -1f), listOf(w, h, -1f, -1f))) {
            drawLine(PINK, Offset(x, y), Offset(x + dx * l, y), t)
            drawLine(PINK, Offset(x, y), Offset(x, y + dy * l), t)
        }
    }
}

// -- tabs ---------------------------------------------------------------------------------------------------------
@Composable
private fun HomeTab(tick: Int, go: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var on by remember(tick) { mutableStateOf(CapriService.running) }
    val canDraw = remember(tick) { Settings.canDrawOverlays(ctx) }
    val access = remember(tick) { CapriAccess.enabled(ctx) }
    val brain = remember(tick) { ModelStore.active(ctx) }
    val status = remember(tick) { CapriService.brainStatus }
    val memories = remember(tick) { Memory.get(ctx).all().take(3) }
    val skin = remember(tick) { prefs.skin }
    val uri = LocalUriHandler.current
    var update by remember(tick) { mutableStateOf(Updates.waiting(ctx, prefs)) }

    if (update.isNotEmpty()) Card("New update", "Version ${update.removePrefix("v")} is out on GitHub.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton("Get it", Modifier.weight(1f)) { uri.openUri(Updates.PAGE) }
            CapriButton("Later", Modifier.weight(1f), quiet = true) { prefs.updateSkipped = update; update = "" }
        }
    }
    Box(Modifier.fillMaxWidth().height(330.dp).background(STAGE, chamfer(16.dp)).border(1.dp, EDGE, chamfer(16.dp))) {
        Brackets(Modifier.fillMaxSize().padding(14.dp))
        Capri(skin, Modifier.fillMaxSize().padding(vertical = 20.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (on) "She's out, choom." else if (canDraw) "Ready when you are." else "One step first.",
            fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = INK)
        Text(if (on) status else "Brain: " + (brain?.label ?: "none yet"), fontFamily = SANS, fontSize = 13.sp, color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !canDraw -> CapriButton("Let her on screen", Modifier.weight(1f)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                on -> {
                    CapriButton("Jack out", Modifier.weight(1f)) { ctx.stopService(Intent(ctx, CapriService::class.java)); on = false }
                    CapriButton("Nap", Modifier.weight(1f), quiet = true) {
                        ctx.startService(Intent(ctx, CapriService::class.java).setAction(CapriService.ACTION_NAP))
                    }
                }
                else -> CapriButton("Summon", Modifier.weight(1f)) { summon(ctx); on = true }
            }
        }
    }
    if (!canDraw || brain == null || !access) Card("Get her ready") {
        Step("Let her on screen", "\"Display over other apps\", so she can float over everything", canDraw)
        Step("Give her a brain", "On this phone, LM Studio on your PC, or NVIDIA's free cloud", brain != null) { go(Tab.BRAIN) }
        Step("Let her see", "Accessibility: sit on icons, react to your apps, look at your screen", access) { go(Tab.SETUP) }
    }
    if (brain == null && !ModelStore.canShare(ctx)) Card("Had Capri before?") {
        Step("Find your brains", "Kept your brains safe last time? She finds them with no downloading", false) { go(Tab.BRAIN) }
    }
    if (memories.isNotEmpty()) Card("She remembers") {
        for (m in memories) Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
            Box(Modifier.padding(top = 7.dp).size(5.dp).background(PINK, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(m.text, color = INK, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Step(title: String, why: String, done: Boolean, open: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (!done && open != null) Modifier.clickable(onClick = open) else Modifier)
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(if (done) GREEN else PANEL, chamfer(5.dp))
            .border(1.dp, if (done) GREEN else EDGE, chamfer(5.dp)), contentAlignment = Alignment.Center) {
            if (done) LineIcon("check", ON_GREEN, 16.dp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (done) MUTED else INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (!done) Text(why, color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
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
    var mini by remember { mutableStateOf(prefs.mini) }
    var moods by remember { mutableStateOf(prefs.moods) }
    var roam by remember { mutableStateOf(prefs.roam) }
    var talk by remember { mutableStateOf(prefs.talk) }
    var blips by remember { mutableStateOf(prefs.blips) }
    var tilt by remember { mutableStateOf(prefs.tilt) }
    var shake by remember { mutableStateOf(prefs.shake) }
    var feed by remember { mutableStateOf(prefs.feed) }
    var size by remember { mutableFloatStateOf(prefs.sizeDp.toFloat()) }
    var bubbleStyle by remember { mutableIntStateOf(prefs.bubbleStyle) }
    var skin by remember { mutableStateOf(prefs.skin) }
    var name by remember { mutableStateOf(prefs.userName) }

    Card("Permissions") {
        @Composable
        fun line(title: String, ok: Boolean, why: String, fix: () -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(8.dp).background(if (ok) GREEN else PINK, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(why, color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!ok) CapriButton("Allow", onClick = fix)
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
            CapriButton("App info", quiet = true) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }
    Card("Theme") {
        Segments(listOf("Light", "Dark"), if (Look.dark) 1 else 0) { i ->
            Look.dark = i == 1
            prefs.dark = Look.dark
            (ctx as? MainActivity)?.bars()
        }
    }
    Card("You") {
        OutlinedTextField(name, { name = it; prefs.userName = it.trim() }, label = { Text("What she calls you") }, singleLine = true,
            colors = capriField(), shape = chamfer(9.dp), modifier = Modifier.fillMaxWidth())
    }
    Card("Her look") {
        val seen = skin == "watcher" || prefs.watcherSeen
        Segments(listOf("Capri", if (seen) "Watcher" else "???"), if (skin == "watcher") 1 else 0) { i ->
            val pick = if (i == 1) "watcher" else "capri"
            if (pick != skin) {
                skin = pick
                prefs.skin = pick
                if (i == 1) prefs.watcherSeen = true
                restart(ctx)
            }
        }
        if (skin == "watcher") Text("The Watcher doesn't talk. It just watches.", color = MUTED, fontFamily = SANS, fontSize = 12.sp)
    }
    Card("Her behaviour") {
        Toggle("Always mini", "she stays small, just her eyes. Drag them anywhere: tap to type, hold to talk, double tap to annoy her",
            mini) { mini = it; prefs.mini = it; CapriService.miniChanged(ctx) }
        if (!mini) Toggle("Mini moods", "now and then she goes mini by herself for a few minutes", moods) { moods = it; prefs.moods = it }
        Toggle("Roam", "zaps to a new spot every half a minute or so", roam) { roam = it; prefs.roam = it }
        Toggle("Talk", "greetings, reactions, battery and charger lines", talk) { talk = it; prefs.talk = it }
        Toggle("Blips", "soft ticks while she types", blips) { blips = it; prefs.blips = it }
        Toggle("Tilt slide", "she slides to the low side when you tilt your phone", tilt) { tilt = it; prefs.tilt = it }
        Toggle("Shake", "shake her dizzy; a hard shake throws her", shake) { shake = it; prefs.shake = it }
        Toggle("React on her own", "every minute or two she glances at whatever app you're in and says something. " +
            "Skips banking, wallet and password apps and login screens. Needs a brain + accessibility. Never stored.", feed) { feed = it; prefs.feed = it }
    }
    Card("Chat bubble", "How her speech bubble looks on your screen.") {
        Segments(listOf("Classic", "Light", "Dark"), bubbleStyle) { bubbleStyle = it; prefs.bubbleStyle = it }
    }
    Card("Size", "${size.toInt()} dp tall") {
        Slider(value = size, onValueChange = { size = it }, valueRange = 90f..240f,
            onValueChangeFinished = { prefs.sizeDp = size.toInt(); restart(ctx) },  // she reloads at the new size
            colors = SliderDefaults.colors(thumbColor = PINK, activeTrackColor = PINK, inactiveTrackColor = EDGE))
    }
    Card("How to play") {
        val moves = listOf(
            "Tap" to "a little box opens: type to her, over any app (or Chat in her notification)",
            "Double tap" to "a poke. head: annoyed. body: a hop. keep going and she storms off",
            "Hold" to "talk to her with your voice (with a brain) / nap (without)",
            "Drag" to "pick her up, let go to throw. fling her into an edge and she peeks",
            "Shake" to "she gets dizzy. a hard shake throws her",
            "Tilt" to "she slides to the low side",
            "Look" to "say \"look at this\" and she checks your screen",
            "Nap" to "tap her while she's lying down to wake her",
            "Mini" to "Always mini (above) keeps her small, just her eyes",
        )
        for ((k, v) in moves) Row(Modifier.padding(vertical = 2.dp)) {
            Text(k, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(82.dp))
            Text(v, color = MUTED, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    AboutTab()
}

@Composable
private fun AboutTab() {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    Card("Fan-made", "Capri is an unofficial fan project, made for fun and given away free. It is not made, endorsed " +
        "or sponsored by CD PROJEKT RED or Studio Trigger, and it's not for sale.")
    Card("Credits") {
        val credits = listOf(
            "Capri, Cyberpunk & Edgerunners" to "Capri, Cyberpunk: Edgerunners and Cyberpunk 2077 are trademarks and characters of CD PROJEKT S.A. Cyberpunk is a trademark of R. Talsorian Games. All rights to them stay with their owners.",
            "Art" to "Capri's art, her mini form, the Watcher and the intro by xilia_world00 (Discord): no official art, audio or logos are in this app.",
            "Model training" to "The CapriAI2 models, the ones trained to be Capri, by ghasty3 (github.com/ghasty3).",
            "Brains" to "llama.cpp by ggml-org (MIT). Her ears: Qwen3-ASR by the Qwen team (Apache 2.0). Conversational 7B by xiaoheiqaq. Each model keeps its own license (Qwen, NVIDIA Nemotron): see its Hugging Face page.",
            "Fonts" to "Manrope, Share Tech Mono and Fraunces (SIL Open Font License).",
            "This app" to "Code under the PolyForm Noncommercial License 1.0.0: free to use and share, never for sale.",
            "Thanks" to "Lachy (github.com/lachydotmcg), whose sprite art the first versions used.",
            "Made by" to "the Capri crew. First Capri on a phone, ever.",
        )
        for ((k, v) in credits) Column(Modifier.padding(vertical = 3.dp)) {
            Text(k, color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(v, color = MUTED, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
    Card("Privacy", "No accounts, no analytics, no servers of ours. A brain on the phone runs offline once downloaded. " +
        "With LM Studio, what you say and what she looks at go only to your own PC over your own Wi-Fi. With NVIDIA, what " +
        "you say goes to NVIDIA, and so does your screen when you ask her to look, or by itself only if you switch on \"Let her look on her own\" in the Brain tab. Your key stays on this phone. The only other network use is downloading what you pick and, on Wi-Fi, asking GitHub if there's a new version. Her memories stay in the app's private storage (see them in the Brain " +
        "tab). Accessibility (optional) is only used to find icons to sit on, see which app is open, react to what's on your screen " +
        "(React on her own) and look at it when you ask. Speech stays on the phone with her Qwen ears; otherwise it goes through your phone's own recognizer.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CapriButton("GitHub", Modifier.weight(1f), quiet = true) { uri.openUri(REPO_URL) }
        CapriButton("Support us", Modifier.weight(1f), quiet = true, color = GREEN) { uri.openUri(DONATE_URL) }
    }
    Text("v$version · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = MUTED, fontFamily = SANS, fontSize = 11.sp,
        modifier = Modifier.padding(start = 2.dp))
}
