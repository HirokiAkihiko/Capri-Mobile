package com.capri.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar

/**
 * Badges you earn by hanging out with her. Counters live in the same prefs as everything else; an unlock shows up
 * in her bubble the moment it happens.
 */
enum class Badge(val title: String, val how: String, val counter: String = "", val goal: Int = 1, val tier: Int = 0,
                 val glyph: String) {
    JACKED_IN("Jacked In", "Summon her for the first time", glyph = ">_"),
    BOOP("Boop", "Poke her", "pokes", 1, glyph = "o"),
    RUDE("Rude", "Poke her until she storms off", "mad", 1, glyph = "!"),
    YEET("Yeet", "Throw her", "throws", 1, glyph = "^"),
    FLYER("Frequent Flyer", "Throw her 25 times", "throws", 25, 1, "^^"),
    DIZZY("Room's Spinning", "Shake her dizzy", "dizzy", 1, glyph = "@"),
    PEEK("Peekaboo", "Fling her into the edge of the screen", "peeks", 1, glyph = "|"),
    PERCH("Front Row Seat", "She sits on one of your app icons", "perches", 1, glyph = "_"),
    JUICED("Juiced", "Plug in your charger while she's out", "charges", 1, glyph = "+"),
    NIGHT_OWL("Night Owl", "Hang out with her after midnight", "night", 1, glyph = "*"),
    BIG_BRAIN("Big Brain", "Give her a brain", "brains", 1, 1, "#"),
    FIRST_WORDS("First Words", "Talk to her", "talks", 1, glyph = "\""),
    CHATTERBOX("Chatterbox", "Talk to her 50 times", "talks", 50, 2, "\"\""),
    SCROLL_BUDDY("Doomscroll Buddy", "She reacts to your feed", "feed", 1, 1, "~"),
    MEMORY_LANE("Memory Lane", "She remembers 10 things about your life", "memories", 10, 1, "&"),
    FIRST_GIFT("Finders Keepers", "Collect a gift", "gifts", 1, glyph = "$"),
    HOARDER("Hoarder", "Collect 25 gifts", "gifts", 25, 1, "$$"),
    LEGEND("Legendary", "Collect a legendary gift", "legend", 1, 2, "%"),
    LOYAL("Loyal Choom", "Hang out with her on 7 different days", "days", 7, 2, "7"),
}

object Achievements {
    private fun sp(c: Context) = c.getSharedPreferences("capri", Context.MODE_PRIVATE)

    fun count(c: Context, key: String) = sp(c).getInt("n_$key", 0)

    fun unlocked(c: Context, b: Badge) = sp(c).getBoolean("badge_" + b.name, false)

    fun progress(c: Context, b: Badge): Float = if (unlocked(c, b)) 1f
    else if (b.counter.isEmpty()) 0f else (count(c, b.counter).toFloat() / b.goal).coerceIn(0f, 1f)

    /** Counts one more [key] event; returns the badges that just unlocked. */
    fun bump(c: Context, key: String, by: Int = 1): List<Badge> {
        val n = count(c, key) + by
        sp(c).edit().putInt("n_$key", n).apply()
        return Badge.entries.filter { it.counter == key && n >= it.goal && !unlocked(c, it) }.onEach { give(c, it) }
    }

    /** A badge with no counter (or one you hit directly). Returns it if it's new. */
    fun unlock(c: Context, b: Badge): Badge? = if (unlocked(c, b)) null else b.also { give(c, it) }

    /** Today counts as a day with her (for Loyal Choom). */
    fun dayVisit(c: Context): List<Badge> {
        val cal = Calendar.getInstance()
        val today = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        if (sp(c).getString("last_day", "") == today) return emptyList()
        sp(c).edit().putString("last_day", today).apply()
        return bump(c, "days")
    }

    private fun give(c: Context, b: Badge) = sp(c).edit().putBoolean("badge_" + b.name, true).apply()

    /** Badge colours by tier, for the unlock sparkle: soft, tinted, pink. */
    val tierColors = intArrayOf(0xFF111111.toInt(), 0xFF8A6CFF.toInt(), 0xFFE2558A.toInt())

    /**
     * A badge, Paper style: a thin circle with its glyph. Earned: ink (legendary: pink) with a pink dot; locked: a pale
     * outline with a "?". Drawn at (0, 0) in an [s] square.
     */
    fun draw(canvas: Canvas, b: Badge, s: Float, earned: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = if (!earned) 0xFFD6D6D6.toInt() else if (b.tier == 2) 0xFFE2558A.toInt() else 0xFF111111.toInt()
        p.color = if (earned) 0xFFFFFFFF.toInt() else 0xFFF7F7F7.toInt()
        canvas.drawCircle(s / 2, s / 2, s * 0.44f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * (if (earned) 0.035f else 0.025f)
        p.color = ink
        canvas.drawCircle(s / 2, s / 2, s * 0.44f, p)
        if (earned && b.tier >= 1) {  // rarer badges get a second, inner ring
            p.strokeWidth = s * 0.015f
            canvas.drawCircle(s / 2, s / 2, s * 0.37f, p)
        }
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        p.textSize = s * if (b.glyph.length > 1) 0.26f else 0.32f
        p.textAlign = Paint.Align.CENTER
        p.color = if (earned) ink else 0xFFC4C4C4.toInt()
        canvas.drawText(if (earned) b.glyph else "?", s / 2, s / 2 + p.textSize * 0.36f, p)
        if (earned) {
            p.color = 0xFFE2558A.toInt()
            canvas.drawCircle(s * 0.80f, s * 0.20f, s * 0.07f, p)
        }
    }
}
