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
 * Her speech bubble: the call-style box from the desktop, small. Her face on the left, CAPRI, and the line typing
 * itself out. Tap it to close it early.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val dp: Float) : View(context) {
    private val face: Bitmap = context.assets.open("face.png").use { BitmapFactory.decodeStream(it) }
    private val pad = 10 * dp
    private val cam = 40 * dp
    val maxW = (250 * dp).toInt()
    private val textW = (maxW - pad * 3 - cam).toInt()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0110E16.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = 0x55FFFFFF
    }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1460.toInt() }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2EEF7.toInt(); textSize = 11 * dp; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2EEF7.toInt(); textSize = 13.5f * dp
    }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val youPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF968CA5.toInt(); textSize = 11 * dp }
    private var full = ""
    private var you = ""
    private var youH = 0f
    private var shown = 0
    private var layout: StaticLayout? = null
    var onTap: (() -> Unit)? = null
    var heightPx = 0
        private set

    /** Sets the line and measures the box; returns its (width, height). */
    fun set(text: String, you: String = "", keep: Boolean = false): Pair<Int, Int> {
        full = text
        if (!keep || shown > text.length) shown = 0
        this.you = if (you.length > 60) "“" + you.take(58) + "...”" else if (you.isNotEmpty()) "“$you”" else ""
        youH = if (this.you.isNotEmpty()) 15 * dp else 0f
        val l = StaticLayout.Builder.obtain(text, 0, text.length, body, textW).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f).build()
        layout = l
        heightPx = maxOf((pad * 2 + cam).toInt(), (pad * 2 + 16 * dp + youH + l.height).toInt())
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
        val w = width.toFloat()
        val h = height.toFloat()
        val c = 9 * dp  // chamfered corners, like the desktop box
        val p = Path().apply {
            moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
        }
        canvas.drawPath(p, panel)
        canvas.drawPath(p, edge)
        canvas.drawRect(0f, c, 2.5f * dp, h - c * 0.5f, pink)
        val cam = RectF(pad, pad, pad + this.cam, pad + this.cam)
        canvas.drawBitmap(face, null, cam, img)
        edge.color = 0xAAFF1460.toInt()
        canvas.drawRect(cam, edge)
        edge.color = 0x55FFFFFF
        val tx = pad * 2 + this.cam
        canvas.drawText("CAPRI", tx, pad + 10 * dp, name)
        if (you.isNotEmpty()) {  // your line, small and grey, like the desktop call box
            val fit = youPaint.breakText(you, true, textW.toFloat(), null)
            canvas.drawText(you, 0, fit, tx, pad + 16 * dp + 11 * dp, youPaint)
        }
        val l = layout ?: return
        canvas.save()
        canvas.translate(tx, pad + 16 * dp + youH)
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
