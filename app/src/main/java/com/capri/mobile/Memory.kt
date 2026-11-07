package com.capri.mobile

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

    /** What she already knows that's about the same thing as [fact] (for her second pass, see Brain.reconcile). */
    @Synchronized
    fun similar(fact: String, limit: Int = 4): List<String> {
        val w = words(fact)
        return facts.map { it.text to overlap(w, words(it.text)) }.filter { it.second >= 0.34f }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * Applies her verdict on [fact] against [near]: ADD, UPDATE n, DELETE n or NONE. Anything else counts as ADD (a
     * small model's muddled answer shouldn't lose a memory). True when she learned something new.
     */
    @Synchronized
    fun apply(fact: String, near: List<String>, verdict: String): Boolean {
        val v = verdict.trim().uppercase()
        val target = Regex("\\d+").find(v)?.value?.toIntOrNull()?.let { near.getOrNull(it - 1) }
        return when {
            v.startsWith("NONE") -> false
            v.startsWith("UPDATE") && target != null -> {
                facts.removeAll { it.text == target }
                add(listOf(fact))
                true
            }
            v.startsWith("DELETE") && target != null -> {
                facts.removeAll { it.text == target }
                add(listOf(fact))
                true
            }
            else -> add(listOf(fact)).isNotEmpty()
        }
    }

    /**
     * The facts that matter for [about]: ranked with BM25 (rare words count for more than common ones, the same scoring
     * search engines use), names on top of that, and a little for being recent or brought up lately.
     */
    @Synchronized
    fun relevant(about: String, limit: Int = 6): List<String> {
        if (facts.isEmpty()) return emptyList()
        val q = words(about)
        val now = System.currentTimeMillis()
        val docs = facts.map { it to words(it.text) }
        val avg = docs.map { it.second.size }.average().toFloat().coerceAtLeast(1f)
        val n = docs.size.toFloat()
        val idf = q.associateWith { term ->
            val has = docs.count { term in it.second }
            kotlin.math.ln(1f + (n - has + 0.5f) / (has + 0.5f))
        }
        val scored = docs.map { (f, w) ->
            val shared = q.intersect(w)
            val bm25 = shared.sumOf { t -> (idf.getValue(t) * 2.2f / (1f + 1.2f * (0.25f + 0.75f * w.size / avg))).toDouble() }.toFloat()
            val names = shared.count { t -> f.text.split(" ").any { it.trim(',', '.', '(', ')', '\'').equals(t, true) && it.first().isUpperCase() } }
            val age = (now - f.t) / 86_400_000f
            val lately = (now - f.used) / 86_400_000f
            f to (bm25 + names * 2f + 0.6f / (1f + age) + 0.2f / (1f + lately))
        }.sortedByDescending { it.second }
        val pick = scored.filter { it.second >= 1.2f }.take(limit).map { it.first }
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
        if (!file.exists()) return
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = o.getString("f")
                if (!JUNK.containsMatchIn(f)) facts.add(Fact(f, o.getLong("t"), o.optLong("u")))  // older builds kept junk
            }
        }.onFailure {  // a file she can't read is kept aside, never written over
            runCatching { file.copyTo(File(file.path + ".unreadable-" + System.currentTimeMillis())) }
        }
    }

    /** Written to a temp file first, then swapped in: a crash mid-write never leaves her memory half gone. */
    private fun save() {
        val a = JSONArray()
        for (f in facts) a.put(JSONObject().put("f", f.text).put("t", f.t).put("u", f.used))
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
        }
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
