package com.capri.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * Her languages, the same eight as the desktop app. You pick one the first time you open the app (or later in Setup);
 * Auto follows the phone's language when it's one of the eight, else English.
 *
 * The app's words are written in English in the code, wrapped in [tr]; each language is a plain JSON file in
 * assets/lang (English text -> translation), so anyone can fix or add a translation without touching code. A line with
 * no translation shows in English. Her replies come from her brain: [rule] tells it which language to answer in.
 */
object Lang {
    val all = linkedMapOf(
        "en" to "English", "es" to "Español", "pt" to "Português", "fr" to "Français",
        "ru" to "Русский", "it" to "Italiano", "de" to "Deutsch", "pl" to "Polski",
    )
    /** The names her brain understands, for "reply only in ...". */
    private val english = mapOf("en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French",
        "ru" to "Russian", "it" to "Italian", "de" to "German", "pl" to "Polish")

    /** The language in use right now (never "auto"). Compose state: the app redraws in the new language at once. */
    var code by mutableStateOf("en")
        private set
    @Volatile private var table: Map<String, String> = emptyMap()
    private var loadedFrom = ""

    /** The phone's language when she speaks it, else English. */
    fun system(): String = Locale.getDefault().language.lowercase().let { if (it in all) it else "en" }

    /** "" (not picked yet) or "auto" -> the phone's; a code -> that one. */
    fun resolve(pick: String) = if (pick in all) pick else system()

    /** Loads the picked language (once per change). Safe to call from anywhere, often. */
    fun init(c: Context) {
        val want = resolve(Prefs(c).lang)
        if (want == loadedFrom) return
        table = load(c, want)
        loadedFrom = want
        code = want
    }

    /** You picked a language ("auto" or a code). */
    fun set(c: Context, pick: String) {
        Prefs(c).lang = pick
        init(c)
    }

    private fun load(c: Context, lang: String): Map<String, String> {
        if (lang == "en") return emptyMap()
        return runCatching {
            val o = JSONObject(c.assets.open("lang/$lang.json").bufferedReader().use { it.readText() })
            buildMap { for (k in o.keys()) put(k, o.getString(k)) }
        }.getOrDefault(emptyMap())
    }

    /** The text in the current language. {0}, {1}... are filled with [args] (also in English). */
    fun tr(s: String, vararg args: Any?): String {
        @Suppress("UNUSED_VARIABLE") val now = code  // read the state, so the screen redraws when the language changes
        var out = table[s]?.takeIf { it.isNotBlank() } ?: s
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
        return out
    }

    /** Her brain's instruction: answer only in the picked language. Auto: whatever language they write in. */
    fun rule(c: Context): String {
        val pick = Prefs(c).lang
        return if (pick in all) "Reply only in ${english[pick]}, every word of it, whatever language earlier messages were " +
            "in; never mix in another language."
        else "Reply in the language they write in (if unsure, ${english[system()]})."
    }

    /** The language to use when she speaks first (reactions, memories): picked, else the phone's. */
    fun speakFirst(c: Context) = "Say it in ${english[resolve(Prefs(c).lang)]}."

    /** For the phone's speech recognizer: the picked language, or null to keep the phone's own. */
    fun speech(c: Context): String? = Prefs(c).lang.takeIf { it in all }?.let { Locale(it).toLanguageTag() }
}

/** Short for [Lang.tr]. */
fun tr(s: String, vararg args: Any?) = Lang.tr(s, *args)
