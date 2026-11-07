package com.capri.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * Her entrance, same as the desktop: a flipbook (assets/intro) that fills the whole screen, then, as her pieces pull
 * together in the last frames, zips down onto the spot she lands on and gets sucked into her as she rebuilds. The
 * frames crossfade into each other on every tick. Each is stored cropped to what's in it, with its place in index.txt.
 */
@SuppressLint("ViewConstructor")
class IntroView(context: Context) : View(context) {
    private class Frame(val art: Bitmap, val box: Rect)

    private var frames: List<Frame> = emptyList()
    private var fw = 1f
    private var fh = 1f
    @Volatile var ready = false
        private set
    var landed = false
        private set
    var done = false
        private set
    private var t = 0f
    private var kFull = 1f
    private var kEnd = 1f
    private var fullX = 0f   // where the end point sits while it fills the screen...
    private var fullY = 0f
    private var herX = 0f    // ...and where it ends up: the middle of her body
    private var herY = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        Thread({ load() }, "capri-intro").start()
    }

    private fun load() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val lines = context.assets.open("intro/index.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            val size = lines[0].split(" ")
            fw = size[0].toFloat()
            fh = size[1].toFloat()
            frames = lines.drop(1).mapIndexed { i, line ->
                pool.submit(Callable {
                    val b = line.split(" ").map { it.toInt() }
                    Frame(context.assets.open("intro/$i.webp").use { BitmapFactory.decodeStream(it) }, Rect(b[0], b[1], b[2], b[3]))
                })
            }.map { it.get() }
        } catch (e: Exception) {  // she still arrives without it: the service falls back to dropping in
            frames = emptyList()
        } finally {
            pool.shutdown()
            ready = true
        }
    }

    val usable get() = frames.isNotEmpty()

    /** Aims it: fills a [w] x [h] screen with its end point as close to her as it can be, then lands on (cx, cy). */
    fun aim(w: Float, h: Float, cx: Float, cy: Float, herHeight: Float) {
        kFull = maxOf(w / fw, h / fh)
        kEnd = 0.8f * herHeight / (END_H * fh)
        val sw = fw * kFull
        val sh = fh * kFull
        fullX = (cx - END_X * sw).coerceIn(w - sw, 0f) + END_X * sw
        fullY = (cy - END_Y * sh).coerceIn(h - sh, 0f) + END_Y * sh
        herX = cx
        herY = cy
    }

    private fun startOf(i: Int) = minOf(i, SHRINK_FROM) * FRAME + maxOf(0, i - SHRINK_FROM) * FAST
    private val length get() = startOf(frames.size)

    fun step(dt: Float) {
        if (done) return
        t += minOf(dt, 0.05f)  // a hitch pauses it instead of skipping frames
        if (t >= length) landed = true
        if (t >= length + FADE_OUT) done = true
        invalidate()
    }

    /** Frees the frames (a screenful each): only ever played once. */
    fun release() {
        for (f in frames) f.art.recycle()
        frames = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        if (frames.isEmpty() || done) return
        var i = frames.lastIndex
        while (i > 0 && t < startOf(i)) i--
        val mix = if (i < frames.lastIndex) {
            smooth(((t - startOf(i)) / (startOf(i + 1) - startOf(i)) - (1 - BLEND)) / BLEND)
        } else 0f
        val s0 = startOf(SHRINK_FROM)
        val z = if (t < length) smooth((t - s0) / (length - s0)) else 1f  // 0 fullscreen .. 1 her size
        var k = kFull * (kEnd / kFull).pow(z)
        var alpha = smooth(t / FADE_IN)
        if (t > length) {  // landed: it keeps shrinking into her body and fades
            val u = smooth((t - length) / FADE_OUT)
            k *= 1 - 0.55f * u
            alpha *= 1 - u
        }
        val ex = fullX + (herX - fullX) * z
        val ey = fullY + (herY - fullY) * z
        val x0 = ex - END_X * fw * k
        val y0 = ey - END_Y * fh * k
        draw(canvas, frames[i], x0, y0, k, alpha * (1 - mix))
        if (mix > 0f) draw(canvas, frames[i + 1], x0, y0, k, alpha * mix)
    }

    private fun draw(canvas: Canvas, f: Frame, x0: Float, y0: Float, k: Float, a: Float) {
        paint.alpha = (255 * a).toInt().coerceIn(0, 255)
        dst.set(x0 + f.box.left * k, y0 + f.box.top * k, x0 + f.box.right * k, y0 + f.box.bottom * k)
        canvas.drawBitmap(f.art, null, dst, paint)
    }

    private fun smooth(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private companion object {
        const val FRAME = 0.033f     // seconds per frame while it fills the screen
        const val FAST = 0.025f      // seconds per frame once it shrinks onto her
        const val SHRINK_FROM = 24   // from this frame on it shrinks from fullscreen down to her size
        const val BLEND = 0.4f       // the last this-much of each frame crossfades into the next
        const val FADE_IN = 0.12f
        const val FADE_OUT = 0.28f
        const val END_X = 530f / 640 // where the animation ends, as a fraction of the frame: her body goes here
        const val END_Y = 370f / 480
        const val END_H = 170f / 480 // how tall the end shape is; it ends up about her height
    }
}
