package com.capri.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Layout params for one of her overlay windows: absolute screen coordinates, never takes keyboard focus. */
@SuppressLint("RtlHardcoded")
fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
    @Suppress("DEPRECATION")
    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (!touchable) alpha = 0.8f  // Android 12+ only lets touches through overlays at or under 80% opacity
    }
}

/**
 * Full-screen, touch-through layer for her pixel effects: the defrag (she breaks into her own pixels and they fly
 * somewhere else to rebuild her) and the debris splash when she lands. Only on screen while something is flying.
 */
class FxView(context: Context) : View(context) {
    private class Bit(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float, val cx: Float, val cy: Float,
        val color: Int, val size: Float, val delay: Float, val dur: Float,
    )

    private class Debris(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val size: Float,
                         var life: Float)

    private val bits = ArrayList<Bit>()
    private val debris = ArrayList<Debris>()
    private val paint = Paint()
    private var t = 0f
    private var onArrive: (() -> Unit)? = null
    var gravity = 2600f

    val busy get() = bits.isNotEmpty() || debris.isNotEmpty()

    /** Pixels fly from [from] to [to] (screen coords, matched up at random) on curved paths; [arrive] when they land. */
    fun defrag(from: List<Triple<Float, Float, Int>>, to: List<Pair<Float, Float>>, size: Float, arrive: () -> Unit) {
        bits.clear()
        t = 0f
        if (from.isEmpty() || to.isEmpty()) { arrive(); return }
        val r = Random(from.size)
        for ((x, y, c) in from) {
            val (tx, ty) = to[r.nextInt(to.size)]
            val lift = (80f + r.nextFloat() * 260f) * if (r.nextBoolean()) 1 else -1
            bits.add(Bit(x, y, tx, ty, (x + tx) / 2 + r.nextFloat() * 120f - 60f, minOf(y, ty) - kotlin.math.abs(lift),
                c, size * (0.7f + r.nextFloat() * 0.6f), r.nextFloat() * 0.25f, 0.55f + r.nextFloat() * 0.35f))
        }
        onArrive = arrive
        invalidate()
    }

    /** A puff of her pixels where she lands (or gets thrown hard). */
    fun splash(x: Float, y: Float, colors: IntArray, size: Float, n: Int = 26) {
        val r = Random.Default
        repeat(n) {
            val a = PI + r.nextDouble() * PI  // upward half
            val v = 300f + r.nextFloat() * 700f
            debris.add(Debris(x + r.nextFloat() * 40f - 20f, y - 4f, (kotlin.math.cos(a) * v).toFloat(),
                (sin(a) * v).toFloat(), colors[r.nextInt(colors.size)], size * (0.6f + r.nextFloat()),
                0.6f + r.nextFloat() * 0.5f))
        }
        invalidate()
    }

    /** Advance [dt] seconds. Returns true while anything is still flying. */
    fun step(dt: Float): Boolean {
        t += dt
        if (bits.isNotEmpty() && bits.all { t > it.delay + it.dur }) {
            bits.clear()
            onArrive?.invoke().also { onArrive = null }
        }
        val it = debris.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.vy += gravity * dt
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.life -= dt
            if (d.life <= 0f) it.remove()
        }
        invalidate()
        return busy
    }

    override fun onDraw(canvas: Canvas) {
        for (b in bits) {
            val k = ((t - b.delay) / b.dur).coerceIn(0f, 1f)
            if (k <= 0f) {
                paint.color = b.color
                canvas.drawRect(b.x0, b.y0, b.x0 + b.size, b.y0 + b.size, paint)
                continue
            }
            val e = 1f - (1f - k) * (1f - k) * (1f - k)  // ease out
            val u = 1f - e  // quadratic bezier through the lift point
            val x = u * u * b.x0 + 2 * u * e * b.cx + e * e * b.x1
            val y = u * u * b.y0 + 2 * u * e * b.cy + e * e * b.y1
            paint.color = if (k < 0.5f && (k * 20).toInt() % 3 == 0) 0xFF78FAFF.toInt() else b.color  // cyan flicker
            val trail = (1f - k) * 3f
            canvas.drawRect(x - trail, y, x + b.size + trail, y + b.size, paint)
        }
        for (d in debris) {
            paint.color = d.color
            paint.alpha = (255 * (d.life / 1.1f).coerceIn(0f, 1f)).toInt()
            canvas.drawRect(d.x, d.y, d.x + d.size, d.y + d.size, paint)
        }
        paint.alpha = 255
    }
}
