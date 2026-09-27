package dev.lucid.keyboard.core.correct

import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.Alphabet
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.touch.KeyGaussian
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchPoint
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.max

enum class CorrectionMode { OFF, GENTLE, BALANCED, STRONG }

enum class ShiftSource { NONE, AUTO, MANUAL, CAPS_LOCK }

/** One character of the word being typed, as the user produced it. */
data class TypedChar(
    val char: Char,
    /** Touch position for keys decoded spatially; null for literal input (symbols page, long-press). */
    val point: TouchPoint? = null,
    val shift: ShiftSource = ShiftSource.NONE,
)

data class Candidate(val word: String, val score: Double, val edits: Int, val isCompletion: Boolean = false, val editWeight: Double = edits.toDouble())

data class CorrectionResult(
    val literal: String,
    /** Text to commit instead of the literal on a separator, or null to keep it. */
    val autoCorrection: String?,
    /** Ordered suggestions (excluding the literal), best first. */
    val suggestions: List<String>,
    val literalKnown: Boolean,
    val margin: Double,
    val reason: String,
)

/**
 * Noisy-channel word corrector.
 *
 * score(w) = Σ spatial(tapᵢ | wᵢ) + edit costs + log P(w | previous word)
 *
 * The spatial term uses the **raw touch coordinates** and the same touch model as the
 * tap decoder, *not* the letters the decoder displayed. The character prior that
 * influenced the displayed letters is therefore not applied a second time here —
 * this is what prevents "double correction" feedback between the two stages.
 */
class WordCorrector(private val lm: LanguageModel, var spatial: SpatialModel) {

    var personalOffsets = true

    /** Per-tap spatial log-scores for every letter, relative to the best letter (≤ 0). */
    private fun spatialTable(layout: KeyboardLayout?, typed: List<TypedChar>): Array<DoubleArray> =
        Array(typed.size) { i ->
            val t = typed[i]
            val row = DoubleArray(Alphabet.SIZE) { NO_MATCH }
            val own = Alphabet.index(t.char)
            if (t.point == null || layout == null) {
                if (own >= 0) row[own] = 0.0
            } else {
                var best = Double.NEGATIVE_INFINITY
                for (k in layout.letterKeys) {
                    val g = if (personalOffsets) spatial.gaussian(k.output) else DEFAULT
                    val dx = (t.point.x - k.cx) / layout.unitW
                    val dy = (t.point.y - k.cy) / layout.unitH
                    val zx = (dx - g.mx) / g.sx; val zy = (dy - g.my) / g.sy
                    val v = -0.5 * (zx * zx + zy * zy) - ln(g.sx * g.sy)
                    row[Alphabet.index(k.char)] = v
                    if (v > best) best = v
                }
                for (s in 0 until 26) row[s] = max(row[s] - best, NO_MATCH)
                // Apostrophe is never a spatial key on the letters page.
                if (own == Alphabet.APOS) row[own] = 0.0
            }
            row
        }

    private class State(val node: Int, val i: Int, val score: Double, val edits: Int, val text: String, val weight: Double = edits.toDouble())

    /**
     * Cost of treating tap i as an accidental extra. Cheap only when it plausibly was
     * one: a repeat of the previous letter, or a touch right beside the previous or
     * next tap (brushing a neighbour). A deliberate tap elsewhere is expensive to drop.
     */
    private fun insertionCosts(layout: KeyboardLayout?, typed: List<TypedChar>): DoubleArray = DoubleArray(typed.size) { i ->
        val t = typed[i]
        fun near(j: Int): Boolean {
            if (j !in typed.indices) return false
            val o = typed[j]
            if (o.char.equals(t.char, ignoreCase = true)) return true
            val a = t.point ?: return false; val b = o.point ?: return false
            val w = layout?.unitW ?: return false
            val dx = (a.x - b.x) / w; val dy = (a.y - b.y) / (layout.unitH)
            return dx * dx + dy * dy < 1.3 * 1.3
        }
        if (near(i - 1) || near(i + 1)) INS_COST else INS_FAR_COST
    }

    /**
     * Finds the best dictionary words for the taps (bounded edit search over the trie).
     * [maxEdits] counts insertions, deletions, transpositions and far substitutions;
     * near-key substitutions are paid for by the spatial score instead.
     */
    fun candidates(layout: KeyboardLayout?, typed: List<TypedChar>, prev: String?, maxEdits: Int, limit: Int = 8): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val table = spatialTable(layout, typed)
        val ins = insertionCosts(layout, typed)
        val n = typed.size
        // Searched once per active language; the word term includes that language's weight.
        var lex = lm.packs[0].lexicon
        var total = lex.totalMass.toDouble()
        var langLog = 0.0
        // The search keeps a wider pool, scored with unigram frequencies; survivors are then
        // rescored with the context model, which can promote a word the context favours.
        val pool = maxOf(limit * 3, 24)
        val results = HashMap<String, Candidate>()
        val bases = HashMap<String, Double>() // spatial + edit part of the score
        val bestSeen = PriorityQueue<Double>() // min-heap of top scores for pruning
        fun bar() = if (bestSeen.size < pool) Double.NEGATIVE_INFINITY else bestSeen.peek()
        fun offer(word: String, score: Double, edits: Int, weight: Double = edits.toDouble(), base: Double = score) {
            val old = results[word]
            if (old != null && old.score >= score) return
            if (lm.user.isBlocked(word)) return
            results[word] = Candidate(word, score, edits, editWeight = weight)
            bases[word] = base
            bestSeen.add(score); if (bestSeen.size > pool) bestSeen.poll()
        }
        var visits = 0
        fun dfs(s: State) {
            if (++visits > MAX_VISITS) return
            // Upper bound: nothing below can beat the best word weight in this subtree.
            val bound = s.score + LM_WEIGHT * (langLog + ln(max(lex.maxWeightOf(s.node).toDouble(), 1e-9) / total))
            if (bound < bar() - PRUNE_SLACK) return
            if (s.i == n) {
                val id = lex.wordIdAt(s.node)
                if (id >= 0) offer(s.text, s.score + LM_WEIGHT * (langLog + ln(lex.weight(id) / total)) + bigram(prev, s.text), s.edits, s.weight, s.score)
            }
            lex.forEachChild(s.node) { sym, child ->
                val c = Alphabet.char(sym)
                if (s.i < n) {
                    val sp = table[s.i][sym]
                    if (sp > NEAR_LIMIT) dfs(State(child, s.i + 1, s.score + sp, s.edits, s.text + c, s.weight))
                    else if (s.edits < maxEdits) {
                        // Vowel-for-vowel (definately, seperate) is a spelling error, not a slip: cheaper.
                        val vowels = isVowel(c) && isVowel(typed[s.i].char.lowercaseChar())
                        dfs(State(child, s.i + 1, s.score + if (vowels) SUB_VOWEL_COST else SUB_COST, s.edits + 1, s.text + c,
                            s.weight + if (vowels) VOWEL_SUB_WEIGHT else 1.0))
                    }
                }
                if (sym == Alphabet.APOS) {
                    // Missing apostrophe ("dont" -> "don't") is cheap and not counted as an edit.
                    if (s.i < n && Alphabet.index(typed[s.i].char) != Alphabet.APOS) dfs(State(child, s.i, s.score + APOS_COST, s.edits, s.text + c, s.weight))
                } else if (s.edits < maxEdits && s.i > 0) {
                    // Letter missing from taps. A missed *double* letter ("gezelig", "tomorow") is the
                    // most common kind, so it is cheaper.
                    val doubled = s.text.isNotEmpty() && s.text.last() == c
                    dfs(State(child, s.i, s.score + if (doubled) DOUBLE_COST else DEL_COST, s.edits + 1, s.text + c,
                        s.weight + if (doubled) DOUBLE_WEIGHT else 1.0))
                }
                // Transposition: taps i, i+1 were typed in swapped order.
                if (s.edits < maxEdits && s.i + 1 < n && sym != Alphabet.APOS) {
                    val a = table[s.i + 1][sym]
                    if (a > NEAR_LIMIT) {
                        lex.forEachChild(child) { sym2, child2 ->
                            val b = table[s.i][sym2]
                            if (b > NEAR_LIMIT && sym2 != sym) dfs(State(child2, s.i + 2, s.score + a + b + TRANS_COST, s.edits + 1, s.text + c + Alphabet.char(sym2), s.weight + TRANSPOSITION_WEIGHT))
                        }
                    }
                }
            }
            // Extra tap (insertion): skip it.
            // An extra tap may be anywhere, including the first letter ("rnew" -> "new").
            if (s.i < n && s.edits < maxEdits && (s.i > 0 || n >= 3)) {
                val repeat = s.i > 0 && Alphabet.index(typed[s.i].char) == Alphabet.index(typed[s.i - 1].char) // "untill"
                dfs(State(s.node, s.i + 1, s.score + ins[s.i], s.edits + 1, s.text, s.weight + if (repeat) DOUBLE_WEIGHT else 1.0))
            }
        }
        for ((pi, pack) in lm.packs.withIndex()) {
            lex = pack.lexicon; total = lex.totalMass.toDouble()
            langLog = ln(lm.languageWeights[pi])
            visits = 0
            dfs(State(0, 0, 0.0, 0, ""))
        }
        // Personal words aren't in the static trie; score them directly by alignment.
        for (uw in lm.user.activeWords()) {
            val w = uw.word.lowercase()
            if (lm.inLexicon(w) || w.length !in (n - maxEdits)..(n + maxEdits)) continue
            val sc = alignScore(table, ins, w, maxEdits) ?: continue
            val lp = lm.wordLogProb(w, prev) ?: continue
            offer(w, sc.first + LM_WEIGHT * lp, sc.second, base = sc.first)
        }
        // Rescore with context: P(word | previous word), mixed over languages.
        return results.values.map { c ->
            val lp = lm.wordLogProb(c.word, prev) ?: return@map c
            c.copy(score = bases.getValue(c.word) + LM_WEIGHT * lp)
        }.sortedByDescending { it.score }.take(limit)
    }

    /** Restricted Damerau–Levenshtein alignment using spatial costs; returns (score, edits). */
    private fun alignScore(table: Array<DoubleArray>, ins: DoubleArray, word: String, maxEdits: Int): Pair<Double, Int>? {
        val n = table.size; val m = word.length
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.NEGATIVE_INFINITY } }
        val ed = Array(n + 1) { IntArray(m + 1) }
        dp[0][0] = 0.0
        for (i in 0..n) for (j in 0..m) {
            if (i == 0 && j == 0) continue
            var best = Double.NEGATIVE_INFINITY; var be = 0
            fun take(v: Double, e: Int) { if (v > best) { best = v; be = e } }
            if (i > 0 && j > 0) {
                val s = Alphabet.index(word[j - 1]).let { if (it < 0) NO_MATCH else table[i - 1][it] }
                if (s > NEAR_LIMIT) take(dp[i - 1][j - 1] + s, ed[i - 1][j - 1]) else take(dp[i - 1][j - 1] + SUB_COST, ed[i - 1][j - 1] + 1)
            }
            if (i > 0) take(dp[i - 1][j] + ins[i - 1], ed[i - 1][j] + 1)
            if (j > 0) take(dp[i][j - 1] + DEL_COST, ed[i][j - 1] + 1)
            dp[i][j] = best; ed[i][j] = be
        }
        return if (ed[n][m] <= maxEdits && dp[n][m] > Double.NEGATIVE_INFINITY) dp[n][m] to ed[n][m] else null
    }

    private fun bigram(prev: String?, w: String): Double =
        if (prev == null) 0.0 else lm.user.bigramCount(prev, w).let { if (it > 0) ln(1.0 + LanguageModel.BIGRAM_BOOST * it) else 0.0 }

    /** Score of the literal letters under the same model, for comparison. */
    fun literalScore(layout: KeyboardLayout?, typed: List<TypedChar>, prev: String?): Double {
        val table = spatialTable(layout, typed)
        val lit = typed.joinToString("") { it.char.toString() }.lowercase()
        var sp = 0.0
        typed.forEachIndexed { i, t -> val s = Alphabet.index(t.char); sp += if (s < 0) 0.0 else table[i][s] }
        // An unknown literal's spelling improbability is floored per character: precise
        // taps on an unusual spelling (names, other languages) are evidence of intent.
        val lp = lm.wordLogProb(lit, prev) ?: (max(lm.ngramLogProb(lit), OOV_CHAR_FLOOR * (lit.length + 1)) + LanguageModel.OOV_PENALTY)
        return sp + LM_WEIGHT * lp
    }

    /**
     * Full decision for the word at a separator.
     * [sentenceStart] tells whether an initial capital came from sentence position.
     * [rejected] asks whether the user already reverted literal->candidate.
     */
    fun correct(
        layout: KeyboardLayout?,
        typed: List<TypedChar>,
        prev: String?,
        mode: CorrectionMode,
        sentenceStart: Boolean,
        suggestionsWanted: Boolean = true,
    ): CorrectionResult {
        val literal = typed.joinToString("") { it.char.toString() }
        val lower = literal.lowercase()
        val known = lm.isKnown(lower)
        fun result(auto: String?, sugg: List<String>, margin: Double, reason: String) =
            CorrectionResult(literal, auto?.let { restoreCase(it, typed) }, sugg.map { restoreCase(it, typed) }, known, margin, reason)

        if (literal.isEmpty()) return result(null, emptyList(), 0.0, "empty")

        val onlyLetters = lower.all { Alphabet.index(it) >= 0 }
        val maxEdits = when { lower.length <= 2 -> 0; lower.length <= 4 -> 1; else -> 2 }
        val cands = if (onlyLetters && (suggestionsWanted || mode != CorrectionMode.OFF))
            candidates(layout, typed, prev, maxEdits) else emptyList()
        val sugg = cands.map { lm.casedForm(it.word) }.filter { !it.equals(literal, ignoreCase = true) }.distinct().take(4)

        if (mode == CorrectionMode.OFF) {
            // Personal replacements are still offered, just never applied automatically.
            val rep = lm.user.replacementFor(lower)
            return result(null, if (rep != null) listOf(rep) + sugg else sugg, 0.0, "off")
        }

        // Explicit personal replacements always win (they are the user's own rule).
        lm.user.replacementFor(lower)?.let { return result(it, listOf(it) + sugg, 99.0, "personal replacement") }
        if (lm.user.isNeverCorrect(lower)) return result(null, sugg, 0.0, "never-correct")
        if (!onlyLetters) return result(null, sugg, 0.0, "mixed alphanumeric/symbols")
        if (lower == "i") return result("I", sugg, 99.0, "pronoun I")
        if (typed.size >= 2 && typed.all { it.char.isUpperCase() } && typed.any { it.shift != ShiftSource.CAPS_LOCK })
            return result(null, sugg, 0.0, "all-caps (acronym)")

        lm.replacementFor(lower)?.let { (to, minMode) ->
            val need = CorrectionMode.valueOf(minMode.uppercase())
            if (mode.ordinal >= need.ordinal && !lm.user.wasRejected(lower, to)) return result(to, listOf(to) + sugg, 99.0, "contraction")
        }

        if (lm.user.isActive(lower)) return result(null, sugg, 0.0, "personal word")
        // Accent restoration: the dictionary spells this word with diacritics ("ideeen" -> "ideeën").
        if (known) {
            val cased = lm.casedForm(lower)
            if (!cased.equals(literal, ignoreCase = true) && fold(cased) == fold(lower) && !lm.user.wasRejected(lower, cased))
                return result(cased, sugg, 99.0, "accents")
        }
        val properNoun = typed.first().shift == ShiftSource.MANUAL && !sentenceStart
        val litScore = literalScore(layout, typed, prev)
        val best = cands.firstOrNull { it.word != lower && !lm.user.wasRejected(lower, it.word) }
            ?: return result(null, sugg, 0.0, "no candidate")
        val second = cands.firstOrNull { it !== best && it.word != lower }
        val margin = best.score - litScore
        val ambiguity = if (second == null) 99.0 else best.score - second.score
        val bestIsCapitalised = lm.casedForm(best.word).first().isUpperCase()

        // Every real edit (not a near-key slip) must be paid for with extra confidence,
        // so rewriting an unknown word into a different one needs overwhelming evidence.
        val need = EDIT_MARGIN * best.editWeight
        val ok = when (mode) {
            CorrectionMode.OFF -> false
            // Gentle leaves informal spellings ("dont", "im") alone: apostrophe-only fixes are Balanced+.
            CorrectionMode.GENTLE -> !known && !properNoun && best.edits <= 1 && lower.length >= 3 &&
                best.word.replace("'", "") != lower &&
                margin > GENTLE_MARGIN + need && ambiguity > GENTLE_AMBIGUITY
            CorrectionMode.BALANCED -> !known && (!properNoun || bestIsCapitalised) && margin > BALANCED_MARGIN + need
            CorrectionMode.STRONG -> if (known) best.edits == 0 && margin > STRONG_KNOWN_MARGIN
            else margin > STRONG_MARGIN + need
        }
        return if (ok) result(lm.casedForm(best.word), sugg, margin, "corrected")
        else result(null, sugg, margin, if (known) "literal is a known word" else "below threshold")
    }

    /** Word completions for the suggestion strip while typing (prefix predictions), across languages. */
    fun completions(prefixLower: String, limit: Int = 3): List<String> {
        val out = HashMap<String, Double>()
        for ((pi, pack) in lm.packs.withIndex()) {
            val lex = pack.lexicon
            val node = lex.nodeFor(prefixLower)
            if (node < 0) continue
            val w = lm.languageWeights[pi] / lex.totalMass
            // Best-first over the subtree by the largest word weight below each node.
            val pq = PriorityQueue<Pair<Int, Float>>(compareByDescending { it.second })
            pq += node to lex.maxWeightOf(node)
            var steps = 0; var found = 0
            while (pq.isNotEmpty() && found < limit + 2 && steps++ < 4000) {
                val (nd, _) = pq.poll()
                val id = lex.wordIdAt(nd)
                if (id >= 0 && nd != node) {
                    val cased = lex.cased(id)
                    if (!lm.user.isBlocked(cased)) { out.merge(cased, lex.weight(id) * w) { a, b2 -> a + b2 }; found++ }
                }
                lex.forEachChild(nd) { _, ch -> pq += ch to lex.maxWeightOf(ch) }
            }
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    companion object {
        private val DEFAULT = KeyGaussian(0.0, 0.0, SpatialModel.DEFAULT_SX, SpatialModel.DEFAULT_SY)
        const val NO_MATCH = -30.0
        /** Spatial log-score below which a letter is treated as a far substitution (edit). */
        const val NEAR_LIMIT = -9.0
        const val SUB_COST = -9.5
        const val SUB_VOWEL_COST = -6.5
        const val VOWEL_SUB_WEIGHT = 0.5
        const val DEL_COST = -7.0
        const val DOUBLE_COST = -4.0
        const val DOUBLE_WEIGHT = 0.4
        const val INS_COST = -6.5
        const val INS_FAR_COST = -12.0
        const val EDIT_MARGIN = 2.5
        const val TRANSPOSITION_WEIGHT = 0.4
        const val OOV_CHAR_FLOOR = -3.2
        const val TRANS_COST = -4.5
        const val APOS_COST = -1.0
        const val LM_WEIGHT = 1.0
        const val PRUNE_SLACK = 2.0
        const val MAX_VISITS = 60_000

        const val GENTLE_MARGIN = 5.0
        const val GENTLE_AMBIGUITY = 1.0
        const val BALANCED_MARGIN = 2.5
        const val STRONG_MARGIN = 1.0
        const val STRONG_KNOWN_MARGIN = 7.0

        private fun isVowel(c: Char) = c in "aeiou"
        private fun fold(s: String) = s.map { Alphabet.fold(it) ?: it }.joinToString("")

        /** Applies the typed word's capitalisation to a candidate. */
        fun restoreCase(candidate: String, typed: List<TypedChar>): String {
            if (typed.isEmpty() || candidate.isEmpty()) return candidate
            val allCaps = typed.size >= 2 && typed.all { !it.char.isLetter() || it.char.isUpperCase() }
            if (allCaps) return candidate.uppercase()
            if (typed.first().char.isUpperCase()) return candidate.replaceFirstChar { it.uppercaseChar() }
            return candidate
        }
    }
}
