package com.arubih.capriai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** RPG-style talking blips, like the desktop: a short synth chirp per couple of letters, pitch wobbling a bit. */
class Blips {
    private val rate = 22050
    private val tracks = (0 until 5).map { i -> make(520f + i * 70f) }
    private var next = 0

    private fun make(freq: Float): AudioTrack? = runCatching {
        val n = (rate * 0.045f).toInt()
        val pcm = ShortArray(n) { k ->
            val t = k / rate.toFloat()
            val square = if (sin(2 * PI * freq * t) >= 0) 1f else -1f
            val env = exp(-t * 55f) * minOf(1f, k / 40f)
            (square * env * 0.16f * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
            .apply { write(pcm, 0, n) }
    }.getOrNull()

    fun blip() {
        val t = tracks[(next + Random.nextInt(2)) % tracks.size] ?: return
        next = (next + 1) % tracks.size
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() = tracks.forEach { it?.release() }
}

/**
 * Her speech bubble, Paper style: a white card with a hairline border and a soft shadow (so it reads over any app),
 * her face, "Capri" with a pink dot, your line in grey, and hers typing itself out. Tap it to close it early.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val dp: Float) : View(context) {
    private val face: Bitmap = context.assets.open("face.png").use { BitmapFactory.decodeStream(it) }
    private val sh = 6 * dp     // room around the card for its shadow
    private val pad = 11 * dp
    private val cam = 38 * dp
    val maxW = (256 * dp).toInt()
    private val textW = (maxW - 2 * sh - pad * 3 - cam).toInt()
    private val sans = runCatching { ResourcesCompat.getFont(context, R.font.manrope_regular) }.getOrNull() ?: Typeface.DEFAULT
    private val sansBold = runCatching { ResourcesCompat.getFont(context, R.font.manrope_extrabold) }.getOrNull()
        ?: Typeface.DEFAULT_BOLD
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(5 * dp, 0f, 1.5f * dp, 0x26000000)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0xFFE6E6E6.toInt()
    }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2558A.toInt() }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt(); textSize = 12 * dp; typeface = sansBold
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt(); textSize = 13.5f * dp; typeface = sans
    }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val youPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A8A8A.toInt(); textSize = 11 * dp; typeface = sans }
    private var full = ""
    private var you = ""
    private var youH = 0f
    private var shown = 0
    private var layout: StaticLayout? = null
    var onTap: (() -> Unit)? = null
    var heightPx = 0
        private set

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // the card's soft shadow
    }

    /** Sets the line and measures the box; returns its (width, height). */
    fun set(text: String, you: String = "", keep: Boolean = false): Pair<Int, Int> {
        full = text
        if (!keep || shown > text.length) shown = 0
        this.you = if (you.length > 60) "“" + you.take(58) + "...”" else if (you.isNotEmpty()) "“$you”" else ""
        youH = if (this.you.isNotEmpty()) 15 * dp else 0f
        val l = StaticLayout.Builder.obtain(text, 0, text.length, body, textW).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f).build()
        layout = l
        heightPx = maxOf((pad * 2 + cam).toInt(), (pad * 2 + 17 * dp + youH + l.height).toInt()) + (2 * sh).toInt()
        return maxW to heightPx
    }

    /** One more letter. Returns false once the whole line is out. */
    fun type(): Boolean {
        if (shown >= full.length) return false
        shown++
        invalidate()
        return true
    }

    val done get() = shown >= full.length

    override fun onDraw(canvas: Canvas) {
        val card = RectF(sh, sh, width - sh, height - sh)
        val r = 12 * dp
        canvas.drawRoundRect(card, r, r, panel)
        canvas.drawRoundRect(card, r, r, edge)
        val cam = RectF(sh + pad, sh + pad, sh + pad + this.cam, sh + pad + this.cam)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(cam, 8 * dp, 8 * dp, Path.Direction.CW) })
        canvas.drawBitmap(face, null, cam, img)
        canvas.restore()
        val tx = sh + pad * 2 + this.cam
        canvas.drawCircle(tx + 3 * dp, sh + pad + 6.5f * dp, 3 * dp, pink)
        canvas.drawText("Capri", tx + 10 * dp, sh + pad + 11 * dp, name)
        if (you.isNotEmpty()) {  // your line, small and grey
            val fit = youPaint.breakText(you, true, textW.toFloat(), null)
            canvas.drawText(you, 0, fit, tx, sh + pad + 17 * dp + 11 * dp, youPaint)
        }
        val l = layout ?: return
        canvas.save()
        canvas.translate(tx, sh + pad + 17 * dp + youH)
        canvas.clipRect(0f, 0f, textW.toFloat(), l.height.toFloat())
        // only the letters typed so far: draw the layout, then cover what isn't "out" yet line by line
        val upto = shown
        for (i in 0 until l.lineCount) {
            val s = l.getLineStart(i)
            val e = l.getLineEnd(i)
            if (s >= upto) break
            val part = full.substring(s, minOf(e, upto)).trimEnd('\n')
            canvas.drawText(part, 0f, l.getLineBaseline(i).toFloat(), body)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
        return true
    }
}
