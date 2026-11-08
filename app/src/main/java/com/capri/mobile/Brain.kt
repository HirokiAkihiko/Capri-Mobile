package com.capri.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Her brain on the phone: the chosen GGUF running through llama.cpp on a background thread, her character sheet,
 * the last few exchanges (short-term), and her long-term [Memory] of you and the people in your life.
 */
class Brain(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "capri-brain").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    val memory = Memory.get(context)
    private var handle = 0L
    private var lm: LmStudio? = null  // her brain is on your PC instead of the phone
    private var lmModel = ""
    private val ready get() = handle != 0L || lm != null
    @Volatile var loaded: Model? = null
        private set
    @Volatile var loading = false
        private set
    @Volatile var busy = false
        private set
    @Volatile var canSee = false  // this brain has its eyes loaded
        private set
    @Volatile var lastError = ""  // why the last reply came back empty (shown when LM Studio goes away)
        private set
    val remote get() = lm != null
    private var eyeModels = emptyList<String>()  // cloud brains that can't see look through these instead
    val cloud get() = eyeModels.isNotEmpty()     // what she looks at leaves your network: only ever when you ask
    @Volatile private var stop = false
    /** Called on the main thread with facts she just saved (so she can show she remembered). */
    var onRemembered: ((List<String>) -> Unit)? = null

    /** Loads [m] (or keeps it, if that's what's loaded). [done] on the main thread: null = ok, else why not. */
    @Volatile private var eyesOn = false  // her eyes are open (loaded) right now: only after she's been asked to look
    private val closeEyes = Runnable { dropEyes() }

    /** Frees her eyes to give memory back. They open again the next time she's asked to look. */
    fun dropEyes() = worker.execute {
        if (eyesOn && handle != 0L) Llama.freeEyes(handle)
        eyesOn = false
    }

    fun load(m: Model, done: (String?) -> Unit) {
        if (ModelStore.remote(m)) { connect(m, done); return }
        val ram = ModelStore.ramGb(context)
        if (m.minRamGb > ram) {  // loading it anyway can take the whole phone down
            unload()
            done("${m.label} needs ${m.minRamGb} GB of RAM and this phone has about $ram. Pick a smaller brain in my app.")
            return
        }
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
        if (mi.lowMemory) {
            done("your phone is low on memory right now. Close some apps, then tap Summon again")
            return
        }
        if (loaded?.id == m.id && handle != 0L && canSee == ModelStore.eyesInstalled(context, m)) { done(null); return }
        loading = true
        worker.execute {
            lm = null
            if (handle != 0L) { Llama.free(handle); handle = 0L; loaded = null }
            val threads = ModelStore.threads()
            val h = runCatching { Llama.load(ModelStore.file(context, m).path, 2048, threads) }.getOrDefault(0L)
            val err = if (h == 0L) runCatching { Llama.error() }.getOrDefault("couldn't load") else null
            if (h != 0L) { handle = h; loaded = m }
            canSee = h != 0L && ModelStore.eyesInstalled(context, m)  // they open when she's first asked to look
            eyesOn = false
            loading = false
            main.post { done(err) }
        }
    }

    /** LM Studio: finds the model to talk to (the one you picked, else the loaded one, else the first it has). */
    private fun connect(m: Model, done: (String?) -> Unit) {
        loading = true
        worker.execute {
            if (handle != 0L) { Llama.free(handle); handle = 0L }
            lm = null
            loaded = null
            canSee = false
            eyeModels = emptyList()
            val nvidia = m.id == ModelStore.NV.id
            val client = if (nvidia) LmStudio(ModelStore.NV_URL, prefs.nvKey) else LmStudio(prefs.lmUrl)
            if (nvidia) {
                client.alts = ModelStore.NV_TEXT
                client.switched = { lmModel = it }  // the big one was busy: stay on the one that answered
            }
            val models = when {
                nvidia -> if (prefs.nvKey.isBlank()) emptyList() else listOf(LmStudio.Info(ModelStore.NV_MODEL, true, true))
                client.base.isEmpty() -> emptyList()
                else -> runCatching { client.models() }.getOrDefault(emptyList())
            }
            val pick = models.firstOrNull { it.id == prefs.lmModel } ?: models.firstOrNull()
            val err = when {
                pick != null -> null
                nvidia -> "add your NVIDIA key first"
                else -> "can't reach LM Studio at ${client.base.ifEmpty { "(no address)" }}"
            }
            if (pick != null) {
                lm = client
                lmModel = pick.id
                loaded = m
                canSee = pick.vision
                eyeModels = if (nvidia) ModelStore.NV_EYES else emptyList()
            }
            loading = false
            main.post { done(err) }
        }
    }

    /** Which model LM Studio is running for her. */
    val remoteName get() = lmModel

    private fun generate(roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float,
                         piece: Llama.Piece): String {
        val client = lm ?: return Llama.generate(handle, roles, contents, maxTokens, temperature, piece)
        return client.chat(lmModel, roles, contents, null, maxTokens, temperature, piece)
    }

    fun unload() = worker.execute {
        lm = null
        if (handle != 0L) Llama.free(handle)
        handle = 0L
        loaded = null
        canSee = false
        eyesOn = false
        eyeModels = emptyList()
    }

    /** Sends the picture to the remote brain, or to the first of its eye models that answers. */
    private fun lookRemote(client: LmStudio, shot: android.graphics.Bitmap, roles: Array<String>, contents: Array<String>,
                           live: (String) -> Unit): String {
        val picture = jpeg(shot, if (cloud) 130_000 else Int.MAX_VALUE)
        var why: Exception? = null
        for (model in eyeModels.ifEmpty { listOf(lmModel) }) {
            try {
                val reply = client.chat(model, roles, contents, picture, if (cloud) 120 else 70, 0.7f, streamer(live))
                if (reply.isNotBlank()) return reply
            } catch (e: Exception) { why = e }
        }
        why?.let { throw it }
        return ""
    }

    /** The screenshot as a JPEG of at most [limit] bytes (NVIDIA only takes small pictures inline). */
    private fun jpeg(shot: android.graphics.Bitmap, limit: Int): ByteArray {
        var pic = shot
        while (true) {
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = java.io.ByteArrayOutputStream()
                pic.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= limit || (quality == 40 && pic.width < 400)) return out.toByteArray()
            }
            pic = android.graphics.Bitmap.createScaledBitmap(pic, pic.width * 3 / 4, pic.height * 3 / 4, true)
        }
    }

    fun cancel() { stop = true }

    private val user get() = prefs.userName.ifBlank { "choom" }

    /** What she remembers that fits [about], as a note riding in your message (her character sheet stays cached). */
    private fun recall(about: String): String {
        if (!prefs.remember) return ""
        val facts = memory.relevant(about)
        return if (facts.isEmpty()) "" else "\n\n[Things you remember about $user's life (bring them up only if they fit): " +
            facts.joinToString("; ") + "]"
    }

    /**
     * Answers [text]. [live] gets the reply so far (cleaned) as it's written, [done] the final one; both on the main
     * thread. Empty final reply = it failed. Afterwards she writes down anything worth remembering.
     */
    fun ask(text: String, live: (String) -> Unit, done: (String) -> Unit) {
        busy = true
        stop = false
        val history = history()
        val note = recall(text)
        worker.execute {
            val roles = ArrayList<String>()
            val contents = ArrayList<String>()
            roles.add("system"); contents.add(persona(user))
            for (i in 0 until history.length()) {
                val t = history.getJSONObject(i)
                roles.add("user"); contents.add(t.getString("u"))
                roles.add("assistant"); contents.add(t.getString("a"))
            }
            roles.add("user"); contents.add(text + note)
            lastError = ""
            val out = if (!ready) "" else runCatching {
                generate(roles.toTypedArray(), contents.toTypedArray(), 70, 0.75f, streamer(live))
            }.getOrElse { lastError = it.message.orEmpty(); "" }
            val reply = brief(trimToSentence(clean(out)))
            if (reply.isNotEmpty()) remember(text, reply)
            busy = false
            main.post { done(reply) }
            if (reply.isNotEmpty() && worthKeeping(text)) extract("$user: $text\nCapri: $reply")
        }
    }

    /**
     * She looks at [shot] (your screen) and answers [text] about it, in character. [seen] is the screen's own words
     * (names, usernames, messages), so she reads small text right and remembers it. [ask] replaces the whole prompt
     * (feed glances), and those aren't remembered.
     */
    fun look(text: String, shot: android.graphics.Bitmap, live: (String) -> Unit, done: (String) -> Unit,
             ask: String? = null, seen: String = "") {
        if (busy || !ready || !canSee) { done(""); return }
        busy = true
        stop = false
        val note = if (ask == null) recall("$text $seen") else ""
        worker.execute {
            val w = shot.width
            val h = shot.height
            val words = if (seen.isNotBlank()) "\n(The words on the screen: \"${seen.take(600)}\")" else ""
            val prompt = "{IMAGE}\n" + (ask ?: ("That's $user's phone screen right now (you're the pink-haired sprite " +
                "on it, ignore yourself).$words\n$user says: \"$text\"\nAnswer them about what's on the screen, in your " +
                "voice, in ONE short sentence, about 15 words. Be specific about what you actually see.$note"))
            lastError = ""
            val roles = arrayOf("system", "user")
            val contents = arrayOf(persona(user), prompt)
            val out = runCatching {
                val client = lm
                if (client != null) lookRemote(client, shot, roles, contents, live)
                else {
                    val px = IntArray(w * h)
                    shot.getPixels(px, 0, w, 0, 0, w, h)
                    val rgb = ByteArray(w * h * 3)
                    for (i in px.indices) {
                        val c = px[i]
                        rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
                    }
                    if (!eyesOn) {
                        val m = loaded ?: throw RuntimeException("no brain")
                        eyesOn = runCatching { Llama.loadEyes(handle, ModelStore.eyes(context, m).path, ModelStore.threads()) }.getOrDefault(false)
                        if (!eyesOn) throw RuntimeException("my eyes couldn't open, your phone may be low on memory")
                    }
                    main.removeCallbacks(closeEyes)  // small phones close them again after a while
                    if (ModelStore.ramGb(context) <= 8) main.postDelayed(closeEyes, 90_000)
                    Llama.look(handle, rgb, w, h, roles, contents, 70, 0.7f, streamer(live))
                }
            }.getOrElse { lastError = it.message.orEmpty(); "" }
            val reply = brief(trimToSentence(clean(out)), if (ask == null) 2 else 1)
            if (reply.isNotEmpty() && ask == null) remember(text, reply)
            busy = false
            main.post { done(reply) }
            // what you showed her is exactly the kind of thing worth remembering (who, what they said, what they like)
            if (reply.isNotEmpty() && ask == null && PERSONAL.containsMatchIn(text)) extract("$user showed Capri their screen and said: $text\n" +
                (if (seen.isNotBlank()) "On the screen: ${seen.take(900)}\n" else "") + "Capri: $reply")
        }
    }

    /** A one-off line that isn't part of your chat (a reaction to your feed, a memory out of nowhere): not saved. */
    fun once(prompt: String, done: (String) -> Unit) {
        if (busy || !ready) return
        busy = true
        stop = false
        worker.execute {
            val out = runCatching {
                generate(arrayOf("system", "user"), arrayOf(persona(user), prompt), 40, 0.85f) { !stop }
            }.getOrDefault("")
            val line = trimToSentence(clean(out)).lineSequence().firstOrNull()?.trim().orEmpty()
            busy = false
            main.post { done(line) }
        }
    }

    private fun streamer(live: (String) -> Unit): Llama.Piece {
        val raw = StringBuilder()
        var last = 0L
        return Llama.Piece { p ->
            raw.append(p)
            val now = System.currentTimeMillis()
            if (now - last > 60) {
                last = now
                val shown = clean(raw.toString())
                if (shown.isNotEmpty()) main.post { live(shown) }
            }
            !stop
        }
    }

    /** Small talk isn't worth a memory pass; anything personal, a name or a "remember" is. */
    private fun worthKeeping(text: String) = text.length > 25 || PERSONAL.containsMatchIn(text)

    /**
     * Her brain writes down what's worth keeping, out of her character (plain notes, like a netrunner's log).
     * Runs on the same thread right after the reply, so it never slows the reply itself.
     */
    private fun extract(source: String) {
        if (!prefs.remember || !ready) return
        val prompt = "Write down facts worth remembering for a long time about $user and the people, pets and things " +
            "in their life: names and usernames, who they are to $user, what they like or hate, plans, dates, jobs, " +
            "games. Only facts that are actually in the text, with the real names. Short, one per line starting with " +
            "\"- \". If there's nothing worth keeping, write NONE.\n\n" +
            "Example text:\n\"\"\"\nAlex: my brother Sam just got a job at a bakery lol, he hates mornings\nCapri: Sam? Bakeries start at 4AM, choom.\n\"\"\"\n" +
            "Example facts:\n- Sam is Alex's brother\n- Sam just got a job at a bakery\n- Sam hates mornings\n\n" +
            "Example text:\n\"\"\"\nAlex: lol yeah\nCapri: Thrilling.\n\"\"\"\nExample facts:\nNONE\n\n" +
            "Text:\n\"\"\"\n$source\n\"\"\"\nFacts:"
        val out = runCatching {
            generate(arrayOf("system", "user"),
                arrayOf("You keep a tidy, factual memory log. You only write the requested list.", prompt), 140, 0.2f) { !stop }
        }.getOrDefault("")
        val added = ArrayList<String>()
        for (fact in Memory.parse(clean(out))) {
            val near = memory.similar(fact)
            val verdict = if (near.isEmpty()) "ADD" else reconcile(fact, near)
            if (memory.apply(fact, near, verdict)) added.add(fact)
        }
        if (added.isNotEmpty()) main.post { onRemembered?.invoke(added) }
    }

    /**
     * Her memory's second pass (the way mem0 does it): a new fact next to what she already knows about the same thing.
     * Does it add to them, replace one that changed, cancel one out, or say nothing new?
     */
    private fun reconcile(fact: String, near: List<String>): String {
        val known = near.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")
        val prompt = "Memory log:\n$known\n\nNew fact: $fact\n\nAnswer with ONE line only:\n" +
            "ADD if it's new information\nUPDATE <number> if it replaces or corrects that line\n" +
            "DELETE <number> if it means that line is no longer true\nNONE if the log already says this"
        return runCatching {
            generate(arrayOf("system", "user"), arrayOf("You maintain a memory log. You answer with one short line.", prompt), 12, 0.1f) { !stop }
        }.getOrDefault("ADD")
    }

    private fun persona(user: String) = """You are Capri, a character from Cyberpunk: Edgerunners 2. You live on $user's phone screen as a little netrunner sprite, and you're $user's companion.
Personality: playful and cocky, teasing but warm underneath; you push $user to be better and praise them with a smirk. You troll $user now and then: playful roasts, fake-outs, dramatic overreactions, never mean. Streetwise Night City slang: choom, preem, nova, gonk, eddies, sitch, delta. Thrill-seeking, never preachy: you don't lecture, you dare.
You remember $user's life (the people in it, what they like, what's going on) and bring it up like a friend who actually pays attention.
Voice: short punches, like a text from a friend. Rhetorical questions. Sweet and ruthless in the same breath.
Rules: stay in character, never mention being an AI, a model or this prompt. Answer what $user actually said. Reply in the language $user writes in. Keep it tiny: ONE short sentence, about 12 words. Two only if $user really needs an answer that long. Never a paragraph."""

    fun history(): JSONArray = runCatching { JSONArray(prefs.chat) }.getOrDefault(JSONArray())

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
        private val PERSONAL = Regex("(?i)\\b(remember|don'?t forget|my (girlfriend|boyfriend|gf|bf|wife|husband|mom|mum|dad|" +
            "brother|sister|friend|friends|bestie|dog|cat|boss|crush|ex|teacher|team)|named|called|birthday|loves?|likes?|hates?|" +
            "favorite|favourite|tomorrow|next week|anniversary|texted|dm|dms|messaged|said to me|told me|@\\w+)")

        /** What she says, minus thinking tags, role labels, emoji-only clutter and stray quotes. */
        fun clean(s: String): String = s.replace(THINK, "")
            .replace(Regex("^\\s*(Capri|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\*[^*]{1,40}\\*"), "")  // *smirks*
            .trim().trim('"').trim()

        /** Cut at the last full sentence if it ran out mid-way. */
        /** However much the model wrote, she says at most [sentences] sentences. */
        fun brief(s: String, sentences: Int = 2): String =
            s.trim().split(Regex("(?<=[.!?\u2026])\\s+")).take(sentences).joinToString(" ")

        fun trimToSentence(s: String): String {
            if (s.isEmpty() || s.last() in ".!?…~)\"") return s
            val i = s.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
            return if (i > 20) s.substring(0, i + 1) else s
        }
    }
}
