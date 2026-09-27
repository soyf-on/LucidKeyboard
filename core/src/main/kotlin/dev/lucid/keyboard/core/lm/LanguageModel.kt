package dev.lucid.keyboard.core.lm

import kotlin.math.ln

/**
 * Combines the bundled [Lexicon], the user's [UserVocabulary] and the [CharNgram]
 * fallback into (a) next-character distributions for the touch decoder and
 * (b) word probabilities for correction and suggestions.
 */
class LanguageModel(
    val lexicon: Lexicon,
    val ngram: CharNgram,
    val user: UserVocabulary,
    /** Built-in contraction fixes ("dont" -> "don't") with the weakest mode that applies each. */
    val builtInReplacements: Map<String, Pair<String, String>> = emptyMap(),
) {
    /** A position inside the current word: the letters so far and where they lead in each trie. */
    class Prefix internal constructor(
        val text: String,
        internal val lex: Int,
        internal val usr: UserTrie.Node?,
    ) {
        val inVocabulary get() = lex >= 0 || usr != null
    }

    fun root() = Prefix("", 0, user.prefixTrie.root)

    fun extend(p: Prefix, c: Char): Prefix {
        val s = Alphabet.index(c)
        if (s < 0) return Prefix(p.text + c.lowercaseChar(), -1, null)
        return Prefix(
            p.text + c.lowercaseChar(),
            if (p.lex >= 0) lexicon.child(p.lex, s) else -1,
            user.prefixTrie.child(p.usr, s),
        )
    }

    fun prefixOf(text: CharSequence): Prefix {
        var p = root()
        for (c in text) p = extend(p, c)
        return p
    }

    /**
     * Fills [out] with P(next symbol | prefix) over [Alphabet] (END = word ends here).
     * Returns a confidence in [0.35, 1]: high when the prefix leads to plenty of known
     * words, low when we are relying on the character n-gram.
     */
    fun distribution(p: Prefix, out: DoubleArray): Double {
        ngram.distribution(p.text, out)
        if (!p.inVocabulary) return OOV_CONFIDENCE
        val vocab = DoubleArray(Alphabet.SIZE)
        var total = 0.0
        if (p.lex >= 0) {
            lexicon.forEachChild(p.lex) { sym, child -> vocab[sym] += lexicon.massOf(child).toDouble() }
            vocab[Alphabet.END] += lexicon.endWeight(p.lex).toDouble()
        }
        p.usr?.let { u ->
            for ((sym, child) in u.kids) vocab[sym] += child.mass.toDouble()
            vocab[Alphabet.END] += u.end.toDouble()
        }
        for (v in vocab) total += v
        if (total <= 0.0) return OOV_CONFIDENCE
        for (i in 0 until Alphabet.SIZE) out[i] = (1 - NGRAM_MIX) * vocab[i] / total + NGRAM_MIX * out[i]
        return OOV_CONFIDENCE + (1 - OOV_CONFIDENCE) * total / (total + CONFIDENCE_MASS)
    }

    /** Known = in the dictionary or an active personal word, and not blocked by the user. */
    fun isKnown(word: String): Boolean =
        !user.isBlocked(word) && (lexicon.contains(word) || user.isActive(word))

    /** log P(word | prev) with a light personal-bigram boost; null for unknown words. */
    fun wordLogProb(word: String, prev: String? = null): Double? {
        if (user.isBlocked(word)) return null
        val lexW = lexicon.wordId(word).let { if (it < 0) 0f else lexicon.weight(it) }
        val w = lexW + user.weight(word)
        if (w <= 0f) return null
        var lp = ln(w.toDouble() / lexicon.totalMass)
        if (prev != null) {
            val bc = user.bigramCount(prev, word)
            if (bc > 0) lp += ln(1.0 + BIGRAM_BOOST * bc)
        }
        return lp
    }

    /** Probability of an unknown string: character model plus an out-of-vocabulary cost. */
    fun oovLogProb(word: String): Double = ngram.logProbWord(word) + OOV_PENALTY

    /** Preferred display form: the user's casing, else the dictionary's, else as typed. */
    fun casedForm(word: String): String =
        user.casedForm(word)?.takeIf { user.isActive(word) }
            ?: lexicon.wordId(word).let { if (it >= 0) lexicon.cased(it) else word }

    companion object {
        const val OOV_CONFIDENCE = 0.35
        const val NGRAM_MIX = 0.04
        /** Subtree mass at which confidence reaches halfway (≈ one Zipf-2.5 word). */
        const val CONFIDENCE_MASS = 0.3
        const val BIGRAM_BOOST = 1.5
        const val OOV_PENALTY = -4.0
    }
}
