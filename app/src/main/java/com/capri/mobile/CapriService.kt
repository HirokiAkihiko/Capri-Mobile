package com.capri.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Capri on your phone: a foreground service that keeps her floating over every app. She stands on the bottom of
 * the screen (or sits on app icons and buttons, with the accessibility switch on), blinks and glitches, zaps around,
 * reacts to pokes, shakes, tilts, your charger, your battery, the time and the app you open, peeks out from the
 * screen edges, hides during fullscreen videos and games, and naps in the corner.
 */
class CapriService : Service(), SensorEventListener, CapriAccess.Listener {
    companion object {
        const val ACTION_NAP = "com.capri.mobile.NAP"  // nap / wake, from the notification
        const val ACTION_BYE = "com.capri.mobile.BYE"
        const val ACTION_ASK = "com.capri.mobile.ASK"      // typed from the app: extra "text"
        const val ACTION_BRAIN = "com.capri.mobile.BRAIN"  // the chosen brain changed: (re)load it
        const val ACTION_TYPE = "com.capri.mobile.TYPE"    // the notification's Chat button: the little type box
        const val ACTION_MINI = "com.capri.mobile.MINI"    // Always mini was switched
        const val ACTION_MIC = "com.capri.mobile.MIC"  // mic allowed in the app: she becomes allowed to listen too
        private const val CHANNEL = "capri"
        var running = false
            private set
        @Volatile var brainStatus = "no brain"  // for the app screen
        @Volatile var lastReply = ""
        @Volatile var instance: CapriService? = null  // VoiceActivity hands what you said back through this
        @Volatile var lastEarsError = ""  // the speech recognizer's last error, shown in the app (for debugging)
        @Volatile var chatOpen = false  // the app's Chat tab is on screen: replies show there, not in her bubble
        @Volatile var chatLine = ""     // her reply as it's written, for the Chat tab
        @Volatile var quietStart = false  // restarted by a setting (size, skin): no intro this time
        @Volatile var appOpen = false   // her app is in front: taps go through her, so she never blocks its buttons

        /** Tell a running Capri the mic was just allowed (the app is in front, so she may add the mic to her service). */
        fun micAllowed(c: Context) {
            if (running) c.startService(Intent(c, CapriService::class.java).setAction(ACTION_MIC))
        }

        fun miniChanged(c: Context) {
            if (running) c.startService(Intent(c, CapriService::class.java).setAction(ACTION_MINI))
        }

        fun brainChanged(c: Context) {
            if (running) c.startService(Intent(c, CapriService::class.java).setAction(ACTION_BRAIN))
        }
    }

    private enum class Mode { IDLE, PERCH, DRAG, FALL, AWAY, CHIP, PEEK, HIDDEN, INTRO }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var sprites: Sprites
    private lateinit var her: CapriView
    private lateinit var chip: ChipView
    private lateinit var fx: FxView
    private lateinit var bubble: BubbleView
    private var intro: IntroView? = null
    private var box: TypeBox? = null
    private var ready = false  // her sprites are loaded and she's built
    private var introWait = 0f
    private val silent get() = prefs.skin == "watcher"  // the watcher just sits and watches: no bubble, no brain
    private lateinit var herLp: WindowManager.LayoutParams
    private lateinit var chipLp: WindowManager.LayoutParams
    private lateinit var fxLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var blips: Blips? = null
    private var herOn = false
    private var chipOn = false
    private var fxOn = false
    private var bubbleOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density
    private val k get() = dp / 2.75f  // speeds and forces scale with screen density, so she feels the same everywhere
    private var mode = Mode.FALL
    private var footX = 0f  // where she touches the ground (feet) or her seat (hip), screen px
    private var footY = 0f
    private var vx = 0f
    private var vy = 0f
    private var now = 0f
    private var clipT = 0f
    private var oneShot = false
    private var nextAct = 3f
    private var nextRoam = 25f
    private var glitchFor = 0f
    private var lastFrame = 0L
    private var paused = false
    private var chipTick = 0f
    private var nextUpdate = 20f
    private var always = false    // Always mini: small on purpose, she stays that way
    private var mood = false      // mini for a while because she felt like it
    private var moodUntil = 0f
    private var nextMood = 120f + Random.nextFloat() * 180f
    private var fsMini = false   // mini for a fullscreen video or game
    private var nextFsCheck = 0f
    private val mini get() = always || mood || fsMini
    private var miniAt = 0f       // when she shrinks (after her entrance)
    private var annoyedUntil = 0f
    private var blinkUntil = 0f
    private var nextBlink = 2f
    private var glanceUntil = 0f
    private var nextGlance = 6f
    private var glance = "look_left"
    private var drawn = ""
    private var lastTouch = 0f  // last time you touched her (night mode naps after a while without)

    // where she is
    private var perch: Rect? = null
    private var peekLeft = true
    private var peekY = 0f
    private var peekUntil = 0f
    private var hiddenFrom = Mode.IDLE

    // screen
    private var screenW = 0
    private var floor = 0f
    private var left = 0f
    private var right = 0f
    private var top = 0f

    // touch
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var dragging = false
    private var longPressed = false
    private val pokes = ArrayList<Float>()
    private var lastTap = -1f
    private var grumpyUntil = 0f
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    // senses
    private var sensors: SensorManager? = null
    private var sensing = false
    private val g = FloatArray(3)
    private val spikes = ArrayList<Float>()
    private var lastHard = -9f
    private var dizzyLeft = 0f
    private var tiltX = 0f
    private var slide = 0f
    private var battery = 100
    private var plugged = false
    private var napLowDone = false
    private var lowSaid = false
    private var lastGreet = -9999f
    private var lastAppLine = -9999f
    private val appLines = HashMap<String, Float>()
    private var lastClickZap = -99f
    private var fullscreen = false
    private var probe: View? = null

    // talking
    private var talkText = ""
    private var typeT = 0f
    private var talkUntil = 0f
    private var talkOverChip = false

    // her brain and ears
    private lateinit var brain: Brain
    private var ears: Ears? = null
    private var qwen: QwenEars? = null
    private var holdingToTalk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        startForegroundNow()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        measure()
        val height = (prefs.sizeDp * dp).toInt()
        val skin = prefs.skin
        // decoding all her frames takes a while, and this thread is also the app's: doing it here froze the app
        Thread({
            val s = Sprites(this, height, skin)
            handler.post { if (instance === this) { sprites = s; build() } }
        }, "capri-sprites").start()
    }

    /** Her sprites are in: the rest of her. */
    private fun build() {
        val margin = (22 * dp).toInt()
        her = CapriView(this, sprites, margin).apply { onTouch = ::touchHer }
        herLp = overlayParams(sprites.w + 2 * margin, sprites.h + 2 * margin, touchable = true)
        always = prefs.mini
        chip = ChipView(this, (prefs.sizeDp * 0.9f * dp).toInt(), (prefs.sizeDp * 0.62f * dp).toInt(), prefs.skin).apply { onTouch = ::touchChip }
        chipLp = overlayParams(chip.widthPx, chip.heightPx + chip.pad, touchable = true)
        fx = FxView(this)
        fxLp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)
        bubble = BubbleView(this, dp).apply { onTap = ::hush }
        bubbleLp = overlayParams(bubble.maxW, 10, touchable = true)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        ContextCompat.registerReceiver(this, events, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)  // system broadcasts still arrive; nothing else can send these
        CapriAccess.listener = this
        addProbe()
        brain = Brain(this)
        brain.onRemembered = { facts -> remembered(facts) }
        loadBrain(quiet = true)
        footX = left + (right - left) * 0.72f
        sense(true)
        if (quietStart && mini) enterMini() else if (quietStart || ModelStore.lowRam(this)) dropIn() else startIntro()
        quietStart = false
        ready = true
        startLoop()
    }

    /** Restarted by a setting while always mini: she's just there, small. */
    private fun enterMini() {
        fitChip()
        placeChip()
        showChip()
        mode = Mode.CHIP
    }

    /** Her entrance: the fullscreen intro, ending exactly where she lands. */
    private fun startIntro() {
        footY = floor
        mode = Mode.INTRO
        intro = IntroView(this)
    }

    /** Without the intro: she drops in from above the screen and lands with a splash. */
    private fun dropIn() {
        footY = -sprites.h * 0.2f
        mode = Mode.FALL
        her.clip = "dangle"
        showHer()
        handler.postDelayed({ greet() }, 1800)
    }

    private fun stepIntro(dt: Float, v: IntroView) {
        if (mode == Mode.INTRO && v.parent == null) {  // waiting for its frames (a moment at most)
            introWait += dt
            if (v.ready && v.usable) {
                val c = sprites["idle"]
                val dm = resources.displayMetrics
                val full = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else Rect(0, 0, dm.widthPixels, dm.heightPixels)
                v.aim(full.width().toFloat(), full.height().toFloat(), footX - 0.05f * prefs.sizeDp * dp, floor - 0.5f * prefs.sizeDp * dp, c.h.toFloat())
                // "touchable": a click-through overlay can't be solid, and this is only up for a second
                wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
            } else if (v.ready || introWait > 4f) {
                intro = null
                dropIn()
            }
            return
        }
        v.step(dt)
        if (v.landed && mode == Mode.INTRO) landFromIntro()
        if (v.done) {
            runCatching { wm.removeView(v) }
            v.release()
            intro = null
        }
    }

    /** The intro ends on her: she rebuilds right there, with a burst of her own pixels. */
    private fun landFromIntro() {
        miniAt = now + 2f  // always mini: long enough to see her land
        showHer()
        arrive()
        glitchFor = 0.45f
        val c = sprites["idle"]
        val cx = footX
        val cy = footY - c.h * 0.45f
        fx.splash(cx, cy, sprites.palette, 3f * dp, 44)
        fx.splash(cx, cy, intArrayOf(0xFF78FAFF.toInt(), 0xFFFFFFFF.toInt()), 2.4f * dp, 22)
        showFx()
        handler.postDelayed({ greet() }, 900)
    }

    /** The phone needs memory back: her eyes and ears go first (they come back when needed). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!ready || level < TRIM_MEMORY_RUNNING_LOW) return
        brain.dropEyes()
        qwen?.free()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready) return START_STICKY
        when (intent?.action) {
            ACTION_NAP -> if (mode == Mode.CHIP) wake() else nap()
            ACTION_MINI -> {
                always = prefs.mini
                if (!mini) wake()
                else if (mode == Mode.CHIP) { hideChip(); fitChip(); placeChip(); showChip() }  // napping: up into her mini form
                else miniAt = 0f
            }
            ACTION_BYE -> stopSelf()
            ACTION_ASK -> intent.getStringExtra("text")?.let { think(it) }
            ACTION_TYPE -> { wake(); openBox() }
            ACTION_BRAIN -> loadBrain(quiet = false)
            ACTION_MIC -> startForegroundNow()  // same notification, now also allowed to use the mic
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        if (CapriAccess.listener === this) CapriAccess.listener = null
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        sense(false)
        runCatching { unregisterReceiver(events) }
        if (ready) for ((v, on) in listOf(her to herOn, chip to chipOn, fx to fxOn, bubble to bubbleOn)) if (on) runCatching { wm.removeView(v) }
        intro?.let { v -> runCatching { wm.removeView(v) }; v.release() }
        closeBox()
        probe?.let { runCatching { wm.removeView(it) } }
        blips?.release()
        ears?.release()
        qwen?.release()
        if (ready) {
            brain.cancel()
            brain.unload()
        }
        brainStatus = "no brain"
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        measure()  // rotated: the floor moved
        if (!ready) return
        footX = footX.coerceIn(left + sprites.w * 0.3f, right - sprites.w * 0.3f)
        if (mode == Mode.IDLE) footY = floor
        if (mode == Mode.PERCH) startFall()
        if (chipOn) {
            placeChip()
            wm.updateViewLayout(chip, chipLp)
        }
    }

    // -- the loop ------------------------------------------------------------------------------------------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            val dt = if (lastFrame == 0L) 0.016f else ((nanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
            lastFrame = nanos
            update(dt)
            if (!paused && running) {
                val wait = pace()
                if (wait == 0L) Choreographer.getInstance().postFrameCallback(this)
                else Choreographer.getInstance().postFrameCallbackDelayed(this, wait)
            }
        }
    }

    /** ms to wait before the next frame: every frame while she flies or bursts, ~25 fps standing, ~15 fps mini. */
    private fun pace(): Long = when {
        intro != null || fxOn || mode == Mode.FALL || mode == Mode.DRAG || mode == Mode.AWAY || mode == Mode.INTRO -> 0L
        bubbleOn && !bubble.done -> 20L
        mode == Mode.CHIP || mode == Mode.HIDDEN -> 60L
        else -> 50L  // her clips run at 8-15 fps, so 20 is plenty
    }

    private fun startLoop() {
        lastFrame = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val sleepy get() = (battery <= 20 && !plugged) || hour() in 0..4

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun update(dt: Float) {
        now += dt
        her.t = now
        dizzyLeft = maxOf(0f, dizzyLeft - dt)
        her.dizzy = dizzyLeft
        when (mode) {
            Mode.IDLE -> idle(dt)
            Mode.PERCH -> perched(dt)
            Mode.PEEK -> {
                animate("idle", dt, loop = true)
                if (now > peekUntil) popOut()
            }
            Mode.DRAG -> animate("dangle", dt, loop = true)
            Mode.FALL -> fall(dt)
            Mode.AWAY, Mode.HIDDEN, Mode.INTRO -> Unit
            Mode.CHIP -> {
                chip.t += dt
                chipTick += dt
                if (chipTick > 1 / 15f) {  // the z's don't need 60 fps
                    chipTick = 0f
                    if (chip.mini) chip.look = miniLook()
                    chip.invalidate()
                }
            }
        }
        if (mini && (mode == Mode.IDLE || mode == Mode.PERCH) && now >= miniAt && !holdingToTalk) nap()
        if (fsMini && now > nextFsCheck) {
            nextFsCheck = now + 1.5f
            probe?.requestApplyInsets()
            val vis = probe?.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars())
            if (vis == true && Build.VERSION.SDK_INT >= 30) { fullscreen = false; onFullscreen(false) }
        }
        moods()
        intro?.let { stepIntro(dt, it) }
        glitchFor -= dt
        her.split = if (glitchFor > 0) (2f + Random.nextFloat() * 5f) * dp else her.split * exp(-dt * 12f)
        her.flash = maxOf(0f, her.flash - dt * 2.5f)
        if (fxOn && !fx.step(dt)) hideFx()
        feed()
        memoryBeat()
        if (now > nextUpdate) {  // Wi-Fi only, and it only really asks GitHub a few times a day
            nextUpdate = now + 1800f
            Updates.check(this) { handler.post { if (around()) say("New update's out, choom. Check your notifications.") } }
        }
        talk(dt)
        mouth()
        ghost()
        if (herOn) {
            placeHer()
            her.bob = her.bob.toInt().toFloat()  // whole pixels: an unchanged frame isn't redrawn (battery)
            val key = "${her.clip}${her.frame}|${her.mouth}|${her.bob}|${(her.split * 2).toInt()}|${(her.flash * 50).toInt()}|" +
                "${her.rot.toInt()}|${her.flip}|${if (dizzyLeft > 0) (now * 30).toInt() else 0}"
            if (key != drawn) { drawn = key; her.invalidate() }
        }
    }

    private var ghosted = false

    /** While her own app is open taps go through her (Android wants click-through overlays at 80% or less). */
    private var keyboardUp = false
    private var overInput = false
    private var nextGhostCheck = 0f
    @Volatile private var imeProbe = false

    /** Is a text box (from accessibility) under her? Then taps go through her so you can type. */
    private fun overText(): Boolean {
        val boxes = CapriAccess.inputs
        if (boxes.isEmpty()) return false
        val r = if (herOn) Rect(herLp.x + her.margin, herLp.y + her.margin, herLp.x + herLp.width - her.margin, herLp.y + herLp.height - her.margin)
        else if (chipOn) Rect(chipLp.x, chipLp.y, chipLp.x + chipLp.width, chipLp.y + chipLp.height) else return false
        return boxes.any { Rect.intersects(it, r) }
    }

    private fun ghost() {
        if (now > nextGhostCheck) {
            nextGhostCheck = now + 0.4f
            keyboardUp = CapriAccess.keyboard || imeProbe
            overInput = overText()
        }
        val want = appOpen || keyboardUp || overInput
        if (want == ghosted) return
        ghosted = want
        val touch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        for (lp in listOf(herLp, chipLp)) {
            lp.flags = if (ghosted) lp.flags or touch else lp.flags and touch.inv()
            lp.alpha = if (ghosted) 0.8f else 1f
        }
        if (herOn) wm.updateViewLayout(her, herLp)
        if (chipOn) wm.updateViewLayout(chip, chipLp)
    }

    /** Now and then she goes mini for a few minutes, because she feels like it. Never while you're talking to her. */
    private fun moods() {
        if (always) { mood = false; return }
        val quiet = !bubbleOn && !holdingToTalk && box == null
        if (mood) {
            if (now > moodUntil && quiet) {
                mood = false
                nextMood = now + 180f + Random.nextFloat() * 420f
                wake()
            }
        } else if (prefs.moods && now > nextMood && quiet && (mode == Mode.IDLE || mode == Mode.PERCH)) {
            mood = true
            moodUntil = now + 60f + Random.nextFloat() * 180f
            miniAt = 0f
        }
    }

    /** Which of her mini looks right now: thinking, talking, annoyed, or blinking and glancing around. */
    private fun miniLook(): String {
        val mad = now < annoyedUntil || now < grumpyUntil
        if (bubbleOn && !bubble.done) {
            val flap = (now * 9).toInt() % 3
            return if (mad) (if (flap == 2) "annoyed" else "annoyed_talk") else listOf("talk", "talk2", "idle")[flap]
        }
        if (brain.busy) return if ((now * 5).toInt() % 3 == 0) "glitch" else "idle"
        if (now > nextBlink) { blinkUntil = now + 0.14f; nextBlink = now + 2f + Random.nextFloat() * 4f }
        if (now < blinkUntil) return "blink"
        if (mad) return "annoyed"
        if (now > nextGlance) {
            glanceUntil = now + 0.9f + Random.nextFloat()
            nextGlance = glanceUntil + 5f + Random.nextFloat() * 9f
            glance = if (Random.nextBoolean()) "look_left" else "look_right"
        }
        return if (now < glanceUntil) glance else "idle"
    }

    /** Her mouth moves while her line is being written out (same rhythm as the desktop), over her standing frames. */
    private fun mouth() {
        val talking = bubbleOn && !bubble.done && mode == Mode.IDLE &&
            (her.clip == "idle" || her.clip == "grin" || her.clip == "blink" || her.clip == "blink_glitch")
        her.mouth = if (!talking) -1 else if ((now * 12).toInt() % 4 != 0) (now * 12 + 0.5f).toInt() % 5 else 0
    }

    private fun animate(name: String, dt: Float, loop: Boolean) {
        if (her.clip != name) { her.clip = name; clipT = 0f }
        clipT += dt * if (sleepy && name.startsWith("idle")) 0.7f else 1f
        val c = sprites[name]
        val f = (clipT * c.fps).toInt()
        her.frame = if (loop) f % c.count else f.coerceAtMost(c.count - 1)
    }

    private fun play(name: String) {
        her.clip = name
        clipT = 0f
        oneShot = true
        if ("glitch" in name) glitchFor = 0.4f
    }

    /** The one-shot clip finished? Then back to [base]. */
    private fun oneShotDone(dt: Float, base: String): Boolean {
        if (!oneShot) return true
        animate(her.clip, dt, loop = false)
        if ((clipT * sprites[her.clip].fps).toInt() >= sprites[her.clip].count) {
            oneShot = false
            animate(base, 0f, loop = true)
            return true
        }
        return false
    }

    private fun idle(dt: Float) {
        her.bob = (sin(now * 1.7f) * 0.5f + 0.5f) * 3f * dp
        her.rot = if (dizzyLeft > 0) sin(now * 9f) * 9f * minOf(1f, dizzyLeft) else her.rot * exp(-dt * 6f)
        tilt(dt)
        if (!oneShotDone(dt, "idle")) return
        animate("idle", dt, loop = true)
        if (now > nextAct) {  // something small every few seconds: blink, grin, or a glitch
            val r = Random.nextFloat()
            play(if (r < 0.55f) "blink" else if (r < 0.8f && !sleepy) "grin" else if (r < 0.9f) "blink_glitch" else "glitch")
            nextAct = now + (if (sleepy) 2f else 3f) + Random.nextFloat() * 4f
        }
        autoMoves()
    }

    private fun perched(dt: Float) {
        her.bob = 0f
        her.rot = if (dizzyLeft > 0) sin(now * 9f) * 6f * minOf(1f, dizzyLeft) else 0f
        if (!oneShotDone(dt, "perch")) return
        animate("perch", dt, loop = true)
        if (now > nextAct) {
            play(if (Random.nextFloat() < 0.75f) "perch_blink" else "perch_glitch")
            nextAct = now + 3f + Random.nextFloat() * 4f
        }
        autoMoves()
    }

    /** Roaming, and napping on her own late at night. */
    private fun autoMoves() {
        if (hour() in 0..4 && now - lastTouch > 240f && now > 60f) { nap(); return }
        if (prefs.roam && now > nextRoam) {
            nextRoam = now + 18f + Random.nextFloat() * 22f
            roam()
        }
    }

    /** Phone tilted: she slides toward the low side, leaning into it. */
    private fun tilt(dt: Float) {
        if (!prefs.tilt || dizzyLeft > 0) { slide *= exp(-dt * 4f); return }
        if (abs(tiltX) > 2.2f) {
            slide += tiltX * 230f * k * dt
            her.flip = slide < 0
        }
        slide *= exp(-dt * 2.2f)
        if (abs(slide) > 4f) {
            footX += slide * dt
            val lo = left + sprites.w * 0.3f
            val hi = right - sprites.w * 0.3f
            if (footX < lo || footX > hi) { footX = footX.coerceIn(lo, hi); slide = 0f }
            her.rot = (-tiltX * 1.6f).coerceIn(-10f, 10f)
        }
    }

    private fun fall(dt: Float) {
        animate("dangle", dt, loop = true)
        her.bob = 0f
        her.rot *= exp(-dt * 8f)
        val prevY = footY
        vy += 3200f * k * dt
        footX += vx * dt
        footY += vy * dt
        val c = sprites["idle"]
        val halfW = c.w * 0.3f
        // flung hard into a side, high up: she grabs the edge and peeks
        if (footX < left + halfW || footX > right - halfW) {
            val leftSide = footX < left + halfW
            if (abs(vx) > 1700f * k && footY < floor - c.h * 0.25f) { peek(leftSide); return }
            footX = footX.coerceIn(left + halfW, right - halfW)
            vx = -vx * 0.5f
        }
        if (footY < top + c.h * 0.4f && vy < 0) vy *= 0.9f  // flung at the ceiling: slow down
        // falling past the top of an icon or button: she lands on it and sits
        if (vy > 0) for (r in CapriAccess.ledges) {
            if (prevY <= r.top && footY >= r.top && footX in r.left.toFloat()..r.right.toFloat() && r.top < floor - 40 * dp &&
                r.top > top + sprites.h * 0.5f) {
                sit(r)
                fx.splash(footX, r.top.toFloat(), sprites.palette, 2.5f * dp, 10)
                showFx()
                return
            }
        }
        if (footY >= floor) {
            footY = floor
            if (vy > 1500f * k) {  // hard landing: bounce once
                vy = -vy * 0.28f
                vx *= 0.6f
                fx.splash(footX, floor, sprites.palette, 3f * dp, 14)
                showFx()
                glitchFor = 0.25f
            } else {
                vx = 0f
                vy = 0f
                mode = Mode.IDLE
                fx.splash(footX, floor, sprites.palette, 3f * dp)
                showFx()
                play("glitch")
                nextAct = now + 3f
            }
        }
    }

    private fun startFall() {
        perch = null
        her.rot = 0f
        oneShot = false
        mode = Mode.FALL
    }

    private fun sit(r: Rect) {
        perch = Rect(r)
        footY = r.top.toFloat()
        footX = footX.coerceIn(r.left + 4 * dp, r.right - 4 * dp)
        vx = 0f; vy = 0f
        mode = Mode.PERCH
        oneShot = false
        animate("perch", 0f, loop = true)
        nextAct = now + 2f
    }

    private fun peek(leftSide: Boolean) {
        mode = Mode.PEEK
        peekLeft = leftSide
        val c = sprites["idle"]
        peekY = (footY - c.ay * c.h + c.h / 2f).coerceIn(top + c.w, floor - c.w)
        peekUntil = now + 25f + Random.nextFloat() * 20f
        her.flip = false
        her.rot = if (leftSide) 90f else -90f
        her.bob = 0f
        vx = 0f; vy = 0f
        if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.peek))
    }

    private fun popOut() {
        val c = sprites["idle"]
        footX = if (peekLeft) left + c.w * 0.4f else right - c.w * 0.4f
        footY = peekY + c.h * 0.35f
        vx = (if (peekLeft) 650f else -650f) * k
        vy = -500f * k
        startFall()
    }

    // -- touch ----------------------------------------------------------------------------------------------
    /** Holding her: with a brain she listens while you hold (let go to send); without one, she naps. */
    private val longPress = Runnable {
        if (dragging) return@Runnable
        longPressed = true
        if (ModelStore.active(this) != null) listen() else nap()
    }

    private fun touchHer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode != Mode.IDLE && mode != Mode.FALL && mode != Mode.PERCH && mode != Mode.PEEK) return false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(e) }
                downX = e.rawX; downY = e.rawY
                dragging = false
                longPressed = false
                lastTouch = now
                handler.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging && !longPressed && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    handler.removeCallbacks(longPress)
                    dragging = true
                    if (mode == Mode.PEEK) popOut()
                    val c = sprites["idle"]
                    if (mode == Mode.PERCH) footY += c.h * 0.1f
                    grabX = downX - footX; grabY = downY - footY
                    perch = null
                    her.rot = 0f
                    mode = Mode.DRAG
                    oneShot = false
                    her.bob = 0f
                    glitchFor = 0.2f
                }
                if (dragging) {
                    footX = e.rawX - grabX
                    footY = e.rawY - grabY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (holdingToTalk) {  // let go: that's the end of what you said
                    holdingToTalk = false
                    ears?.stop()
                    if (qwen != null) { qwen?.stop(); say("...", hold = true) }
                }
                tracker?.addMovement(e)
                if (dragging) {  // let go: she flies with the flick and falls
                    tracker?.computeCurrentVelocity(1000, 6000f * k)
                    vx = tracker?.xVelocity ?: 0f
                    vy = tracker?.yVelocity ?: 0f
                    mode = Mode.FALL
                    if (hypot(vx, vy) > 3500f * k && Random.nextFloat() < 0.4f) say(Lines.pick(Lines.thrown))
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                    tap(e.x, e.y)
                }
                tracker?.recycle()
                tracker = null
                dragging = false
            }
        }
        return true
    }

    private val singleTap = Runnable { singleTapNow() }
    private var tapX = 0f
    private var tapY = 0f

    /** A poke (x, y inside her window). Head: annoyed. Body: a hop. Double tap: a grin. Five quick pokes: she's out. */
    private fun tap(x: Float, y: Float) {
        if (mode == Mode.PEEK) { popOut(); say(Lines.pick(Lines.popOut)); return }
        if (now < grumpyUntil) return
        pokes.add(now)
        pokes.removeAll { now - it > 3f }
        if (pokes.size >= 5) {
            pokes.clear()
            handler.removeCallbacks(singleTap)
            grumpyUntil = now + 15f
            glitchFor = 0.9f
            her.flash = 0.5f
            say(Lines.pick(Lines.mad))
            handler.postDelayed({ if (mode == Mode.IDLE || mode == Mode.PERCH) roam() }, 900)
            return
        }
        if (now - lastTap < 0.32f) {
            handler.removeCallbacks(singleTap)
            lastTap = -1f
            tapX = x; tapY = y
            poke()
            return
        }
        lastTap = now
        tapX = x; tapY = y
        handler.postDelayed(singleTap, 320)
    }

    /** One tap: the little box to type to her in. Without a brain to answer it's a poke. */
    private fun singleTapNow() {
        if (silent || ModelStore.active(this) == null) poke() else openBox()
    }

    private fun openBox() {
        if (box != null) return
        hush()
        val b = TypeBox(this, dp, send = { think(it) }, close = ::closeBox)
        val lp = overlayParams((right - left - 24 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            flags = (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            x = (left + 12 * dp).toInt()
            y = (top + 12 * dp).toInt()
        }
        wm.addView(b, lp)
        box = b
        b.open()
    }

    private fun closeBox() {
        box?.let { runCatching { wm.removeView(it) } }
        box = null
    }

    private fun poke() {
        if (mode == Mode.CHIP) {  // mini: she glares
            annoyedUntil = now + 2.5f
            if (Random.nextFloat() < 0.7f) say(Lines.pick(Lines.headPoke))
            return
        }
        val c = sprites[her.clip]
        val spriteTop = her.height / 2f - c.h / 2f - her.bob
        her.flip = tapX < her.width / 2f  // she turns to the side you poked
        if (tapY < spriteTop + c.h * 0.32f) {
            play(if (mode == Mode.PERCH) "perch_glitch" else "glitch")
            glitchFor = 0.5f
            if (Random.nextFloat() < 0.7f) say(Lines.pick(Lines.headPoke))
        } else {
            if (mode == Mode.IDLE) { vy = -900f * k; vx = 0f; mode = Mode.FALL }  // a little hop
            else play("perch_blink")
            if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.bodyPoke))
        }
    }

    private fun touchChip(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; grabX = e.rawX - chipLp.x; grabY = e.rawY - chipLp.y
                dragging = false
                longPressed = false
                lastTouch = now
                if (chip.mini) handler.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed && (dragging || hypot(e.rawX - downX, e.rawY - downY) > slop)) {  // slide the chip anywhere
                handler.removeCallbacks(longPress)
                dragging = true
                chipLp.x = (e.rawX - grabX).toInt()
                chipLp.y = (e.rawY - grabY).toInt()
                wm.updateViewLayout(chip, chipLp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (holdingToTalk) {  // let go: that's the end of what you said
                    holdingToTalk = false
                    ears?.stop()
                    if (qwen != null) { qwen?.stop(); say("...", hold = true) }
                } else if (dragging) {
                    if (chip.mini) { prefs.miniX = chipLp.x; prefs.miniY = chipLp.y }
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                    lastTouch = now
                    if (chip.mini) tap(e.x, e.y) else wake()
                }
                dragging = false
            }
        }
        return true
    }

    // -- her brain and ears ---------------------------------------------------------------------------------------
    private fun loadBrain(quiet: Boolean) {
        if (silent) { brain.unload(); brainStatus = "the watcher doesn't talk"; return }
        val m = ModelStore.active(this)
        if (m == null) { brain.unload(); brainStatus = "no brain"; return }
        val lm = ModelStore.remote(m)
        if (!lm && brain.loaded?.id == m.id && brain.canSee == ModelStore.eyesInstalled(this, m)) return
        brainStatus = if (lm) "connecting to ${m.label}..." else "loading ${m.label}..."
        if (!quiet) say("Swapping my brain... hold on, choom.")
        brain.load(m) { err ->
            val name = if (m.id == ModelStore.LM.id) "LM Studio: ${brain.remoteName}" else m.label
            brainStatus = if (err == null) "$name online" + (if (brain.canSee) " + eyes" else "")
            else if (lm) err else "couldn't load ${m.label}: $err"
            if (err != null) say(if (lm) "Can't reach my brain, choom: $err." else "Brain glitch. That one won't boot on this phone.")
            else if (!quiet) say("Brain online. Hold me and talk.")
        }
    }

    private fun listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("Let me hear you first: allow the mic in my app.")
            return
        }
        if (ModelStore.earsReady(this)) {  // her own ears, same as the desktop
            val q = qwen ?: QwenEars(this, heard = { think(it) }, failed = { why ->
                holdingToTalk = false
                say(if (why == "mic") "I can't hear you: allow the mic in my app." else "Didn't catch that, choom.")
            }).also { qwen = it }
            holdingToTalk = true
            say("listening...", hold = true)
            play(if (mode == Mode.PERCH) "perch_blink" else "blink")
            q.start()
            return
        }
        val e = ears ?: Ears(this, heard = { think(it) }, level = { }, failed = { why ->
            holdingToTalk = false
            when (why) {
                "nothing" -> say("Didn't catch that, choom.")
                "mic" -> say("I can't hear you: allow the mic in my app.")
                else -> {  // this phone's recognizer won't work from the overlay: its own voice popup instead
                    lastEarsError = ears?.lastError.orEmpty()
                    voicePopup()
                }
            }
        }).also { ears = it }
        if (!e.available) { voicePopup(); return }
        holdingToTalk = true
        say("listening...", hold = true)
        play(if (mode == Mode.PERCH) "perch_blink" else "blink")
        e.start()
    }

    /** The phone's own voice-typing popup (VoiceActivity): works where the overlay's recognizer can't. */
    private fun voicePopup() {
        try {
            startActivity(Intent(this, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            say("Talk into the popup, choom.", hold = true)
        } catch (e: Exception) {
            say("My ears glitched${if (lastEarsError.isNotEmpty()) " ($lastEarsError)" else ""}. Type to me in my app.")
        }
    }

    /** What the voice popup heard (null: nothing / no voice typing; problem "" = you closed it). */
    fun voiceResult(text: String?, problem: String?) {
        handler.post {
            holdingToTalk = false
            when {
                text != null -> think(text)
                problem == "" -> hush()
                problem != null -> say(problem)
                else -> say("Didn't catch that, choom.")
            }
        }
    }

    private val LOOK = Regex("\\b(look at|looking at|see (this|that|my)|what do you see|what('?s| is) (this|that|on my screen)|" +
        "check (this|that) out|my screen|rate (this|that|my)|what am i (watching|doing|looking at|playing)|this (pic|picture|video|game|meme))\\b",
        RegexOption.IGNORE_CASE)

    /**
     * "Look at this": with eyes she takes a screenshot and really looks (her bubble steps aside for the shot);
     * without them she reads the words on screen; without accessibility she says she can't see.
     */
    private fun look(text: String) {
        val access = CapriAccess.instance
        if (access == null) { say("I can't see your screen yet: switch me on in Accessibility (Setup tab).", you = text); return }
        if (!brain.canSee || Build.VERSION.SDK_INT < 30) {  // no eyes: read the screen's words instead
            val words = access.screenText(700)
            say("...", you = text, hold = true)
            brain.ask("$text\n[The words on my screen right now: \"$words\"]",
                live = { say(it, you = text, hold = true, live = true) }) { reply ->
                lastReply = reply
                say(reply.ifEmpty { "Can't make it out, choom." }, you = text)
            }
            return
        }
        hush()
        handler.postDelayed({
            val seen = access.screenText(900)  // names, usernames, messages: read exactly, and remembered
            access.screenshot(if (brain.remote) 1280 else 768) { shot ->  // a PC's brain can take in a sharper picture
                if (shot == null) { say("Couldn't grab your screen, choom.", you = text); return@screenshot }
                say("*looking*", you = text, hold = true)
                brain.look(text, shot, seen = seen, live = { say(it, you = text, hold = true, live = true) }, done = { reply ->
                    lastReply = reply
                    say(reply.ifEmpty { failed("My optics glitched. Try again?") }, you = text)
                })
            }
        }, 250)  // a beat for her bubble to leave the shot
    }

    /** Something you said or typed: she thinks it over with her brain and answers in her bubble. */
    private fun think(text: String) {
        lastTouch = now
        if (Regex("\\b(go to sleep|take a nap|nap time|go nap)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            say("Fine. Wake me when it's fun."); handler.postDelayed({ nap() }, 1500); return
        }
        wake()
        if (brain.loaded == null) {
            say(if (brain.loading) "Still booting my brain, choom. Give me a sec." else "I've got no brain yet. Grab one in my app.")
            return
        }
        if (brain.busy) return
        if (LOOK.containsMatchIn(text)) { look(text); return }
        say("...", you = text, hold = true)
        brain.ask(text, live = { say(it, you = text, hold = true, live = true) }) { reply ->
            lastReply = reply
            say(reply.ifEmpty { failed("My brain fizzled. Say that again?") }, you = text)
        }
    }

    /** What she says when a reply came back empty. */
    private fun failed(line: String) = if (brain.remote && brain.lastError.isNotEmpty()) {
        brainStatus = "my brain stopped answering: ${brain.lastError}"
        "My brain stopped answering, choom: ${brain.lastError.take(60)}."
    } else line

    // -- scroll with me: she watches your feed and sometimes has something to say -------------------------------
    private val feeds = mapOf(
        "com.zhiliaoapp.musically" to "TikTok", "com.ss.android.ugc.trill" to "TikTok", "com.ss.android.ugc.aweme" to "TikTok",
        "com.instagram.android" to "Instagram", "com.google.android.youtube" to "YouTube", "com.reddit.frontpage" to "Reddit",
        "com.twitter.android" to "X", "com.facebook.katana" to "Facebook",
    )
    // -- her memory: what she saved, and memories she brings up on her own ------------------------------------------
    private var lastShard = -999f
    private var nextMemory = 600f

    /** She just wrote something down: now and then she lets you know (that's the "she actually remembers" moment). */
    private fun remembered(facts: List<String>) {
        if (now - lastShard < 120f || !around()) return
        lastShard = now
        handler.postDelayed({
            if (!bubbleOn && around()) {
                lastTouch = now
                say("[memory shard] " + facts.first())
                her.flash = 0.4f
            }
        }, 3500)
    }

    /** Every so often she remembers something about your life out of nowhere and brings it up (or trolls you with it). */
    private fun memoryBeat() {
        if (now < nextMemory || !around() || bubbleOn || brain.loaded == null || brain.busy || !prefs.remember) return
        nextMemory = now + 600f + Random.nextFloat() * 900f
        if (Random.nextFloat() < 0.4f) return  // not every time: it should feel like her, not a timer
        val fact = brain.memory.callback() ?: return
        val user = prefs.userName.ifBlank { "your choom" }
        brain.once("Out of nowhere you remember this about $user's life: \"$fact\". Bring it up in ONE short line, " +
            "like a friend who remembers: ask about it, tease them about it, or troll them a little. Max 12 words.") { line ->
            if (line.length > 3 && around() && !bubbleOn) { lastTouch = now; say(line) }
        }
    }

    private var nextFeed = 0f
    private var lastFeedText = ""

    /** Apps she never looks at by herself: money, passwords, the phone's own screens. */
    private val PRIVATE = Regex("(?i)bank|wallet|paypal|venmo|cash|revolut|finance|chase|wellsfargo|citi|barclays|hsbc|" +
        "coinbase|binance|crypto|metamask|authenticator|authy|password|bitwarden|1password|lastpass|keepass|dashlane|" +
        "settings|packageinstaller|permissioncontroller|systemui|inputmethod|launcher|com\\.capri\\.mobile")
    private val homeApp by lazy {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName.orEmpty()
    }

    private fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /**
     * React on her own: every minute or two she glances at the app you're in and says one short thing. She looks at
     * the picture if the brain can do it cheaply, and otherwise reads the words. Nothing from here is remembered.
     */
    private fun feed() {
        if (!prefs.feed || now < nextFeed || !around() || bubbleOn || box != null || holdingToTalk) return
        val pkg = CapriAccess.foreground
        val app = feeds[pkg] ?: appName(pkg)
        if (pkg.isEmpty() || pkg == homeApp || PRIVATE.containsMatchIn(pkg) || PRIVATE.containsMatchIn(app)) return
        nextFeed = now + if (brain.remote) 40f + Random.nextFloat() * 70f else 240f + Random.nextFloat() * 240f
        if (brain.loaded == null || brain.busy || Random.nextFloat() < 0.15f) return
        val cloudOk = !brain.cloud || prefs.cloudGlance  // your chats only go to NVIDIA by themselves if you said so
        if (!cloudOk && pkg !in feeds) return
        val access = CapriAccess.instance ?: return
        val text = access.screenText()
        if (access.sawPassword || (text.length >= 40 && text == lastFeedText)) return
        lastFeedText = text
        val user = prefs.userName.ifBlank { "your choom" }
        val where = if (app.isEmpty()) "on their phone" else "in $app"
        val how = "React in ONE short line, like a friend looking over their shoulder: a joke, a hot take or a roast. " +
            "Don't describe the screen, don't mention apps, screens or text. Max 10 words."
        val done = { line: String ->
            if (line.length > 3 && around() && !bubbleOn && CapriAccess.foreground == pkg) {
                lastTouch = now
                say(line)
            }
        }
        // an on-phone brain takes a while over a picture, so it reads the words whenever there are any
        if (brain.canSee && cloudOk && Build.VERSION.SDK_INT >= 30 && brain.remote) {
            val words = if (text.length >= 40) " The words on it: \"$text\"." else ""
            access.screenshot(if (brain.remote) 1280 else 768) { shot ->
                if (shot == null || brain.busy) return@screenshot
                brain.look("", shot, live = {}, done = done, ask = "You and $user are hanging out $where; that's their " +
                    "screen right now (the pink-haired sprite or the green eyes are you, ignore them).$words $how")
            }
            return
        }
        if (text.length < 40) return
        brain.once("You and $user are hanging out $where. On the screen: \"$text\"\n$how", done)
    }

    // -- senses: motion ------------------------------------------------------------------------------------------
    private fun sense(on: Boolean) {
        val sm = sensors ?: return
        if (on && !sensing) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            sensing = true
        } else if (!on && sensing) {
            sm.unregisterListener(this)
            sensing = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) g[i] = g[i] * 0.85f + e.values[i] * 0.15f  // gravity, low-passed
        val lx = e.values[0] - g[0]
        val ly = e.values[1] - g[1]
        val lz = e.values[2] - g[2]
        val jolt = sqrt(lx * lx + ly * ly + lz * lz)
        // which way is "screen right" in the phone's own axes depends on how it's turned
        @Suppress("DEPRECATION")
        val rot = wm.defaultDisplay.rotation
        tiltX = when (rot) { Surface.ROTATION_90 -> g[1]; Surface.ROTATION_180 -> g[0]; Surface.ROTATION_270 -> -g[1]; else -> -g[0] }
        val awake = mode == Mode.IDLE || mode == Mode.PERCH || mode == Mode.PEEK
        if (!awake || !prefs.shake) return
        if (jolt > 30f && now - lastHard > 1.5f) {  // a hard shake throws her
            lastHard = now
            if (mode == Mode.PEEK) popOut()
            startFall()
            vy = -(2400f + Random.nextFloat() * 800f) * k
            vx = (Random.nextFloat() * 2f - 1f) * 1500f * k
            if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.thrown))
            return
        }
        if (jolt > 13f) {
            spikes.add(now)
            spikes.removeAll { now - it > 1f }
            if (spikes.size >= 4 && dizzyLeft < 1f) {
                spikes.clear()
                dizzyLeft = 3.5f
                glitchFor = 0.3f
                say(Lines.pick(Lines.dizzy))
            }
        }
    }

    // -- senses: the phone and the time ----------------------------------------------------------------------
    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { paused = true; sense(false) }
                Intent.ACTION_SCREEN_ON -> { paused = false; startLoop(); if (mode != Mode.CHIP) sense(true) }
                Intent.ACTION_USER_PRESENT -> handler.postDelayed({ greet() }, 700)
                Intent.ACTION_POWER_CONNECTED -> charged()
                Intent.ACTION_POWER_DISCONNECTED -> if (around() && Random.nextFloat() < 0.5f) say(Lines.pick(Lines.unplugged))
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0) battery = level * 100 / maxOf(1, scale)
                    plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (plugged || battery > 20) napLowDone = false
                    if (battery <= 15 && !plugged && !napLowDone && visible()) {
                        napLowDone = true
                        say(Lines.pick(Lines.napLow))
                        handler.postDelayed({ nap() }, 2600)
                    } else if (battery <= 20 && !plugged && !lowSaid && around()) {
                        lowSaid = true
                        say(Lines.pick(Lines.lowBattery))
                    }
                    if (plugged || battery > 20) lowSaid = false
                }
            }
        }
    }

    private fun visible() = mode == Mode.IDLE || mode == Mode.PERCH || mode == Mode.PEEK
    private fun around() = visible() || (mode == Mode.CHIP && mini && chipOn)  // somewhere she can talk from, mini included

    private fun charged() {
        if (!around()) return
        val c = sprites[her.clip]
        val cx = herLp.x + herLp.width / 2f
        val hy = herLp.y + herLp.height / 2f - c.h * 0.35f
        fx.splash(cx, hy, intArrayOf(0xFF78FAFF.toInt(), 0xFFFFE066.toInt(), 0xFFFFFFFF.toInt()), 3f * dp, 34)
        showFx()
        play(if (mode == Mode.PERCH) "perch_glitch" else "glitch")
        glitchFor = 0.8f
        her.flash = 0.8f
        say(Lines.pick(Lines.charger))
    }

    /** Unlocked: a greeting for the time of day (not more than every 15 minutes). */
    private fun greet() {
        if (now - lastGreet < 900f) return
        lastGreet = now
        if (mode == Mode.CHIP && !mini) { say(Lines.pick(Lines.sleepy)); return }
        if (!around()) return
        val h = hour()
        say(Lines.pick(when (h) {
            in 0..4 -> if (Random.nextBoolean()) Lines.lateNight else Lines.night
            in 5..11 -> Lines.morning
            in 12..17 -> Lines.afternoon
            else -> Lines.evening
        }))
    }

    // -- accessibility: apps, icons, taps ------------------------------------------------------------------------
    override fun onApp(pkg: String) {
        if (fsMini && pkg == homeApp) { fullscreen = false; onFullscreen(false) }
        handler.postDelayed({ react(pkg) }, 1200)
    }

    private fun react(pkg: String) {
        if (!around() || now - lastAppLine < 60f || now - (appLines[pkg] ?: -9999f) < 600f) return
        val lines = when (pkg) {
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme" -> Lines.tiktok
            "com.google.android.youtube", "app.revanced.android.youtube" -> Lines.youtube
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.soundcloud.android", "deezer.android.app" -> Lines.spotify
            "com.discord" -> Lines.discord
            "com.instagram.android" -> Lines.instagram
            else -> if (isGame(pkg)) Lines.game else null
        } ?: return
        lastAppLine = now
        appLines[pkg] = now
        say(Lines.pick(lines))
    }

    private fun isGame(pkg: String): Boolean = runCatching {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        @Suppress("DEPRECATION")
        (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) || ai.flags and ApplicationInfo.FLAG_IS_GAME != 0
    }.getOrDefault(false)

    override fun onLedges() {
        val p = perch ?: return
        if (mode != Mode.PERCH) return
        // still there (maybe scrolled a little)? follow it. Gone? She falls.
        val same = CapriAccess.ledges.firstOrNull {
            abs(it.top - p.top) < 40 * dp && footX in it.left.toFloat()..it.right.toFloat() && abs(it.width() - p.width()) < 8 * dp
        }
        if (same == null) startFall() else { perch = Rect(same); footY = same.top.toFloat() }
    }

    /** You tapped something: she looks at it, and sometimes zaps over and sits on it. */
    override fun onClicked(r: Rect) {
        if (!visible() || mode == Mode.PEEK) return
        her.flip = r.exactCenterX() < footX
        if (prefs.roam && now - lastClickZap > 25f && Random.nextFloat() < 0.25f && seatable(r)) {
            lastClickZap = now
            handler.postDelayed({ if (visible()) zapTo(Rect(r)) }, 600)
        }
    }

    private fun seatable(r: Rect) = r.width() >= 32 * dp && r.top > top + sprites.h * 0.6f && r.top < floor - 50 * dp &&
        r.left >= left && r.right <= right

    // -- big moves: roam, nap, wake ---------------------------------------------------------------------------
    /** She glitches, breaks into pixels, and they fly to [to], where [arrive] puts her back together. */
    private fun defrag(to: List<Pair<Float, Float>>, arrive: () -> Unit) {
        mode = Mode.AWAY
        play(if (her.clip.startsWith("perch")) "perch_glitch" else "glitch")
        glitchFor = 0.45f
        her.flash = 0f
        handler.postDelayed({
            val from = herPixels()
            her.visibility = View.INVISIBLE
            showFx()
            fx.defrag(from, to, 4f * dp, arrive)
        }, 380)
    }

    private fun roam() {
        if (mode == Mode.PEEK) return
        val seats = CapriAccess.ledges.filter(::seatable)
        if (seats.isNotEmpty() && Random.nextFloat() < 0.45f) { zapTo(seats[Random.nextInt(seats.size)]); return }
        val span = right - left - sprites.w * 0.6f
        var x: Float
        do { x = left + sprites.w * 0.3f + Random.nextFloat() * span } while (abs(x - footX) < span * 0.3f)
        val target = x
        her.flip = target < footX
        defrag(targets("idle", target, floor)) {
            footX = target
            footY = floor
            perch = null
            arrive()
        }
    }

    private fun zapTo(r: Rect) {
        val x = r.exactCenterX() + (Random.nextFloat() - 0.5f) * r.width() * 0.4f
        her.flip = x < footX
        defrag(targets("perch", x, r.top.toFloat())) {
            footX = x
            footY = r.top.toFloat()
            her.visibility = View.VISIBLE
            her.flash = 1f
            glitchFor = 0.2f
            sit(r)
        }
    }

    private fun arrive() {
        her.visibility = View.VISIBLE
        her.flash = 1f
        glitchFor = 0.2f
        her.rot = 0f
        mode = Mode.IDLE
        play("wake")
        nextAct = now + 3f
    }

    fun nap() {
        if (!visible() && mode != Mode.FALL) return
        if (mode == Mode.PEEK) {  // step out of the edge first, so the pixels leave from where she is
            val c = sprites["idle"]
            footX = if (peekLeft) left + c.w * 0.4f else right - c.w * 0.4f
            footY = peekY + c.h * 0.35f
            her.rot = 0f
        }
        fitChip()
        placeChip()
        val step = (5 * dp).toInt().coerceAtLeast(2)
        val art = chip.art
        val sx = chip.widthPx.toFloat() / art.width
        val inc = (step / sx).toInt().coerceAtLeast(1)
        val to = ArrayList<Pair<Float, Float>>()
        for (y in 0 until art.height step inc) for (x in 0 until art.width step inc) {
            if (Color.alpha(art.getPixel(x, y)) > 200) to.add(chipLp.x + x * sx to chipLp.y + chip.pad + y * sx)
        }
        defrag(to) {
            hideHer()
            showChip()
            perch = null
            mode = Mode.CHIP
            sense(false)
        }
    }

    fun wake() {
        if (mode != Mode.CHIP || mini) return
        mode = Mode.AWAY
        val art = chip.art
        val sx = chip.widthPx.toFloat() / art.width
        val inc = ((5 * dp) / sx).toInt().coerceAtLeast(1)
        val from = ArrayList<Triple<Float, Float, Int>>()
        for (y in 0 until art.height step inc) for (x in 0 until art.width step inc) {
            val c = art.getPixel(x, y)
            if (Color.alpha(c) > 200) from.add(Triple(chipLp.x + x * sx, chipLp.y + chip.pad + y * sx, c))
        }
        val target = (chipLp.x + chip.widthPx / 2f).coerceIn(left + sprites.w * 0.3f, right - sprites.w * 0.3f)
        hideChip()
        footX = target
        footY = floor
        her.rot = 0f
        her.clip = "idle"
        her.visibility = View.INVISIBLE
        showHer()
        showFx()
        sense(true)
        fx.defrag(from, targets("idle", target, floor), 4f * dp) {
            arrive()
            if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.woke))
        }
    }

    /** Her pixels right now, in screen coords (where a defrag starts), mirrored / turned like she's drawn. */
    private fun herPixels(): List<Triple<Float, Float, Int>> {
        val c = sprites[her.clip]
        val cx = herLp.x + herLp.width / 2f
        val cy = herLp.y + herLp.height / 2f - her.bob
        val a = Math.toRadians(her.rot.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        return c.pixels(her.frame.coerceIn(0, c.count - 1), (5 * dp).toInt().coerceAtLeast(2)).map { (x, y, col) ->
            var lx = x - c.w / 2f
            val ly = y - c.h / 2f
            if (her.flip) lx = -lx
            Triple(cx + lx * ca - ly * sa, cy + lx * sa + ly * ca, col)
        }
    }

    /** Where clip [name]'s pixels go if its contact point were at (x, y): the targets of a defrag. */
    private fun targets(name: String, x: Float, y: Float): List<Pair<Float, Float>> {
        val c = sprites[name]
        val ax = if (her.flip) 1f - c.ax else c.ax
        val ox = x - ax * c.w
        val oy = y - c.ay * c.h
        return c.pixels(0, (5 * dp).toInt().coerceAtLeast(2)).map { (px, py, _) ->
            (if (her.flip) ox + c.w - px else ox + px) to oy + py
        }
    }

    // -- talking -------------------------------------------------------------------------------------------------
    /**
     * Her bubble. [you]: your line above hers (when she's answering you). [hold]: stays up until replaced (listening,
     * thinking). [live]: the same reply grown longer, so the letters already typed stay typed.
     */
    private fun say(text: String, you: String = "", hold: Boolean = false, live: Boolean = false) {
        if (silent) return
        val asked = hold || you.isNotEmpty() || now - lastTouch < 3f  // answers to you show even with Talk off
        if (!prefs.talk && !asked) return
        if (chatOpen && you.isNotEmpty()) { chatLine = text; return }  // you're talking to her in the app
        talkOverChip = mode == Mode.CHIP
        talkText = text
        bubble.style = prefs.bubbleStyle
        val (w, h) = bubble.set(text, you, keep = live)
        bubbleLp.width = w
        bubbleLp.height = h
        if (!live) typeT = 0f
        talkUntil = if (hold) now + 90f else now + 2.4f + text.length * 0.045f + text.length / 38f
        placeBubble()
        if (!bubbleOn) { wm.addView(bubble, bubbleLp); bubbleOn = true } else wm.updateViewLayout(bubble, bubbleLp)
        bubble.invalidate()
    }

    private var lastBlip = 0f

    private fun hush() {
        if (bubbleOn) runCatching { wm.removeView(bubble) }
        bubbleOn = false
    }

    private fun talk(dt: Float) {
        if (!bubbleOn) return
        if (now > talkUntil || mode == Mode.HIDDEN || (talkOverChip != (mode == Mode.CHIP) && mode != Mode.AWAY)) { hush(); return }
        typeT += dt
        var typed = 0
        while (typeT > 1 / 38f && !bubble.done) {  // ~38 letters a second
            typeT -= 1 / 38f
            bubble.type()
            typed++
        }
        if (typed > 0 && prefs.blips && now - lastBlip > 0.14f) {  // a soft tick every few letters, not every letter
            lastBlip = now
            (blips ?: Blips().also { blips = it }).blip()
        }
        val bx = bubbleLp.x
        val by = bubbleLp.y
        placeBubble()
        if (bx != bubbleLp.x || by != bubbleLp.y) wm.updateViewLayout(bubble, bubbleLp)
    }

    private fun placeBubble() {
        val (cx, headTop) = if (mode == Mode.CHIP || talkOverChip) {
            (chipLp.x + chip.widthPx / 2f) to chipLp.y.toFloat() + chip.pad
        } else {
            val c = sprites[her.clip]
            val cy = herLp.y + herLp.height / 2f
            (herLp.x + herLp.width / 2f) to cy - (if (mode == Mode.PEEK) c.w else c.h) / 2f + c.h * 0.04f
        }
        bubbleLp.x = (cx - bubbleLp.width / 2f).toInt().coerceIn((left + 8 * dp).toInt(), (right - bubbleLp.width - 8 * dp).toInt())
        bubbleLp.y = (headTop - bubbleLp.height - 4 * dp).toInt().coerceAtLeast((top + 4 * dp).toInt())
    }

    // -- fullscreen: she steps away while you watch or play -------------------------------------------------------
    private fun addProbe() {
        if (Build.VERSION.SDK_INT < 30) return
        val v = View(this)
        v.setOnApplyWindowInsetsListener { _, insets ->
            imeProbe = insets.isVisible(WindowInsets.Type.ime())
            val full = !insets.isVisible(WindowInsets.Type.statusBars())
            if (full != fullscreen) { fullscreen = full; handler.post { onFullscreen(full) } }
            insets
        }
        runCatching { wm.addView(v, overlayParams(1, 1, touchable = false)) }.onSuccess { probe = v }
    }

    /** Fullscreen video or game: she shrinks to her eyes in the corner and comes back to full size after. */
    private fun onFullscreen(full: Boolean) {
        if (mode == Mode.INTRO || !ready) return
        measure()
        if (full) {
            fsMini = true
            hush()
            if (mode == Mode.CHIP) {
                if (!chipOn) { fitChip(); placeChip(); showChip() } else { placeChip(); wm.updateViewLayout(chip, chipLp) }
            } else if (mode != Mode.IDLE && mode != Mode.PERCH && mode != Mode.PEEK) {
                miniAt = 0f  // mid-move (flying, rebuilding): she shrinks as soon as she lands
            } else {
                hideHer()
                perch = null
                her.rot = 0f
                fitChip()
                placeChip()
                showChip()
                mode = Mode.CHIP
                sense(false)
            }
        } else {
            fsMini = false
            if (mode == Mode.HIDDEN) {
                footY = floor
                her.rot = 0f
                her.visibility = View.VISIBLE
                showHer()
                arrive()
            } else if (mode == Mode.CHIP) {
                if (!mini) wake() else if (!chipOn) { placeChip(); showChip() } else { placeChip(); wm.updateViewLayout(chip, chipLp) }
            }
        }
    }

    // -- windows ---------------------------------------------------------------------------------------------
    private fun placeHer() {
        val c = sprites[her.clip]
        val m = her.margin
        val sideways = mode == Mode.PEEK
        val w = (if (sideways) c.h else c.w) + 2 * m
        val h = (if (sideways) c.w else c.h) + 2 * m
        val cx: Float
        val cy: Float
        if (sideways) {
            val c0 = sprites["idle"]
            cx = if (peekLeft) left - c0.h / 2f + c0.h * 0.36f else right + c0.h / 2f - c0.h * 0.36f
            cy = peekY
        } else {
            val ax = if (her.flip) 1f - c.ax else c.ax
            cx = footX + (0.5f - ax) * c.w
            cy = footY + (0.5f - c.ay) * c.h
        }
        val x = (cx - w / 2f).toInt()
        val y = (cy - h / 2f).toInt()
        if (x != herLp.x || y != herLp.y || w != herLp.width || h != herLp.height) {
            herLp.x = x; herLp.y = y; herLp.width = w; herLp.height = h
            wm.updateViewLayout(her, herLp)
        }
    }

    private fun showHer() {
        if (herOn) return
        wm.addView(her, herLp)
        herOn = true
        placeHer()
    }

    private fun hideHer() {  // removed, not just hidden: an invisible window would still eat your taps
        if (herOn) wm.removeView(her)
        herOn = false
    }

    /** The chip window, sized for what she is now: mini or napping. */
    private fun fitChip() {
        chip.use(mini)
        chipLp.width = chip.widthPx
        chipLp.height = chip.heightPx + chip.pad
    }

    private fun placeChip() {
        val maxX = (right - chip.widthPx).toInt()
        val maxY = (floor - chip.heightPx - chip.pad).toInt()
        val saved = mini && prefs.miniX >= 0
        chipLp.x = (if (saved) prefs.miniX else (right - chip.widthPx - 12 * dp).toInt()).coerceIn(left.toInt(), maxX)
        chipLp.y = (if (saved) prefs.miniY else (floor - chip.heightPx - chip.pad - 4 * dp).toInt()).coerceIn(top.toInt(), maxY)
    }

    private fun showChip() {
        if (!chipOn) wm.addView(chip, chipLp)
        chipOn = true
    }

    private fun hideChip() {
        if (chipOn) wm.removeView(chip)
        chipOn = false
    }

    private fun showFx() {
        if (!fxOn) wm.addView(fx, fxLp)
        fxOn = true
    }

    private fun hideFx() {
        if (fxOn) wm.removeView(fx)
        fxOn = false
    }

    /** Screen size, the status bar line, and the floor she stands on: just above the navigation bar. */
    private fun measure() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val real = android.graphics.Point()
            @Suppress("DEPRECATION") (getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: wm.defaultDisplay).getRealSize(real)
            val m = wm.currentWindowMetrics
            val fresh = m.bounds.width() == real.x && m.bounds.height() == real.y
            val ins = if (fresh) m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.statusBars())
            else android.graphics.Insets.of(0, (24 * dp).toInt(), 0, (48 * dp).toInt())  // stale: safe margins
            screenW = real.x
            floor = (real.y - ins.bottom).toFloat()
            left = ins.left.toFloat()
            right = (screenW - ins.right).toFloat()
            top = ins.top.toFloat()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            @Suppress("DiscouragedApi", "InternalInsetResource")
            val nav = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            @Suppress("DiscouragedApi", "InternalInsetResource")
            val bar = resources.getIdentifier("status_bar_height", "dimen", "android")
            screenW = dm.widthPixels
            floor = (dm.heightPixels - if (nav > 0) resources.getDimensionPixelSize(nav) else 0).toFloat()
            left = 0f
            right = screenW.toFloat()
            top = if (bar > 0) resources.getDimensionPixelSize(bar).toFloat() else 0f
        }
    }

    // -- the notification that keeps her alive ------------------------------------------------------------------
    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capri on screen", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val nap = PendingIntent.getService(this, 1, Intent(this, CapriService::class.java).setAction(ACTION_NAP), flags)
        val chat = PendingIntent.getService(this, 3, Intent(this, CapriService::class.java).setAction(ACTION_TYPE), flags)
        val bye = PendingIntent.getService(this, 2, Intent(this, CapriService::class.java).setAction(ACTION_BYE), flags)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capri_stat)
            .setContentTitle("Capri is jacked in")
            .setContentText("Tap her to type. Hold her to talk.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Chat", chat)
            .addAction(0, "Nap / wake", nap)
            .addAction(0, "Jack out", bye)
            .build()
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val mic = Build.VERSION.SDK_INT >= 30 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, 7, n, types)
        } catch (e: Exception) {  // the mic type can only be added while the app is in front: without it then
            ServiceCompat.startForeground(this, 7, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }
    }
}
