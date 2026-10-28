package com.arubih.capriai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val BG = Color(0xFF0A0514)
val PANEL = Color(0xFF130D1C)
val PINK = Color(0xFFFF1460)
val GREEN = Color(0xFF2BFF9A)
val CYAN = Color(0xFF78FAFF)
val TEXT = Color(0xFFF2EEF7)
val MUTED = Color(0xFF968CA5)
val EDGE = Color(0x33FFFFFF)
val MONO = FontFamily(Font(R.font.share_tech_mono))

/** The GitHub repo, once there is a public one (shown on the About tab). */
const val REPO_URL = "https://github.com/Capri-cmd/Capri-Mobile"

/** The one screen: her home, her brain, your stash, setup, and the credits. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

private enum class Tab(val label: String, val glyph: String) {
    HOME("HOME", ">_"), BRAIN("BRAIN", "#"), STASH("STASH", "$"), SETUP("SETUP", "*"), ABOUT("ABOUT", "i")
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(Tab.HOME) }
    var tick by remember { mutableIntStateOf(0) }  // bumped on resume: permissions / service state re-read
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {  // her status, badges and gifts change while you're looking
            kotlinx.coroutines.delay(2000)
            tick++
        }
    }
    Box(Modifier.fillMaxSize().background(BG).drawBehind {
        // a faint grid and scanlines, like her desktop posters
        val grid = Color(0x0FFF1460)
        var x = 0f
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height)); x += 48.dp.toPx() }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y)); y += 48.dp.toPx() }
        drawCircle(Brush.radialGradient(listOf(Color(0x33FF1460), Color.Transparent), Offset(size.width, 0f),
            size.width * 0.9f), size.width * 0.9f, Offset(size.width, 0f))
        drawCircle(Brush.radialGradient(listOf(Color(0x2278FAFF), Color.Transparent), Offset(0f, size.height),
            size.width), size.width, Offset(0f, size.height))
    }) {
        Column(Modifier.fillMaxSize()) {
            Header(tick)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (tab) {
                    Tab.HOME -> HomeTab(tick) { tab = it }
                    Tab.BRAIN -> BrainSection(CapriService.running, { t, b, c -> Card(t, b, content = c) },
                        { l, c, f -> NeonButton(l, c, onClick = f) })
                    Tab.STASH -> StashTab(tick)
                    Tab.SETUP -> SetupTab(tick) { tick++ }
                    Tab.ABOUT -> AboutTab()
                }
                Spacer(Modifier.height(12.dp))
            }
            TabBar(tab) { tab = it }
        }
    }
}

// -- pieces -------------------------------------------------------------------------------------------------------
@Composable
private fun Header(tick: Int) {
    val on = remember(tick) { CapriService.running }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("> CAPRI.EXE // MOBILE v0.1", color = GREEN, fontFamily = MONO, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.border(1.dp, if (on) GREEN else PINK, CutCornerShape(topStart = 6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(if (on) "● JACKED IN" else "○ OFFLINE", color = if (on) GREEN else PINK, fontFamily = MONO, fontSize = 11.sp)
            }
        }
        Box {  // glitch title: cyan and pink ghosts behind the white
            val style = TextStyle(fontFamily = MONO, fontSize = 54.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Bold)
            Text("CAPRI", style = style, color = CYAN.copy(alpha = 0.7f), modifier = Modifier.offset((-3).dp, 0.dp))
            Text("CAPRI", style = style, color = PINK.copy(alpha = 0.8f), modifier = Modifier.offset(3.dp, 0.dp))
            Text("CAPRI", style = style, color = TEXT)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(PINK, CYAN, Color.Transparent))))
    }
}

@Composable
private fun TabBar(tab: Tab, pick: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xF00A0514)).border(1.dp, EDGE).navigationBarsPadding()
        .padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (t in Tab.entries) {
            val sel = t == tab
            Column(
                Modifier.weight(1f).clickable { pick(t) }
                    .then(if (sel) Modifier.background(Color(0x22FF1460), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)) else Modifier)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.glyph, color = if (sel) PINK else MUTED, fontFamily = MONO, fontSize = 16.sp)
                Text(t.label, color = if (sel) TEXT else MUTED, fontFamily = MONO, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun Card(title: String, body: String = "", accent: Color = PINK, content: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().background(PANEL.copy(alpha = 0.92f), CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp))
            .border(1.dp, EDGE, CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 16.dp).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title, color = CYAN, fontFamily = MONO, fontSize = 14.sp, letterSpacing = 2.sp)
        }
        if (body.isNotEmpty()) Text(body, color = TEXT, fontSize = 14.sp, lineHeight = 20.sp)
        content()
    }
}

@Composable
fun NeonButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier, colors = ButtonDefaults.buttonColors(containerColor = color,
        contentColor = if (color.luminance() < 0.25f) TEXT else BG),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)) {
        Text(label, fontFamily = MONO, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Toggle(title: String, body: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TEXT, fontFamily = MONO, fontSize = 15.sp)
            Text(body, color = MUTED, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(value, set, colors = SwitchDefaults.colors(checkedThumbColor = BG, checkedTrackColor = GREEN,
            uncheckedTrackColor = Color(0xFF241B30), uncheckedBorderColor = EDGE, uncheckedThumbColor = MUTED))
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.border(1.dp, EDGE, CutCornerShape(topStart = 8.dp)).padding(10.dp)) {
        Text(value, color = color, fontFamily = MONO, fontSize = 22.sp)
        Text(label, color = MUTED, fontFamily = MONO, fontSize = 11.sp)
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

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(118.dp).border(2.dp, PINK, CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp)).padding(4.dp)) {
            Image(face, "Capri", Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NETRUNNER // SPRITE", color = PINK, fontFamily = MONO, fontSize = 12.sp, letterSpacing = 2.sp)
            Text("She lives on your screen now, choom.", color = TEXT, fontSize = 16.sp, lineHeight = 21.sp)
            Text("brain: " + (brain?.label ?: "none yet"), color = if (brain != null) GREEN else MUTED, fontFamily = MONO, fontSize = 12.sp)
        }
    }
    if (!canDraw) {
        Card("STEP 1 // ACCESS", "Let her draw over other apps. That's how she floats on your home screen.") {
            NeonButton("Allow", PINK) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
    } else {
        Card(if (on) "JACKED IN" else "READY", if (on) "She's out. Go to your home screen and say hi."
        else "She drops in from the top of your screen.", accent = if (on) GREEN else PINK) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (on) {
                    NeonButton("Jack out", MUTED) { ctx.stopService(Intent(ctx, CapriService::class.java)); on = false }
                    NeonButton("Nap / wake", CYAN) {
                        ctx.startService(Intent(ctx, CapriService::class.java).setAction(CapriService.ACTION_NAP))
                    }
                } else {
                    NeonButton("Summon Capri", PINK) {
                        val i = Intent(ctx, CapriService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                        on = true
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("BADGES", "$earned/${Badge.entries.size}", GREEN, Modifier.weight(1f).clickable { go(Tab.STASH) })
        Stat("GIFTS", "$gifts", CYAN, Modifier.weight(1f).clickable { go(Tab.STASH) })
        Stat("DAYS", "${Achievements.count(ctx, "days")}", PINK, Modifier.weight(1f))
    }
    if (brain == null) Card("NO BRAIN YET", "Give her a brain and she talks back, offline, on your phone.", accent = CYAN) {
        NeonButton("Pick a brain", CYAN) { go(Tab.BRAIN) }
    }
    Card("HOW TO PLAY") {
        val moves = listOf(
            "POKE" to "head: annoyed. body: a hop. double tap: a grin. five quick pokes: she storms off",
            "HOLD" to "talk to her (with a brain) / nap (without)",
            "DRAG" to "pick her up. let go to throw. fling her into an edge and she peeks",
            "SHAKE" to "she gets dizzy. a hard shake throws her",
            "TILT" to "she slides to the low side",
            "NAP" to "tap the sleeping chip to wake her",
            "GIFTS" to "she drops things now and then. tap to keep them",
        )
        for ((k, v) in moves) Row {
            Text(k.padEnd(6), color = PINK, fontFamily = MONO, fontSize = 13.sp, modifier = Modifier.width(62.dp))
            Text(v, color = TEXT, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun StashTab(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val earned = remember(tick) { Badge.entries.count { Achievements.unlocked(ctx, it) } }
    Card("ACHIEVEMENTS", "$earned of ${Badge.entries.size} unlocked", accent = GREEN) {
        LinearProgressIndicator(progress = { earned / Badge.entries.size.toFloat() }, modifier = Modifier.fillMaxWidth(),
            color = GREEN, trackColor = Color(0x22FFFFFF))
        for (row in Badge.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in row) {
                val got = remember(tick) { Achievements.unlocked(ctx, b) }
                val prog = remember(tick) { Achievements.progress(ctx, b) }
                val icon = remember(b, got) {
                    android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888).also {
                        Achievements.draw(android.graphics.Canvas(it), b, 144f, got)
                    }.asImageBitmap()
                }
                Column(Modifier.weight(1f).border(1.dp, if (got) Color(Achievements.tierColors[b.tier]).copy(alpha = 0.6f) else EDGE,
                    CutCornerShape(topStart = 10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, b.title, Modifier.size(54.dp))
                    Text(if (got) b.title else "???", color = if (got) TEXT else MUTED, fontFamily = MONO, fontSize = 12.sp, maxLines = 1)
                    Text(b.how, color = MUTED, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 3, minLines = 3)
                    if (!got && b.goal > 1) {
                        LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = Color(Achievements.tierColors[b.tier]), trackColor = Color(0x22FFFFFF))
                        Text("${Achievements.count(ctx, b.counter)}/${b.goal}", color = MUTED, fontFamily = MONO, fontSize = 10.sp)
                    }
                }
            }
        }
    }
    Card("GIFT STASH", "What she dropped for you. ${remember(tick) { Gift.entries.sumOf { prefs.count(it) } }} collected.", accent = CYAN) {
        for (row in Gift.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in row) {
                val n = remember(tick) { prefs.count(g) }
                val icon = remember(g) { giftBitmap(g, 120).asImageBitmap() }
                Column(Modifier.weight(1f).border(1.dp, if (n > 0) Color(g.color).copy(alpha = 0.6f) else EDGE,
                    CutCornerShape(topStart = 10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, g.label, Modifier.size(44.dp), alpha = if (n > 0) 1f else 0.25f)
                    Text(if (n > 0) g.label else "???", color = if (n > 0) TEXT else MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1)
                    Text("x$n · ${g.rarity}", color = if (g.rarity == "legendary") Color(g.color) else MUTED, fontFamily = MONO, fontSize = 10.sp)
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
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it; refresh() }
    var roam by remember { mutableStateOf(prefs.roam) }
    var talk by remember { mutableStateOf(prefs.talk) }
    var blips by remember { mutableStateOf(prefs.blips) }
    var tilt by remember { mutableStateOf(prefs.tilt) }
    var shake by remember { mutableStateOf(prefs.shake) }
    var game by remember { mutableStateOf(prefs.game) }
    var feed by remember { mutableStateOf(prefs.feed) }
    var size by remember { mutableFloatStateOf(prefs.sizeDp.toFloat()) }

    Card("PERMISSIONS", accent = CYAN) {
        @Composable
        fun line(name: String, ok: Boolean, why: String, fix: () -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (ok) "[OK] " else "[--] ") + name, color = if (ok) GREEN else PINK, fontFamily = MONO, fontSize = 14.sp)
                    Text(why, color = MUTED, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!ok) NeonButton("Fix", PINK, onClick = fix)
            }
        }
        line("Draw over apps", canDraw, "so she can float on your screen") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        line("Microphone", micOk, "so she hears you while you hold her") { mic.launch(Manifest.permission.RECORD_AUDIO) }
        line("Accessibility", access, "sit on icons, react to your apps, scroll with you") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!access) Text("Greyed out? App info > ⋮ > Allow restricted settings, then try again.", color = MUTED, fontSize = 12.sp)
        if (!access) NeonButton("App info", MUTED) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }
    Card("HER BEHAVIOUR") {
        Toggle("Roam", "zaps to a new spot every half a minute or so", roam) { roam = it; prefs.roam = it }
        Toggle("Talk", "greetings, reactions, battery and charger lines", talk) { talk = it; prefs.talk = it }
        Toggle("Blips", "retro talking sounds while she types", blips) { blips = it; prefs.blips = it }
        Toggle("Tilt slide", "she slides to the low side when you tilt your phone", tilt) { tilt = it; prefs.tilt = it }
        Toggle("Shake", "shake her dizzy; a hard shake throws her", shake) { shake = it; prefs.shake = it }
        Toggle("Game", "gifts she drops and achievements to unlock. Off: just her", game) { game = it; prefs.game = it }
        Toggle("Scroll with me", "in TikTok, Reels, Shorts, Reddit, X she reads the captions and comments and " +
            "sometimes reacts. Needs a brain + accessibility. Never stored.", feed) { feed = it; prefs.feed = it }
    }
    Card("SIZE", "${size.toInt()} dp tall") {
        Slider(value = size, onValueChange = { size = it }, valueRange = 90f..240f,
            onValueChangeFinished = {
                prefs.sizeDp = size.toInt()
                if (CapriService.running) {  // she reloads at the new size
                    ctx.stopService(Intent(ctx, CapriService::class.java))
                    val i = Intent(ctx, CapriService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                }
            },
            colors = SliderDefaults.colors(thumbColor = PINK, activeTrackColor = PINK, inactiveTrackColor = Color(0x33FFFFFF)))
    }
}

@Composable
private fun AboutTab() {
    val uri = LocalUriHandler.current
    Card("FAN-MADE", "Capri is an unofficial fan project, made for fun and given away free. It is not made, " +
        "endorsed or sponsored by CD PROJEKT RED or Studio Trigger, and it's not for sale.", accent = PINK)
    Card("CREDITS", accent = CYAN) {
        val credits = listOf(
            "Capri, Cyberpunk & Edgerunners" to "Capri, Cyberpunk: Edgerunners and Cyberpunk 2077 are trademarks and characters of CD PROJEKT S.A. Cyberpunk is a trademark of R. Talsorian Games. All rights to them stay with their owners.",
            "Sprites" to "Capri's sprite art by Lachy (github.com/lachydotmcg), PolyForm Noncommercial 1.0.0. The app icon " +
                "and her nap are cut from it: no official art, audio or logos are in this app.",
            "Model training" to "The CapriAI2 models, the ones trained to be Capri, by ghasty3 (github.com/ghasty3).",
            "Brains" to "llama.cpp by ggml-org (MIT). Each model keeps its own license (Qwen, MiniCPM, Gemma, NVIDIA Nemotron): see its Hugging Face page.",
            "Font" to "Share Tech Mono by Carrois Apostrophe (SIL Open Font License).",
            "This app" to "Code under the PolyForm Noncommercial License 1.0.0: free to use and share, never for sale.",
            "Made by" to "the Capri crew. First Capri on a phone, ever.",
        )
        for ((k, v) in credits) Column {
            Text(k, color = TEXT, fontFamily = MONO, fontSize = 14.sp)
            Text(v, color = MUTED, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    Card("PRIVACY", "Everything runs on your phone. No accounts, no analytics, no servers. Her brain is offline once " +
        "downloaded; the only network use is downloading brains you pick. Accessibility (optional) is only used to " +
        "find icons to sit on, see which app is open and, with Scroll with me on, read a feed's captions for a " +
        "reaction. Nothing is saved or sent. Speech goes through your phone's own recognizer.", accent = GREEN)
    if (REPO_URL.isNotEmpty()) NeonButton("GitHub // source + releases", CYAN) { uri.openUri(REPO_URL) }
    Text("v0.1 alpha // ${Build.MODEL} // Android ${Build.VERSION.RELEASE}", color = MUTED, fontFamily = MONO, fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp))
}
