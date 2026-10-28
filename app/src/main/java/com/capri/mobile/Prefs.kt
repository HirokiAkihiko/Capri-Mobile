package com.capri.mobile

import android.content.Context

/** What you can set, plus your gift stash. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("capri", Context.MODE_PRIVATE)

    var sizeDp: Int
        get() = sp.getInt("size_dp", 150)
        set(v) = sp.edit().putInt("size_dp", v).apply()

    var roam: Boolean
        get() = sp.getBoolean("roam", true)
        set(v) = sp.edit().putBoolean("roam", v).apply()

    var blips: Boolean
        get() = sp.getBoolean("blips", true)
        set(v) = sp.edit().putBoolean("blips", v).apply()

    var talk: Boolean  // greetings and reactions in her bubble
        get() = sp.getBoolean("talk", true)
        set(v) = sp.edit().putBoolean("talk", v).apply()

    var tilt: Boolean  // she slides when you tilt the phone
        get() = sp.getBoolean("tilt", true)
        set(v) = sp.edit().putBoolean("tilt", v).apply()

    var shake: Boolean  // shaking makes her dizzy / throws her
        get() = sp.getBoolean("shake", true)
        set(v) = sp.edit().putBoolean("shake", v).apply()

    var game: Boolean  // gifts and achievements
        get() = sp.getBoolean("game", true)
        set(v) = sp.edit().putBoolean("game", v).apply()

    var feed: Boolean  // scroll with me: reacts to TikTok / Reels / Shorts sometimes (needs a brain + accessibility)
        get() = sp.getBoolean("feed", true)
        set(v) = sp.edit().putBoolean("feed", v).apply()

    var remember: Boolean  // long-term memory: she writes down facts about you and the people in your life
        get() = sp.getBoolean("remember", true)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    var brain: String  // id of the model she thinks with (ModelStore)
        get() = sp.getString("brain", "") ?: ""
        set(v) = sp.edit().putString("brain", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var chat: String  // her last few exchanges, JSON
        get() = sp.getString("chat", "[]") ?: "[]"
        set(v) = sp.edit().putString("chat", v).apply()

    var bubbleTheme: Int  // her chat bubble: 0 = follow the phone, 1 = light, 2 = dark
        get() = sp.getInt("bubble_theme", 0)
        set(v) = sp.edit().putInt("bubble_theme", v).apply()

    fun count(g: Gift): Int = sp.getInt("gift_" + g.name, 0)

    fun collect(g: Gift) = sp.edit().putInt("gift_" + g.name, count(g) + 1).apply()
}
