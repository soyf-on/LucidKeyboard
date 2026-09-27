package dev.lucid.keyboard.core.lm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class WordSource { EXPLICIT, INFERRED }

@Serializable
data class UserWord(
    val word: String,
    val source: WordSource,
    /** Times the word was committed deliberately (not immediately deleted). */
    val count: Int = 0,
    val lastUsed: Long = 0,
    /** Never auto-correct this word when typed. */
    val neverCorrect: Boolean = false,
)

@Serializable
data class UserVocabularyData(
    val version: Int = 1,
    val words: List<UserWord> = emptyList(),
    /** Words (lower-case) never offered as suggestions or auto-corrections. */
    val blocked: List<String> = emptyList(),
    /** Personal replacements: typed (lower-case) -> replacement text. */
    val replacements: Map<String, String> = emptyMap(),
    /** literal (lower-case) -> corrections the user reverted; never re-applied. */
    val rejected: Map<String, List<String>> = emptyMap(),
    /** "prev next" (lower-case) -> count; used to favour the user's own phrasing. */
    val bigrams: Map<String, Int> = emptyMap(),
)

/**
 * The user's personal language data. All mutation happens on the input thread;
 * [snapshot] produces an immutable copy for persistence on a background thread.
 *
 * Learning policy (see docs/ADAPTIVE_TARGETS.md):
 *  - EXPLICIT words ("Learn word", dictionary editor, never-correct) are active immediately.
 *  - INFERRED words become active after [INFERRED_ACTIVE_COUNT] deliberate uses, or
 *    after one use that overrode an auto-correction (the strongest implicit signal).
 *  - Words typed and then deleted never count, so typos do not accumulate.
 */
class UserVocabulary(data: UserVocabularyData = UserVocabularyData()) {
    private val words = LinkedHashMap<String, UserWord>()
    private val blocked = HashSet<String>()
    private val replacements = LinkedHashMap<String, String>()
    private val rejected = HashMap<String, MutableSet<String>>()
    private val bigrams = LinkedHashMap<String, Int>(16, 0.75f, true)
    private val trie = UserTrie()
    /** Monotonic change counter so callers can cheaply detect modifications. */
    var revision = 0L
        private set

    init { load(data) }

    fun load(data: UserVocabularyData) {
        words.clear(); blocked.clear(); replacements.clear(); rejected.clear(); bigrams.clear()
        data.words.forEach { words[it.word.lowercase()] = it }
        blocked += data.blocked.map { it.lowercase() }
        replacements += data.replacements.mapKeys { it.key.lowercase() }
        data.rejected.forEach { (k, v) -> rejected[k.lowercase()] = v.map { it.lowercase() }.toMutableSet() }
        bigrams += data.bigrams
        rebuildTrie()
    }

    fun snapshot() = UserVocabularyData(
        words = words.values.toList(),
        blocked = blocked.sorted(),
        replacements = LinkedHashMap(replacements),
        rejected = rejected.mapValues { it.value.sorted() },
        bigrams = LinkedHashMap(bigrams),
    )

    // ---- queries -------------------------------------------------------------------

    fun entry(word: String): UserWord? = words[word.lowercase()]
    fun allWords(): List<UserWord> = words.values.toList()
    fun isActive(word: String): Boolean = entry(word)?.let { isActive(it) } ?: false
    fun isNeverCorrect(word: String) = entry(word)?.neverCorrect == true
    fun isBlocked(word: String) = word.lowercase() in blocked
    fun blockedWords(): Set<String> = blocked
    fun replacementFor(word: String): String? = replacements[word.lowercase()]
    fun replacements(): Map<String, String> = replacements
    fun wasRejected(literal: String, correction: String) =
        rejected[literal.lowercase()]?.contains(correction.lowercase()) == true
    fun bigramCount(prev: String, next: String) = bigrams["${prev.lowercase()} ${next.lowercase()}"] ?: 0
    /** The user's own continuations of [prev] with counts (read-only scan; ≤ MAX_BIGRAMS entries). */
    fun bigramsAfter(prev: String): List<Pair<String, Int>> {
        val key = prev.lowercase() + " "
        return bigrams.entries.filter { it.key.startsWith(key) }.map { it.key.substring(key.length) to it.value }
    }
    fun activeWords(): List<UserWord> = words.values.filter { isActive(it) }

    /** Cased form the user taught us, e.g. "McKenzie". */
    fun casedForm(word: String): String? = entry(word)?.word

    /** Weight comparable to [Lexicon] word weights (see [Lexicon.weightFromZipf]). */
    fun weight(word: String): Float = entry(word)?.let { weightOf(it) } ?: 0f

    internal val prefixTrie: UserTrie get() = trie

    private fun isActive(w: UserWord) =
        w.source == WordSource.EXPLICIT || w.neverCorrect || w.count >= INFERRED_ACTIVE_COUNT

    private fun weightOf(w: UserWord): Float {
        if (!isActive(w)) return 0f
        // Zipf ~4.3 (a common word) for explicit words, rising with use.
        val zipf = (if (w.source == WordSource.EXPLICIT) 4.3 else 3.8) + 0.25 * kotlin.math.ln(1.0 + w.count)
        return Lexicon.weightFromZipf(zipf.coerceAtMost(5.5))
    }

    // ---- explicit edits ------------------------------------------------------------

    fun learnExplicit(word: String, now: Long = System.currentTimeMillis()) {
        val key = word.lowercase()
        val old = words[key]
        words[key] = UserWord(word, WordSource.EXPLICIT, maxOf(1, old?.count ?: 0), now, old?.neverCorrect ?: false)
        blocked -= key
        changed()
    }

    fun setNeverCorrect(word: String, value: Boolean = true, now: Long = System.currentTimeMillis()) {
        val key = word.lowercase()
        val old = words[key]
        words[key] = (old ?: UserWord(word, WordSource.EXPLICIT, 1, now)).copy(neverCorrect = value)
        if (value) blocked -= key
        changed()
    }

    fun remove(word: String) {
        val key = word.lowercase()
        words.remove(key)
        changed()
    }

    /** "Don't suggest this": also forgets it as a learned word. */
    fun block(word: String) {
        val key = word.lowercase()
        blocked += key
        words.remove(key)
        changed()
    }

    fun unblock(word: String) { if (blocked.remove(word.lowercase())) changed() }

    fun setReplacement(from: String, to: String) {
        replacements[from.lowercase()] = to
        changed()
    }

    fun removeReplacement(from: String) { if (replacements.remove(from.lowercase()) != null) changed() }

    fun clearRejection(literal: String) { if (rejected.remove(literal.lowercase()) != null) changed() }

    // ---- implicit learning -----------------------------------------------------------

    /**
     * Records that [word] was committed and kept. [overrodeCorrection] means the
     * user reverted or declined an auto-correction to type it.
     */
    fun observeKept(word: String, knownToLexicon: Boolean, overrodeCorrection: Boolean, now: Long = System.currentTimeMillis()) {
        val key = word.lowercase()
        // Single characters and strings without letters are never learned implicitly.
        if (key.length < 2 || key.none { it.isLetter() } || key in blocked) return
        val old = words[key]
        if (old == null && knownToLexicon) return // dictionary words need no personal entry
        val bump = if (overrodeCorrection) INFERRED_ACTIVE_COUNT else 1
        words[key] = old?.copy(count = old.count + bump, lastUsed = now, word = if (old.source == WordSource.EXPLICIT) old.word else word)
            ?: UserWord(word, WordSource.INFERRED, bump, now)
        changed()
    }

    fun observeBigram(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        val k = "${prev.lowercase()} ${next.lowercase()}"
        bigrams[k] = (bigrams[k] ?: 0) + 1
        while (bigrams.size > MAX_BIGRAMS) bigrams.remove(bigrams.keys.first()) // LRU order
        revision++ // bigrams don't affect the trie
    }

    fun rejectCorrection(literal: String, correction: String) {
        rejected.getOrPut(literal.lowercase()) { mutableSetOf() } += correction.lowercase()
        changed()
    }

    /** Forgets everything inferred (keeps explicit words, never-correct, replacements, blocks). */
    fun resetInferred() {
        words.values.removeAll { it.source == WordSource.INFERRED && !it.neverCorrect }
        rejected.clear(); bigrams.clear()
        changed()
    }

    fun resetAll() { load(UserVocabularyData()); changed() }

    /** Drops stale, never-activated inferred candidates (typos seen once long ago). */
    fun prune(now: Long = System.currentTimeMillis()) {
        val cutoff = now - STALE_MS
        if (words.values.removeAll { it.source == WordSource.INFERRED && !isActive(it) && it.lastUsed < cutoff }) changed()
    }

    private fun changed() { revision++; rebuildTrie() }

    private fun rebuildTrie() {
        trie.clear()
        for (w in words.values) {
            val wt = weightOf(w)
            if (wt > 0f && w.word.all { Alphabet.index(it) >= 0 }) trie.add(w.word.lowercase(), wt)
        }
    }

    fun toJson(): String = JSON.encodeToString(UserVocabularyData.serializer(), snapshot())

    companion object {
        const val INFERRED_ACTIVE_COUNT = 2
        const val MAX_BIGRAMS = 5000
        const val STALE_MS = 45L * 24 * 3600 * 1000
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String) = UserVocabulary(JSON.decodeFromString(UserVocabularyData.serializer(), s))
        fun parse(s: String): UserVocabularyData = JSON.decodeFromString(UserVocabularyData.serializer(), s)
    }
}

/** Small pointer trie for the (hundreds of) personal words, with subtree masses. */
internal class UserTrie {
    class Node { val kids = HashMap<Int, Node>(4); var mass = 0f; var end = 0f }
    val root = Node()
    fun clear() { root.kids.clear(); root.mass = 0f; root.end = 0f }
    fun add(word: String, weight: Float) {
        var n = root
        n.mass += weight
        for (c in word) {
            n = n.kids.getOrPut(Alphabet.index(c)) { Node() }
            n.mass += weight
        }
        n.end += weight
    }
    fun child(n: Node?, sym: Int): Node? = n?.kids?.get(sym)
}
