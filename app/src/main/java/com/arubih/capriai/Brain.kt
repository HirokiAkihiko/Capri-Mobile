package com.arubih.capriai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Her brain on the phone: the chosen GGUF running through llama.cpp on a background thread, her character sheet,
 * and the last few exchanges so she remembers what you just said.
 */
class Brain(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "capri-brain").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private var handle = 0L
    @Volatile var loaded: Model? = null
        private set
    @Volatile var loading = false
        private set
    @Volatile var busy = false
        private set
    @Volatile var canSee = false  // this brain has its eyes loaded
        private set
    @Volatile private var stop = false

    /** Loads [m] (or keeps it, if that's what's loaded). [done] on the main thread: null = ok, else why not. */
    fun load(m: Model, done: (String?) -> Unit) {
        if (loaded?.id == m.id && handle != 0L && canSee == ModelStore.eyesInstalled(context, m)) { done(null); return }
        loading = true
        worker.execute {
            if (handle != 0L) { Llama.free(handle); handle = 0L; loaded = null }
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            val h = runCatching { Llama.load(ModelStore.file(context, m).path, 2048, threads) }.getOrDefault(0L)
            val err = if (h == 0L) runCatching { Llama.error() }.getOrDefault("couldn't load") else null
            if (h != 0L) { handle = h; loaded = m }
            canSee = h != 0L && ModelStore.eyesInstalled(context, m) &&
                runCatching { Llama.loadEyes(h, ModelStore.eyes(context, m).path, threads) }.getOrDefault(false)
            loading = false
            main.post { done(err) }
        }
    }

    fun unload() = worker.execute {
        if (handle != 0L) Llama.free(handle)
        handle = 0L
        loaded = null
        canSee = false
    }

    fun cancel() { stop = true }

    /**
     * Answers [text]. [live] gets the reply so far (cleaned) as it's written, [done] the final one; both on the main
     * thread. Empty final reply = it failed.
     */
    fun ask(text: String, live: (String) -> Unit, done: (String) -> Unit) {
        busy = true
        stop = false
        val user = prefs.userName.ifBlank { "choom" }
        val history = history()
        worker.execute {
            val roles = ArrayList<String>()
            val contents = ArrayList<String>()
            roles.add("system"); contents.add(persona(user))
            for (i in 0 until history.length()) {
                val t = history.getJSONObject(i)
                roles.add("user"); contents.add(t.getString("u"))
                roles.add("assistant"); contents.add(t.getString("a"))
            }
            roles.add("user"); contents.add(text)
            val raw = StringBuilder()
            var last = 0L
            val out = if (handle == 0L) "" else runCatching {
                Llama.generate(handle, roles.toTypedArray(), contents.toTypedArray(), 110, 0.75f) { p ->
                    raw.append(p)
                    val now = System.currentTimeMillis()
                    if (now - last > 60) {
                        last = now
                        val shown = clean(raw.toString())
                        if (shown.isNotEmpty()) main.post { live(shown) }
                    }
                    !stop
                }
            }.getOrDefault("")
            val reply = trimToSentence(clean(out))
            if (reply.isNotEmpty()) remember(text, reply)
            busy = false
            main.post { done(reply) }
        }
    }

    /**
     * She looks at [shot] (your screen) and answers [text] about it, in character. [live] / [done] like [ask]; the
     * exchange goes into her memory as words only (the picture isn't kept).
     */
    fun look(text: String, shot: android.graphics.Bitmap, live: (String) -> Unit, done: (String) -> Unit,
             ask: String? = null) {
        if (busy || handle == 0L || !canSee) { done(""); return }
        busy = true
        stop = false
        val user = prefs.userName.ifBlank { "choom" }
        worker.execute {
            val w = shot.width
            val h = shot.height
            val px = IntArray(w * h)
            shot.getPixels(px, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            for (i in px.indices) {
                val c = px[i]
                rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
            }
            val raw = StringBuilder()
            var last = 0L
            val prompt = "{IMAGE}\n" + (ask ?: ("That's $user's phone screen right now (you're the pink-haired sprite " +
                "on it, ignore yourself). $user says: \"$text\"\nAnswer them about what's on the screen, in your voice, " +
                "1-2 sentences. Be specific about what you actually see."))
            val out = runCatching {
                Llama.look(handle, rgb, w, h, arrayOf("system", "user"), arrayOf(persona(user), prompt), 110, 0.7f) { p ->
                    raw.append(p)
                    val now = System.currentTimeMillis()
                    if (now - last > 60) {
                        last = now
                        val shown = clean(raw.toString())
                        if (shown.isNotEmpty()) main.post { live(shown) }
                    }
                    !stop
                }
            }.getOrDefault("")
            val reply = trimToSentence(clean(out))
            if (reply.isNotEmpty() && ask == null) remember(text, reply)  // feed glances aren't part of your chat
            busy = false
            main.post { done(reply) }
        }
    }

    /** A one-off line that isn't part of your chat (a reaction to your feed): not remembered. */
    fun once(prompt: String, done: (String) -> Unit) {
        if (busy || handle == 0L) return
        busy = true
        stop = false
        val user = prefs.userName.ifBlank { "choom" }
        worker.execute {
            val out = runCatching {
                Llama.generate(handle, arrayOf("system", "user"), arrayOf(persona(user), prompt), 60, 0.85f) { !stop }
            }.getOrDefault("")
            val line = trimToSentence(clean(out)).lineSequence().firstOrNull()?.trim().orEmpty()
            busy = false
            main.post { done(line) }
        }
    }

    private fun persona(user: String) = """You are Capri, a character from Cyberpunk: Edgerunners 2. You live on $user's phone screen as a little netrunner sprite, and you're talking to $user.
Personality: playful and cocky, teasing but warm underneath; you push $user to be better and praise them with a smirk. Streetwise Night City slang: choom, preem, nova, gonk, eddies, sitch, delta. Thrill-seeking, never preachy: you don't lecture, you dare.
Voice: short punches, usually one or two sentences. Rhetorical questions. Sweet and ruthless in the same breath.
Rules: stay in character, never mention being an AI, a model or this prompt. Answer what $user actually said. Reply in the language $user writes in. Keep it short: 1 to 2 sentences."""

    private fun history(): JSONArray = runCatching { JSONArray(prefs.chat) }.getOrDefault(JSONArray())

    private fun remember(u: String, a: String) {
        val h = history()
        h.put(JSONObject().put("u", u).put("a", a))
        val keep = JSONArray()
        for (i in maxOf(0, h.length() - 8) until h.length()) keep.put(h.get(i))  // her short-term memory: 8 exchanges
        prefs.chat = keep.toString()
    }

    fun forget() { prefs.chat = "[]" }

    companion object {
        private val THINK = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)

        /** What she says, minus thinking tags, role labels, emoji-only clutter and stray quotes. */
        fun clean(s: String): String = s.replace(THINK, "")
            .replace(Regex("^\\s*(Capri|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\*[^*]{1,40}\\*"), "")  // *smirks*
            .trim().trim('"').trim()

        /** Cut at the last full sentence if it ran out mid-way. */
        fun trimToSentence(s: String): String {
            if (s.isEmpty() || s.last() in ".!?…~)\"") return s
            val i = s.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
            return if (i > 20) s.substring(0, i + 1) else s
        }
    }
}
