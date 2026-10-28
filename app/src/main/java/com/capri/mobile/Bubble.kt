package com.capri.mobile

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

/** Soft talking ticks while her bubble types: a short, quiet sine "tik", a few per line, pitch varying a little. */
class Blips {
    private val rate = 22050
    private val tracks = (0 until 4).map { i -> make(380f + i * 30f) }
    private var next = 0

    private fun make(freq: Float): AudioTrack? = runCatching {
        val n = (rate * 0.03f).toInt()
        val pcm = ShortArray(n) { k ->
            val t = k / rate.toFloat()
            val env = exp(-t * 90f) * minOf(1f, k / 60f)  // quick fade in, quick fade out: no click
            (sin(2 * PI * freq * t).toFloat() * env * 0.06f * Short.MAX_VALUE).toInt().toShort()
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
 * Her speech bubble. Classic (the default): the dark call box from the desktop, chamfered corners, pink edge, CAPRI.
 * Light / Dark: a Paper card with a soft shadow. Her face, your line in grey, and hers typing itself out.
 * Tap it to close it early.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val dp: Float) : View(context) {
    private val face: Bitmap = context.assets.open("face.png").use { BitmapFactory.decodeStream(it) }
    private var sh = 0f         // room around the card for its shadow (Paper only)
    private var pad = 10 * dp
    private var cam = 40 * dp
    val maxW = (256 * dp).toInt()
    private var textW = 0
    private val sans = runCatching { ResourcesCompat.getFont(context, R.font.manrope_regular) }.getOrNull() ?: Typeface.DEFAULT
    private val sansBold = runCatching { ResourcesCompat.getFont(context, R.font.manrope_extrabold) }.getOrNull()
        ?: Typeface.DEFAULT_BOLD
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val name = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.5f * dp }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val youPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * dp }
    private var full = ""
    private var you = ""
    private var youH = 0f
    private var shown = 0
    private var layout: StaticLayout? = null
    var onTap: (() -> Unit)? = null
    var heightPx = 0
        private set

    /** 0 = Classic (dark call box), 1 = Light card, 2 = Dark card. */
    var style = -1
        set(v) {
            if (field == v) return
            field = v
            restyle()
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // the Paper card's soft shadow
        style = 0
    }

    private val classic get() = style == 0

    private fun restyle() {
        val dark = style == 2
        if (classic) {
            sh = 0f; pad = 10 * dp; cam = 40 * dp
            panel.color = 0xF0110E16.toInt(); panel.clearShadowLayer()
            edge.strokeWidth = 1.2f * dp; edge.color = 0x55FFFFFF
            pink.color = 0xFFFF1460.toInt()
            name.color = 0xFFF2EEF7.toInt(); name.textSize = 11 * dp
            name.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); name.letterSpacing = 0.15f
            body.color = 0xFFF2EEF7.toInt(); body.typeface = Typeface.DEFAULT
            youPaint.color = 0xFF968CA5.toInt(); youPaint.typeface = Typeface.DEFAULT
        } else {
            sh = 6 * dp; pad = 11 * dp; cam = 38 * dp
            panel.color = if (dark) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
            panel.setShadowLayer(5 * dp, 0f, 1.5f * dp, if (dark) 0x55000000 else 0x26000000)
            edge.strokeWidth = 1f * dp; edge.color = if (dark) 0xFF34323A.toInt() else 0xFFE6E6E6.toInt()
            pink.color = if (dark) 0xFFF2A3BF.toInt() else 0xFFE2558A.toInt()
            name.color = if (dark) 0xFFF2EFF4.toInt() else 0xFF111111.toInt(); name.textSize = 12 * dp
            name.typeface = sansBold; name.letterSpacing = 0f
            body.color = if (dark) 0xFFEDEAF0.toInt() else 0xFF111111.toInt(); body.typeface = sans
            youPaint.color = if (dark) 0xFF9A96A0.toInt() else 0xFF8A8A8A.toInt(); youPaint.typeface = sans
        }
        textW = (maxW - 2 * sh - pad * 3 - cam).toInt()
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
        heightPx = maxOf((pad * 2 + cam).toInt(), (pad * 2 + head + youH + l.height).toInt()) + (2 * sh).toInt()
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

    private val head get() = if (classic) 16 * dp else 17 * dp  // the name line above her text

    override fun onDraw(canvas: Canvas) {
        if (classic) {
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
            canvas.drawText("CAPRI", pad * 2 + this.cam, pad + 10 * dp, name)
        } else {
            val card = RectF(sh, sh, width - sh, height - sh)
            val r = 12 * dp
            canvas.drawRoundRect(card, r, r, panel)
            canvas.drawRoundRect(card, r, r, edge)
            val cam = RectF(sh + pad, sh + pad, sh + pad + this.cam, sh + pad + this.cam)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(cam, 8 * dp, 8 * dp, Path.Direction.CW) })
            canvas.drawBitmap(face, null, cam, img)
            canvas.restore()
            val nx = sh + pad * 2 + this.cam
            canvas.drawCircle(nx + 3 * dp, sh + pad + 6.5f * dp, 3 * dp, pink)
            canvas.drawText("Capri", nx + 10 * dp, sh + pad + 11 * dp, name)
        }
        val tx = sh + pad * 2 + this.cam
        if (you.isNotEmpty()) {  // your line, small and grey
            val fit = youPaint.breakText(you, true, textW.toFloat(), null)
            canvas.drawText(you, 0, fit, tx, sh + pad + head + 11 * dp, youPaint)
        }
        val l = layout ?: return
        canvas.save()
        canvas.translate(tx, sh + pad + head + youH)
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
