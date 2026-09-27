package dev.lucid.keyboard.core.gesture

import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.Alphabet
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.lm.Lexicon
import dev.lucid.keyboard.core.touch.TouchPoint
import kotlin.math.hypot

/**
 * Slide-to-type decoder (shape matching in the spirit of SHARK², Kristensson & Zhai 2004).
 *
 * The finger path and each candidate word's ideal path (straight lines through its key
 * centres) are resampled to [N] equidistant points; the mean point-to-point distance,
 * in key pitches, is the location match. Candidates come from words whose first and
 * last letters are near the path's start and end, in every active language. The final
 * score adds log P(word | previous word) so context breaks ties between similar shapes.
 */
class GestureDecoder(private val lm: LanguageModel) {

    data class Result(val word: String, val score: Double, val distance: Double)

    /** Per-lexicon index: (first symbol, last symbol) -> word ids, most frequent first. */
    private val index = HashMap<Lexicon, Array<IntArray>>()

    @Synchronized
    private fun indexFor(lex: Lexicon): Array<IntArray> = index.getOrPut(lex) {
        val buckets = Array(26 * 26) { ArrayList<Pair<Int, Float>>() }
        val sb = StringBuilder()
        fun walk(node: Int) {
            val id = lex.wordIdAt(node)
            if (id >= 0 && sb.length >= 2) {
                val f = Alphabet.index(sb[0]); val l = Alphabet.index(sb[sb.length - 1])
                if (f in 0..25 && l in 0..25) buckets[f * 26 + l] += id to lex.weight(id)
            }
            lex.forEachChild(node) { sym, child ->
                if (sym < 26) { sb.append('a' + sym); walk(child); sb.setLength(sb.length - 1) }
            }
        }
        walk(0)
        Array(buckets.size) { i -> buckets[i].sortedByDescending { it.second }.take(PER_BUCKET).map { it.first }.toIntArray() }
    }

    /** Builds indexes ahead of the first gesture (call from a background thread at startup). */
    fun warmUp() { for (p in lm.packs) indexFor(p.lexicon) }

    fun decode(layout: KeyboardLayout, path: List<TouchPoint>, prev: String?, limit: Int = 5): List<Result> {
        if (path.size < 2) return emptyList()
        val ux = layout.unitW; val uy = layout.unitH
        val user = resample(path.map { floatArrayOf(it.x / ux, it.y / uy) }, N)
        val first = user[0]; val last = user[N - 1]
        fun nearKeys(pt: FloatArray): List<Int> = layout.letterKeys
            .map { k -> Alphabet.index(k.char) to hypot(k.cx / ux - pt[0], k.cy / uy - pt[1]) }
            .filter { it.second < END_RADIUS }.sortedBy { it.second }.take(4).map { it.first }
        val starts = nearKeys(first); val ends = nearKeys(last)
        val centers = HashMap<Int, FloatArray>()
        for (k in layout.letterKeys) centers[Alphabet.index(k.char)] = floatArrayOf(k.cx / ux, k.cy / uy)

        val best = HashMap<String, Result>()
        for (pack in lm.packs) {
            val lex = pack.lexicon
            val idx = indexFor(lex)
            for (s in starts) for (e in ends) for (id in idx[s * 26 + e]) {
                val word = lex.cased(id)
                val pts = ArrayList<FloatArray>(word.length)
                var lastSym = -1
                var ok = true
                for (c in word) {
                    val sym = Alphabet.index(c)
                    if (sym !in 0..25) { if (sym == Alphabet.APOS) continue; ok = false; break }
                    if (sym == lastSym) continue // double letters are one point on the path
                    pts += centers[sym] ?: run { ok = false; null } ?: break
                    lastSym = sym
                }
                if (!ok || pts.isEmpty()) continue
                val ideal = if (pts.size == 1) List(N) { pts[0] } else resample(pts, N)
                val (dist, shape) = match(ideal, user)
                if (dist > MAX_MEAN_DIST) continue
                val lp = lm.wordLogProb(word.lowercase(), prev) ?: continue
                val score = shape + LM_WEIGHT * lp
                val key = word.lowercase()
                val old = best[key]
                if (old == null || old.score < score) best[key] = Result(lm.casedForm(key), score, dist)
            }
        }
        // Personal words (not in the static dictionaries).
        for (uw in lm.user.activeWords()) {
            val w = uw.word
            if (w.length < 2 || lm.inLexicon(w.lowercase())) continue
            val f = Alphabet.index(w[0]); val l = Alphabet.index(w[w.length - 1])
            if (f !in starts || l !in ends) continue
            val pts = w.mapNotNull { centers[Alphabet.index(it)] }
            if (pts.size < 2) continue
            val ideal = resample(pts, N)
            val (dist, shape) = match(ideal, user)
            val lp = lm.wordLogProb(w.lowercase(), prev) ?: continue
            if (dist <= MAX_MEAN_DIST) best[w.lowercase()] = Result(w, shape + LM_WEIGHT * lp, dist)
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    /**
     * (mean distance, log-likelihood of the shape). The shape term treats the path as
     * ~[EFFECTIVE_POINTS] independent observations, and adds a separate term for the start
     * and end points, which people hit much more precisely than the middle of a swipe.
     */
    private fun match(ideal: List<FloatArray>, user: List<FloatArray>): Pair<Double, Double> {
        var sum = 0.0; var sumSq = 0.0
        for (i in 0 until N) {
            val d = hypot((ideal[i][0] - user[i][0]).toDouble(), (ideal[i][1] - user[i][1]).toDouble())
            sum += d; sumSq += d * d
        }
        val ds = hypot((ideal[0][0] - user[0][0]).toDouble(), (ideal[0][1] - user[0][1]).toDouble())
        val de = hypot((ideal[N - 1][0] - user[N - 1][0]).toDouble(), (ideal[N - 1][1] - user[N - 1][1]).toDouble())
        val shape = -(EFFECTIVE_POINTS * sumSq / N) / (2 * SIGMA * SIGMA) - (ds * ds + de * de) / (2 * END_SIGMA * END_SIGMA)
        return sum / N to shape
    }

    companion object {
        const val N = 40
        const val EFFECTIVE_POINTS = 10.0
        const val END_SIGMA = 0.35
        const val PER_BUCKET = 1800
        const val END_RADIUS = 1.1f
        const val MAX_MEAN_DIST = 0.9
        const val SIGMA = 0.30
        const val LM_WEIGHT = 1.0

        /** Resamples a polyline to [n] points equally spaced along its length. */
        fun resample(pts: List<FloatArray>, n: Int): List<FloatArray> {
            if (pts.size == 1) return List(n) { pts[0] }
            var total = 0.0
            for (i in 1 until pts.size) total += hypot((pts[i][0] - pts[i - 1][0]).toDouble(), (pts[i][1] - pts[i - 1][1]).toDouble())
            if (total == 0.0) return List(n) { pts[0] }
            val step = total / (n - 1)
            val out = ArrayList<FloatArray>(n)
            out += pts[0]
            var acc = 0.0
            var i = 1
            var prev = pts[0]
            while (out.size < n - 1 && i < pts.size) {
                val cur = pts[i]
                val seg = hypot((cur[0] - prev[0]).toDouble(), (cur[1] - prev[1]).toDouble())
                if (acc + seg >= step && seg > 0) {
                    val t = ((step - acc) / seg).toFloat()
                    val q = floatArrayOf(prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1]))
                    out += q
                    prev = q
                    acc = 0.0
                } else {
                    acc += seg
                    prev = cur
                    i++
                }
            }
            while (out.size < n) out += pts.last()
            return out
        }
    }
}
