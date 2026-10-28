package com.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect

/**
 * Capri's sprite strips (assets/sprites), scaled once to her size on screen. Standing clips are 240 x 384 frames,
 * the sitting ones (perch) 384 x 384. Art by Lachy (github.com/lachydotmcg), PolyForm Noncommercial: assets/LICENSE.md.
 */
class Clip(val strip: Bitmap, val count: Int, val fps: Float, val seated: Boolean) {
    val w = strip.width / count
    val h = strip.height
    var ax = 0.5f  // the point that touches what she stands / sits on, as fractions of the frame
    var ay = 1f

    fun src(i: Int, out: Rect): Rect = out.apply { set(i * w, 0, (i + 1) * w, h) }

    /** Her opaque pixels in frame [i] on a [step]-px grid: (x, y, colour) relative to the frame's top-left. */
    fun pixels(i: Int, step: Int): List<Triple<Float, Float, Int>> {
        val out = ArrayList<Triple<Float, Float, Int>>()
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                val c = strip.getPixel(i * w + x, y)
                if (Color.alpha(c) > 200) out.add(Triple(x.toFloat(), y.toFloat(), c or 0xFF000000.toInt()))
                x += step
            }
            y += step
        }
        return out
    }
}

class Sprites(context: Context, heightPx: Int) {
    private class Spec(val frames: Int, val fps: Float, val seated: Boolean = false)

    private val specs = mapOf(  // same timing as the desktop app
        "idle" to Spec(16, 8f), "blink" to Spec(4, 8f), "blink_glitch" to Spec(4, 8f), "grin" to Spec(16, 8f),
        "glitch" to Spec(10, 15f), "dangle" to Spec(24, 10f), "sleep" to Spec(16, 6f), "wake" to Spec(13, 15f),
        "perch" to Spec(16, 8f, true), "perch_blink" to Spec(4, 8f, true), "perch_glitch" to Spec(10, 15f, true),
    )
    val clips: Map<String, Clip>
    val h = heightPx                    // standing height (her size x 1.11, like the desktop)
    val w = heightPx * 240 / 384
    private val seat = (heightPx / 1.11f).toInt()

    init {
        clips = specs.mapValues { (name, s) ->
            val fw = if (s.seated) seat else w
            val fh = if (s.seated) seat else h
            val raw = context.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
            val scaled = Bitmap.createScaledBitmap(raw, fw * s.frames, fh, true)
            if (scaled !== raw) raw.recycle()
            Clip(scaled, s.frames, s.fps, s.seated)
        }
        // standing: where her feet touch the ground in an idle frame; sitting: her hip (same numbers as the desktop)
        val idle = clips.getValue("idle")
        var bottom = idle.h - 1
        search@ while (bottom > 0) {
            for (x in 0 until idle.w step 2) if (Color.alpha(idle.strip.getPixel(x, bottom)) > 128) break@search
            bottom--
        }
        var sum = 0f
        var n = 0
        for (y in maxOf(0, bottom - 12)..bottom) for (x in 0 until idle.w) {
            if (Color.alpha(idle.strip.getPixel(x, y)) > 128) { sum += x; n++ }
        }
        for (c in clips.values) {
            if (c.seated) { c.ax = 0.30f; c.ay = 0.79f } else { c.ax = if (n > 0) sum / n / idle.w else 0.5f; c.ay = (bottom + 1f) / idle.h }
        }
    }

    operator fun get(name: String): Clip = clips.getValue(name)

    /** Colours she's made of, for the pixel debris. */
    val palette: IntArray by lazy {
        get("idle").pixels(0, 9).map { it.third }.shuffled().take(64).ifEmpty { listOf(0xFFFF1460.toInt()) }.toIntArray()
    }
}
