package com.capri.mobile

import android.content.Context

/** What you can set. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("capri", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

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


    var feed: Boolean  // react on her own: she glances at your screen in any app now and then (needs a brain + accessibility)
        get() = sp.getBoolean("feed", true)
        set(v) = sp.edit().putBoolean("feed", v).apply()

    var remember: Boolean  // long-term memory: she writes down facts about you and the people in your life
        get() = sp.getBoolean("remember", true)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    var brain: String  // id of the model she thinks with (ModelStore)
        get() = sp.getString("brain", "") ?: ""
        set(v) = sp.edit().putString("brain", v).apply()

    var lmUrl: String  // LM Studio on your PC, like http://192.168.1.5:1234
        get() = sp.getString("lm_url", "") ?: ""
        set(v) = sp.edit().putString("lm_url", v).apply()

    var lmModel: String  // which of its models ("" = the one loaded)
        get() = sp.getString("lm_model", "") ?: ""
        set(v) = sp.edit().putString("lm_model", v).apply()

    var skin: String  // her look: a folder in assets/skins
        get() = sp.getString("skin", "capri") ?: "capri"
        set(v) = sp.edit().putString("skin", v).apply()

    var watcherSeen: Boolean  // the secret skin shows as ??? until you've picked it once
        get() = sp.getBoolean("watcher_seen", false)
        set(v) = sp.edit().putBoolean("watcher_seen", v).apply()

    var nvKey: String  // your NVIDIA API key: its own file, which stays out of phone backups
        get() = secrets.getString("nv_key", "") ?: ""
        set(v) = secrets.edit().putString("nv_key", v).apply()

    var moods: Boolean  // she goes mini on her own now and then, and comes back
        get() = sp.getBoolean("moods", true)
        set(v) = sp.edit().putBoolean("moods", v).apply()

    var cloudGlance: Boolean  // with NVIDIA: she may send your screen by herself. Off until you say so
        get() = sp.getBoolean("cloud_glance", false)
        set(v) = sp.edit().putBoolean("cloud_glance", v).apply()

    var updateTag: String  // the newest release GitHub told us about
        get() = sp.getString("update_tag", "") ?: ""
        set(v) = sp.edit().putString("update_tag", v).apply()

    var updateTold: String  // the release we already sent a notification for
        get() = sp.getString("update_told", "") ?: ""
        set(v) = sp.edit().putString("update_told", v).apply()

    var updateSkipped: String  // the release you said Later to
        get() = sp.getString("update_skipped", "") ?: ""
        set(v) = sp.edit().putString("update_skipped", v).apply()

    var updateChecked: Long  // when we last asked GitHub
        get() = sp.getLong("update_checked", 0L)
        set(v) = sp.edit().putLong("update_checked", v).apply()

    var mini: Boolean  // always mini: she stays in her small form, just her eyes
        get() = sp.getBoolean("mini", false)
        set(v) = sp.edit().putBoolean("mini", v).apply()

    var miniX: Int  // where you left her mini form (-1: the default corner)
        get() = sp.getInt("mini_x", -1)
        set(v) = sp.edit().putInt("mini_x", v).apply()

    var miniY: Int
        get() = sp.getInt("mini_y", -1)
        set(v) = sp.edit().putInt("mini_y", v).apply()

    var dark: Boolean  // the app's dark theme
        get() = sp.getBoolean("dark", false)
        set(v) = sp.edit().putBoolean("dark", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var chat: String  // her last few exchanges, JSON
        get() = sp.getString("chat", "[]") ?: "[]"
        set(v) = sp.edit().putString("chat", v).apply()

    var lang: String  // her language: "" = not picked yet (the first-launch picker shows), "auto" = the phone's, or a code (Lang)
        get() = sp.getString("lang", "") ?: ""
        set(v) = sp.edit().putString("lang", v).apply()

    var bubbleStyle: Int  // her chat bubble: 0 = classic (dark call box), 1 = light card, 2 = dark card
        get() = sp.getInt("bubble_style", 1)
        set(v) = sp.edit().putInt("bubble_style", v).apply()
}
