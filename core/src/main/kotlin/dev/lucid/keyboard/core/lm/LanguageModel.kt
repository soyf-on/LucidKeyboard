package dev.lucid.keyboard.core.lm

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Static models for one language. */
class LanguagePack(
    val code: String,
    val lexicon: Lexicon,
    val ngram: CharNgram,
    /** Contraction fixes ("dont" -> "don't"): from -> (to, weakest mode name). */
    val replacements: Map<String, Pair<String, String>> = emptyMap(),
    /** Next-word statistics; null if not bundled for this language. */
    val bigrams: BigramModel? = null,
)

/**
 * Combines one or more [LanguagePack]s, the user's [UserVocabulary] and the character
 * n-grams into (a) next-character distributions for the touch decoder and (b) word
 * probabilities for correction and suggestions.
 *
 * Multilingual typing: every active language contributes, weighted by [languageWeights] —
 * a running estimate of which language the user is writing in, updated after each
 * committed word ([observeWord]). Mixed sentences work because no active language's
 * weight drops below [MIN_LANGUAGE_WEIGHT]; a word valid in *any* active language is
 * treated as known and is never "corrected" into another language.
 */
class LanguageModel(packs: List<LanguagePack>, val user: UserVocabulary) {

    constructor(lexicon: Lexicon, ngram: CharNgram, user: UserVocabulary, replacements: Map<String, Pair<String, String>> = emptyMap()) :
        this(listOf(LanguagePack("en", lexicon, ngram, replacements)), user)

    var packs: List<LanguagePack> = packs
        private set
    var languageWeights = DoubleArray(packs.size) { 1.0 / packs.size }
        private set

    /** Replaces the active languages (e.g. from settings). Resets the language estimate. */
    fun setPacks(p: List<LanguagePack>) {
        require(p.isNotEmpty())
        packs = p
        languageWeights = DoubleArray(p.size) { 1.0 / p.size }
    }

    fun weightOf(code: String): Double = packs.indexOfFirst { it.code == code }.let { if (it < 0) 0.0 else languageWeights[it] }

    /** A position inside the current word: letters so far and where they lead in each trie. */
    class Prefix internal constructor(
        val text: String,
        internal val lex: IntArray,
        internal val usr: UserTrie.Node?,
    ) {
        val inVocabulary get() = usr != null || lex.any { it >= 0 }
    }

    fun root() = Prefix("", IntArray(packs.size), user.prefixTrie.root)

    fun extend(p: Prefix, c: Char): Prefix {
        val s = Alphabet.index(c)
        val t = p.text + c.lowercaseChar()
        if (s < 0 || p.lex.size != packs.size) return Prefix(t, IntArray(packs.size) { -1 }, null)
        return Prefix(t, IntArray(packs.size) { i -> if (p.lex[i] >= 0) packs[i].lexicon.child(p.lex[i], s) else -1 }, user.prefixTrie.child(p.usr, s))
    }

    fun prefixOf(text: CharSequence): Prefix {
        var p = root()
        for (c in text) p = extend(p, c)
        return p
    }

    /**
     * Fills [out] with P(next symbol | prefix) over [Alphabet] (END = word ends here).
     * Returns a confidence in [0.35, 1]: high when the prefix leads to plenty of known
     * words, low when relying on the character n-grams.
     */
    fun distribution(p: Prefix, out: DoubleArray): Double {
        out.fill(0.0)
        val tmp = DoubleArray(Alphabet.SIZE)
        val vocab = DoubleArray(Alphabet.SIZE)
        var conf = 0.0
        for ((i, pack) in packs.withIndex()) {
            val w = languageWeights[i]
            pack.ngram.distribution(p.text, tmp)
            vocab.fill(0.0)
            var total = 0.0
            val node = if (i < p.lex.size) p.lex[i] else -1
            if (node >= 0) {
                pack.lexicon.forEachChild(node) { sym, child -> vocab[sym] += pack.lexicon.massOf(child).toDouble() }
                vocab[Alphabet.END] += pack.lexicon.endWeight(node).toDouble()
            }
            p.usr?.let { u ->
                for ((sym, child) in u.kids) vocab[sym] += child.mass.toDouble()
                vocab[Alphabet.END] += u.end.toDouble()
            }
            for (v in vocab) total += v
            if (total > 0.0) {
                for (s in 0 until Alphabet.SIZE) out[s] += w * ((1 - NGRAM_MIX) * vocab[s] / total + NGRAM_MIX * tmp[s])
                conf += w * (OOV_CONFIDENCE + (1 - OOV_CONFIDENCE) * total / (total + CONFIDENCE_MASS))
            } else {
                for (s in 0 until Alphabet.SIZE) out[s] += w * tmp[s]
                conf += w * OOV_CONFIDENCE
            }
        }
        return conf
    }

    fun inLexicon(word: String): Boolean = packs.any { it.lexicon.contains(word) }

    /** Known = in any active dictionary or an active personal word, and not blocked. */
    fun isKnown(word: String): Boolean =
        !user.isBlocked(word) && (inLexicon(word) || user.isActive(word))

    /** Probability of [word] under language [i]'s unigram model (0 if unknown there). */
    fun packProb(i: Int, word: String): Double {
        val lex = packs[i].lexicon
        val id = lex.wordId(word)
        return if (id < 0) 0.0 else lex.weight(id).toDouble() / lex.totalMass
    }

    /** P(word | prev) under language [i]: bigram-interpolated when context is available. */
    fun packContextProb(i: Int, word: String, prev: String?): Double {
        val lex = packs[i].lexicon
        val id = lex.wordId(word)
        if (id < 0) return 0.0
        val uni = lex.weight(id).toDouble() / lex.totalMass
        val bg = packs[i].bigrams ?: return uni
        if (prev == null) return uni
        val pid = lex.wordId(prev)
        return if (pid < 0) uni else bg.prob(pid, id, uni)
    }

    /** log P(word | prev) mixed over languages, with a light personal-bigram boost; null for unknown words. */
    fun wordLogProb(word: String, prev: String? = null): Double? {
        if (user.isBlocked(word)) return null
        var p = 0.0
        for (i in packs.indices) p += languageWeights[i] * packContextProb(i, word, prev)
        val u = user.weight(word)
        if (u > 0f) p += u.toDouble() / packs[0].lexicon.totalMass
        if (p <= 0.0) return null
        var lp = ln(p)
        if (prev != null) {
            val bc = user.bigramCount(prev, word)
            if (bc > 0) lp += ln(1.0 + BIGRAM_BOOST * bc)
        }
        return lp
    }

    /**
     * Likely next words after [prev] (cased), from the bundled word pairs of each active
     * language weighted by the language estimate, plus the user's own word pairs.
     */
    fun predictNext(prev: String, k: Int = 3): List<String> {
        val scores = HashMap<String, Double>()
        for ((i, pack) in packs.withIndex()) {
            val bg = pack.bigrams ?: continue
            val pid = pack.lexicon.wordId(prev)
            if (pid < 0) continue
            for ((id, p) in bg.top(pid, 12)) {
                val w = pack.lexicon.cased(id)
                if (user.isBlocked(w)) continue
                scores.merge(w, languageWeights[i] * p) { a, b -> a + b }
            }
        }
        for ((next, c) in user.bigramsAfter(prev)) {
            if (user.isBlocked(next)) continue
            val w = casedForm(next)
            scores.merge(w, 0.08 * c) { a, b -> a + b }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }.take(k)
    }

    /** Character-model log probability of an unknown string, mixed over languages. */
    fun ngramLogProb(word: String): Double {
        var m = Double.NEGATIVE_INFINITY
        val lps = packs.map { it.ngram.logProbWord(word) }
        for (v in lps) m = max(m, v)
        var s = 0.0
        for ((i, v) in lps.withIndex()) s += languageWeights[i] * exp(v - m)
        return m + ln(s)
    }

    /** Probability of an unknown string: character model plus an out-of-vocabulary cost. */
    fun oovLogProb(word: String): Double = ngramLogProb(word) + OOV_PENALTY

    /** Contraction fix for [word] from a language that is currently likely (weight ≥ 0.4). */
    fun replacementFor(word: String): Pair<String, String>? {
        for ((i, pack) in packs.withIndex()) {
            if (languageWeights[i] < 0.4 && packs.size > 1) continue
            pack.replacements[word]?.let { return it }
        }
        return null
    }

    /** Preferred display form: the user's casing, else the most likely language's, else as typed. */
    fun casedForm(word: String): String {
        user.casedForm(word)?.takeIf { user.isActive(word) }?.let { return it }
        var best: String? = null; var bestP = 0.0
        for ((i, pack) in packs.withIndex()) {
            val id = pack.lexicon.wordId(word)
            if (id < 0) continue
            val p = languageWeights[i] * pack.lexicon.weight(id) / pack.lexicon.totalMass
            if (p > bestP) { bestP = p; best = pack.lexicon.cased(id) }
        }
        return best ?: word
    }

    /**
     * Updates the language estimate from a committed word: languages in which the word
     * is likely gain weight. Unknown words leave the estimate unchanged.
     */
    fun observeWord(word: String) {
        if (packs.size < 2) return
        val lw = word.lowercase()
        val probs = DoubleArray(packs.size) { packProb(it, lw) }
        if (probs.all { it == 0.0 }) return
        val post = DoubleArray(packs.size) { languageWeights[it] * (probs[it] + 1e-7) }
        val z = post.sum()
        val next = DoubleArray(packs.size) { (1 - LANGUAGE_RATE) * languageWeights[it] + LANGUAGE_RATE * post[it] / z }
        for (i in next.indices) next[i] = max(next[i], MIN_LANGUAGE_WEIGHT)
        val z2 = next.sum()
        languageWeights = DoubleArray(next.size) { next[it] / z2 }
    }

    companion object {
        const val OOV_CONFIDENCE = 0.35
        const val NGRAM_MIX = 0.04
        /** Subtree mass at which confidence reaches halfway (≈ one Zipf-2.5 word). */
        const val CONFIDENCE_MASS = 0.3
        const val BIGRAM_BOOST = 1.5
        const val OOV_PENALTY = -4.0
        /** How fast the language estimate follows the words typed. */
        const val LANGUAGE_RATE = 0.4
        const val MIN_LANGUAGE_WEIGHT = 0.12
    }
}
