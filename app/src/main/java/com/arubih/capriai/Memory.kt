package com.arubih.capriai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Her long-term memory: short facts about you and the people in your life ("Mia (@mia.exe) is your girlfriend",
 * "Mia loves iced coffee"). Her brain writes them after you talk or show her something; the right ones come back
 * when they're relevant. One JSON file in the app's private storage, never sent anywhere; you can read, delete or
 * wipe it from the app.
 */
class Memory private constructor(context: Context) {
    data class Fact(val text: String, val t: Long, var used: Long = 0)

    private val file = File(context.filesDir, "memory.json")
    private val facts = ArrayList<Fact>()

    init { load() }

    @Synchronized
    fun all(): List<Fact> = facts.sortedByDescending { it.t }

    @Synchronized
    fun size() = facts.size

    /** Adds new facts, skipping ones she already knows (or nearly the same words). Returns what was new. */
    @Synchronized
    fun add(new: List<String>): List<String> {
        val added = ArrayList<String>()
        for (raw in new) {
            val f = raw.trim().trimStart('-', '*', '•', ' ').trim().trimEnd('.').trim()
            if (f.length < 8 || f.length > 160) continue
            val w = words(f)
            val dupe = facts.indexOfFirst { overlap(w, words(it.text)) > 0.7f }
            if (dupe >= 0) {  // the newer wording wins (it may correct the old one)
                facts[dupe] = Fact(f, System.currentTimeMillis(), facts[dupe].used)
                continue
            }
            facts.add(Fact(f, System.currentTimeMillis()))
            added.add(f)
        }
        while (facts.size > 300) facts.remove(facts.minByOrNull { it.used.coerceAtLeast(it.t) })  // oldest, least used
        if (new.isNotEmpty()) save()
        return added
    }

    /** The facts that matter for [about]: shared words and names count most, recent ones break ties. */
    @Synchronized
    fun relevant(about: String, limit: Int = 6): List<String> {
        if (facts.isEmpty()) return emptyList()
        val q = words(about)
        val now = System.currentTimeMillis()
        val scored = facts.map { f ->
            val w = words(f.text)
            val shared = q.intersect(w)
            val names = shared.count { n -> f.text.contains(Regex("\\b${Regex.escape(n)}\\b", RegexOption.IGNORE_CASE)) &&
                f.text.split(" ").any { it.trim(',', '.', '(', ')', '\'').equals(n, true) && it.first().isUpperCase() } }
            val age = (now - f.t) / 86_400_000f
            f to (shared.size * 2f + names * 3f + 1f / (1f + age))
        }.sortedByDescending { it.second }
        val pick = scored.filter { it.second >= 2f }.take(limit).map { it.first }
            .ifEmpty { scored.take(2).map { it.first } }  // nothing matches: her two freshest memories still ride along
        pick.forEach { it.used = now }
        return pick.map { it.text }
    }

    /** A random memory she might bring up out of nowhere (favours ones she hasn't mentioned in a while). */
    @Synchronized
    fun callback(): String? = facts.filter { System.currentTimeMillis() - it.used > 3_600_000 }.randomOrNull()?.also {
        it.used = System.currentTimeMillis()
    }?.text

    @Synchronized
    fun forget(text: String) {
        facts.removeAll { it.text == text }
        save()
    }

    @Synchronized
    fun forgetAll() {
        facts.clear()
        save()
    }

    private fun load() {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = o.getString("f")
                if (!JUNK.containsMatchIn(f)) facts.add(Fact(f, o.getLong("t"), o.optLong("u")))  // older builds kept junk
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        for (f in facts) a.put(JSONObject().put("f", f.text).put("t", f.t).put("u", f.used))
        runCatching { file.writeText(a.toString()) }
    }

    companion object {
        @Volatile private var one: Memory? = null

        /** One memory per app (her service and the app screen share it). */
        fun get(context: Context): Memory = one ?: synchronized(this) {
            one ?: Memory(context.applicationContext).also { one = it }
        }

        private val STOP =setOf("the", "a", "an", "and", "or", "is", "are", "was", "were", "be", "to", "of", "in", "on",
            "at", "for", "with", "that", "this", "it", "they", "she", "he", "you", "your", "i", "me", "my", "we", "do",
            "does", "did", "have", "has", "had", "not", "but", "so", "what", "who", "how", "about", "like", "just", "can",
            "will", "would", "her", "his", "their", "them", "from", "up", "out", "really", "yeah", "lol")

        fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}@._]+"))
            .map { it.trim('.', '_') }.filter { it.length > 2 && it !in STOP }.toSet()

        fun overlap(a: Set<String>, b: Set<String>): Float =
            if (a.isEmpty() || b.isEmpty()) 0f else a.intersect(b).size.toFloat() / minOf(a.size, b.size)

        // small models love writing down what *isn't* there ("No jobs listed"), or notes about the chat itself
        private val JUNK = Regex("(?i)\\b(not|no|none|nothing|without|unknown|unspecified|unclear)\\b.{0,40}\\b(mentioned|" +
            "provided|listed|given|specified|named|said|stated|known|exist)|^(there (is|are|were) no|no )|\\b(capri|netrunner|" +
            "sprite|this (text|conversation|chat|message)|the (text|conversation|chat))\\b|^(the people|people in)|" +
            // "has no friends", "doesn't have any pets": an absence isn't a memory
            "\\b(has|have|had) no\\b|\\b(does not|doesn'?t|do not|don'?t) have\\b|\\b(any|no) (friends|family|pets|people|plans|" +
            "memories|chats|messages|info)\\b|" +
            // the phone, the app, the screen itself
            "\\b(android|smartphone|phone screen|the app|this app|screen|home screen|notification)\\b")

        /** Facts out of a model's "- fact" list ("NONE" = nothing worth keeping); notes about nothing are dropped. */
        fun parse(out: String): List<String> = out.lines().map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
            .map { it.trimStart('-', '*', '•', ' ').trim() }
            .filter { it.length >= 10 && !it.equals("none", true) && !JUNK.containsMatchIn(it) }
            .take(6)
    }
}
