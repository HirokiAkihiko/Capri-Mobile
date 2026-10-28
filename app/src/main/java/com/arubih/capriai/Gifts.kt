package com.arubih.capriai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Little things she drops for you now and then. Tap one to keep it; the app counts what you've collected. */
enum class Gift(val label: String, val rarity: String, val weight: Int, val color: Int) {
    EDDIES("Eddies", "common", 40, 0xFFFFD23C.toInt()),
    SHARD("Data shard", "common", 28, 0xFF78FAFF.toInt()),
    STICKER("Neon cat sticker", "rare", 14, 0xFFFF1460.toInt()),
    OPTIC("Kiroshi optic", "rare", 10, 0xFF2BFF9A.toInt()),
    SANDY("Sandevistan chip", "legendary", 6, 0xFFB46CFF.toInt()),
    RELIC("Relic fragment", "legendary", 2, 0xFFFFFFFF.toInt());

    companion object {
        fun roll(): Gift {
            var r = Random.nextInt(entries.sumOf { it.weight })
            for (g in entries) { r -= g.weight; if (r < 0) return g }
            return EDDIES
        }
    }
}

/** Draws [g] in a [s] x [s] square at (0, 0): pixel-ish icons with a glow, same in the overlay and the app. */
fun drawGift(c: Canvas, g: Gift, s: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = RadialGradient(s / 2, s / 2, s / 2, g.color and 0x66FFFFFF, 0, Shader.TileMode.CLAMP)
    c.drawCircle(s / 2, s / 2, s / 2, p)
    p.shader = null
    p.color = g.color
    val u = s / 16
    when (g) {
        Gift.EDDIES -> {  // a coin with an E
            c.drawCircle(s / 2, s / 2, 5 * u, p)
            p.color = 0xFF2A1A00.toInt()
            c.drawRect(6.5f * u, 5.5f * u, 9.5f * u, 6.5f * u, p)
            c.drawRect(6.5f * u, 7.5f * u, 9f * u, 8.5f * u, p)
            c.drawRect(6.5f * u, 9.5f * u, 9.5f * u, 10.5f * u, p)
            c.drawRect(6.5f * u, 5.5f * u, 7.5f * u, 10.5f * u, p)
        }
        Gift.SHARD -> {  // a crystal shard
            c.drawPath(Path().apply {
                moveTo(8 * u, 2.5f * u); lineTo(11.5f * u, 7 * u); lineTo(8 * u, 13.5f * u); lineTo(4.5f * u, 7 * u); close()
            }, p)
            p.color = 0x88FFFFFF.toInt()
            c.drawPath(Path().apply { moveTo(8 * u, 2.5f * u); lineTo(9.5f * u, 7 * u); lineTo(8 * u, 13.5f * u); close() }, p)
        }
        Gift.STICKER -> {  // a cat head
            c.drawPath(Path().apply {
                moveTo(4 * u, 4 * u); lineTo(6.5f * u, 6.5f * u); lineTo(9.5f * u, 6.5f * u); lineTo(12 * u, 4 * u)
                lineTo(12 * u, 10 * u); lineTo(10 * u, 12.5f * u); lineTo(6 * u, 12.5f * u); lineTo(4 * u, 10 * u); close()
            }, p)
            p.color = 0xFF0A0514.toInt()
            c.drawRect(6 * u, 8.5f * u, 7 * u, 9.5f * u, p)
            c.drawRect(9 * u, 8.5f * u, 10 * u, 9.5f * u, p)
        }
        Gift.OPTIC -> {  // an eye ring
            c.drawCircle(s / 2, s / 2, 5 * u, p)
            p.color = 0xFF0A0514.toInt()
            c.drawCircle(s / 2, s / 2, 3.4f * u, p)
            p.color = 0xFF78FAFF.toInt()
            c.drawCircle(s / 2, s / 2, 1.8f * u, p)
        }
        Gift.SANDY -> {  // a chip with pins
            c.drawRect(5 * u, 5 * u, 11 * u, 11 * u, p)
            for (i in 0 until 3) {
                val o = (6 + i * 2) * u
                c.drawRect(o, 3.5f * u, o + u, 5 * u, p); c.drawRect(o, 11 * u, o + u, 12.5f * u, p)
                c.drawRect(3.5f * u, o, 5 * u, o + u, p); c.drawRect(11 * u, o, 12.5f * u, o + u, p)
            }
            p.color = 0xFF0A0514.toInt()
            c.drawRect(6.5f * u, 6.5f * u, 9.5f * u, 9.5f * u, p)
        }
        Gift.RELIC -> {  // a star
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 5.5f * u else 2.3f * u
                val a = -PI / 2 + i * PI / 5
                val x = (s / 2 + r * cos(a)).toFloat()
                val y = (s / 2 + r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, p)
        }
    }
}

fun giftBitmap(g: Gift, px: Int): Bitmap =
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { drawGift(Canvas(it), g, px.toFloat()) }

/** A dropped gift on screen, bobbing a little. */
@SuppressLint("ViewConstructor")
class GiftView(context: Context, val gift: Gift, val sizePx: Int) : View(context) {
    var t = 0f
    var onTap: (() -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, (sin(t * 3f) * sizePx * 0.06f))
        drawGift(canvas, gift, sizePx.toFloat())
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
        return true
    }
}
