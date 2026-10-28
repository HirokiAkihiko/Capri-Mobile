package com.arubih.capriai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Capri herself: one frame of one clip, drawn around the middle of her window so she can be mirrored (facing left)
 * or turned on her side (peeking from an edge). Extras: a glitchy chroma split, a white flash when she (re)forms,
 * dizzy stars.
 */
@SuppressLint("ViewConstructor")
class CapriView(context: Context, val sprites: Sprites, val margin: Int) : View(context) {
    var clip = "idle"
    var frame = 0
    var split = 0f   // px of red / cyan offset (glitching)
    var flash = 0f   // 0..1 white silhouette over her
    var bob = 0f     // px she floats up by
    var flip = false // facing left
    var rot = 0f     // degrees, clockwise
    var dizzy = 0f   // seconds of stars left
    var t = 0f
    var onTouch: ((MotionEvent) -> Boolean)? = null

    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val red = tinted(0xB0FF1460.toInt())
    private val cyan = tinted(0xB078FAFF.toInt())
    private val white = tinted(0xFFFFFFFF.toInt())
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE066.toInt() }

    private fun tinted(c: Int) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
    }

    override fun onDraw(canvas: Canvas) {
        val c = sprites[clip]
        c.src(frame.coerceIn(0, c.count - 1), src)
        canvas.save()
        canvas.translate(width / 2f, height / 2f - bob)
        canvas.rotate(rot)
        if (flip) canvas.scale(-1f, 1f)
        dst.set(-c.w / 2f, -c.h / 2f, c.w / 2f, c.h / 2f)
        if (split > 0.5f) {
            dst.offset(-split, 0f)
            canvas.drawBitmap(c.strip, src, dst, cyan)
            dst.offset(2 * split, 0f)
            canvas.drawBitmap(c.strip, src, dst, red)
            dst.offset(-split, 0f)
        }
        canvas.drawBitmap(c.strip, src, dst, paint)
        if (flash > 0.01f) {
            white.alpha = (255 * flash).toInt()
            canvas.drawBitmap(c.strip, src, dst, white)
        }
        if (dizzy > 0f) {  // stars circling her head
            val hy = -c.h / 2f + c.h * 0.08f
            for (i in 0 until 3) {
                val a = t * 5f + i * 2.094f
                val x = cos(a) * c.w * 0.32f
                val y = hy + sin(a) * c.h * 0.03f
                star.alpha = (255 * minOf(1f, dizzy)).toInt()
                drawStar(canvas, x, y, c.w * 0.07f)
            }
        }
        canvas.restore()
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, r: Float) {
        val p = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.42f
            val a = -PI / 2 + i * PI / 5
            val px = (x + rr * cos(a)).toFloat()
            val py = (y + rr * sin(a)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        canvas.drawPath(p, star)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = onTouch?.invoke(event) ?: false
}

/** Her nap: the sleeping picture tucked in a corner, z's drifting up. Tap it and she wakes. */
@SuppressLint("ViewConstructor")
class ChipView(context: Context, val widthPx: Int) : View(context) {
    val art: Bitmap = context.assets.open("nap.png").use { BitmapFactory.decodeStream(it) }
    val heightPx = widthPx * art.height / art.width
    val pad = widthPx / 6  // room above for the z's
    var t = 0f
    var onTouch: ((MotionEvent) -> Boolean)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val z = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = 0xFF78FAFF.toInt()
    }
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        dst.set(0f, pad.toFloat(), widthPx.toFloat(), (pad + heightPx).toFloat())
        canvas.drawBitmap(art, null, dst, paint)
        for (i in 0 until 3) {  // three z's, each rising and fading on a loop
            val k = ((t * 0.45f) + i / 3f) % 1f
            z.textSize = widthPx * (0.07f + 0.07f * k)
            z.alpha = (255 * sin(PI * k) * 0.9).toInt()
            canvas.drawText("z", widthPx * (0.78f + 0.08f * k) + sin(k * 6f) * 4f, pad + heightPx * 0.25f - pad * 1.1f * k, z)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = onTouch?.invoke(event) ?: false
}
