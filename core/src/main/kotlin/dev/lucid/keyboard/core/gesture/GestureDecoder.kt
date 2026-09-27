package dev.lucid.keyboard.core.gesture

import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.Alphabet
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.lm.Lexicon
import dev.lucid.keyboard.core.touch.TouchPoint
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Slide-to-type decoder, after SHARK² (Kristensson & Zhai 2004) and later work on how
 * real gesture paths look (they cut corners, vary speed, and miss the middle letters).
 *
 * Each candidate word's template is the polyline through its key centres. A swipe is
 * scored against it with several channels plus the language model:
 *  - location: average closest distance between the two paths, both ways — tolerant of
 *    speed changes and corner cutting;
 *  - shape: the two paths normalised for position and size and compared point by point in
 *    order — this is what separates "top" from "pot";
 *  - coverage: how far the path stays from each of the word's letters (beyond a tolerance);
 *  - length: a long swipe should not match a short word (and vice versa);
 *  - start/end: people start and finish closer to the first and last letter.
 * Candidates are words whose first and last letters are near the path's ends, in every
 * active language, plus the user's own words.
 */
class GestureDecoder(private val lm: LanguageModel) {

    data class Result(val word: String, val score: Double, val distance: Double)

    /**
     * Weights of the scoring channels (log-likelihood scale), fitted on realistic simulated
     * swipes (see GestureFitTest). Order matches [features].
     */
    data class Weights(val w: DoubleArray = FITTED) {
        override fun equals(other: Any?) = other is Weights && w.contentEquals(other.w)
        override fun hashCode() = w.contentHashCode()
    }
    var weights = Weights()

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

    /** The user's swipe, preprocessed once. */
    private class UserPath(val pts: List<FloatArray>, val norm: List<FloatArray>, val length: Double)

    fun decode(layout: KeyboardLayout, path: List<TouchPoint>, prev: String?, limit: Int = 5): List<Result> {
        val best = HashMap<String, Result>()
        val w = weights.w
        forEachCandidate(layout, path, prev) { key, f ->
            var total = 0.0
            for (i in f.indices) total += w[i] * f[i]
            val old = best[key]
            if (old == null || old.score < total) best[key] = Result(lm.casedForm(key), total, sqrt(-f[0]))
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    private fun forEachCandidate(layout: KeyboardLayout, path: List<TouchPoint>, prev: String?, block: (String, DoubleArray) -> Unit) {
        if (path.size < 2) return
        val ux = layout.unitW; val uy = layout.unitH
        val raw = path.map { floatArrayOf(it.x / ux, it.y / uy) }
        val pts = resample(raw, N)
        val user = UserPath(pts, normalize(pts), polyLength(raw))
        val centers = HashMap<Int, FloatArray>()
        for (k in layout.letterKeys) centers[Alphabet.index(k.char)] = floatArrayOf(k.cx / ux, k.cy / uy)
        fun nearKeys(pt: FloatArray): List<Int> = centers.entries
            .map { (s, c) -> s to hypot(c[0] - pt[0], c[1] - pt[1]) }
            .filter { it.second < END_RADIUS }.sortedBy { it.second }.take(END_KEYS).map { it.first }
        val starts = nearKeys(pts[0]); val ends = nearKeys(pts[N - 1])
        val seen = HashSet<String>()
        val w = weights.w
        // Pass 1 (cheap, O(N) per word): length, start/end and word likelihood.
        class Pre(val key: String, val template: List<FloatArray>, val tLen: Double, val lp: Double, val cheap: Double)
        val pre = ArrayList<Pre>()
        fun consider(word: String) {
            val key = word.lowercase()
            if (!seen.add(key)) return
            val template = templateOf(key, centers) ?: return
            val tLen = polyLength(template)
            val ratio = (user.length + 0.3) / (tLen + 0.3)
            if (ratio < 0.35 || ratio > 2.5) return
            val lp = lm.wordLogProb(key, prev) ?: return
            val first = template[0]; val last = template[template.size - 1]
            val ds = hypot((first[0] - pts[0][0]).toDouble(), (first[1] - pts[0][1]).toDouble())
            val de = hypot((last[0] - pts[N - 1][0]).toDouble(), (last[1] - pts[N - 1][1]).toDouble())
            val lr = lengthMismatch(user.length, tLen)
            pre += Pre(key, template, tLen, lp, -w[6] * lr * lr - w[7] * (ds * ds + de * de) + w[8] * lp)
        }
        for (pack in lm.packs) {
            val lex = pack.lexicon
            val idx = indexFor(lex)
            for (s in starts) for (e in ends) for (id in idx[s * 26 + e]) consider(lex.cased(id))
        }
        for (uw in lm.user.activeWords()) {
            val w = uw.word
            if (w.length < 2 || lm.inLexicon(w.lowercase())) continue
            if (Alphabet.index(w[0]) !in starts || Alphabet.index(w[w.length - 1]) !in ends) continue
            consider(w)
        }
        // Pass 2: full comparison for the shortlist only.
        pre.sortByDescending { it.cheap }
        for (c in pre.subList(0, minOf(SHORTLIST, pre.size))) {
            val f = features(user, c.template, c.tLen, c.lp) ?: continue
            block(c.key, f)
        }
    }

    /** Key-centre polyline for a word (double letters collapse; apostrophes are skipped). */
    private fun templateOf(word: String, centers: Map<Int, FloatArray>): List<FloatArray>? {
        val pts = ArrayList<FloatArray>(word.length)
        var last = -1
        for (c in word) {
            val s = Alphabet.index(c)
            if (s == Alphabet.APOS) continue
            if (s !in 0..25) return null
            if (s == last) continue
            pts += centers[s] ?: return null
            last = s
        }
        return pts.ifEmpty { null }
    }

    /**
     * Channel values for one candidate (all ≤ 0 except the language term):
     * 0 location (symmetric closest-distance)², 1 in-order point distance², 2 shape²,
     * 3/4/5 letter coverage beyond 0.3 / 0.42 / 0.55 key, 6 length mismatch², 7 start+end², 8 log P(word | prev).
     */
    private fun features(user: UserPath, template: List<FloatArray>, tLen: Double, lp: Double): DoubleArray? {
        val t = if (template.size == 1) List(N) { template[0] } else resample(template, N)
        var a = 0.0; var b = 0.0; var prop = 0.0
        for (i in 0 until N) {
            a += minDist(user.pts[i], t); b += minDist(t[i], user.pts)
            val dx = (t[i][0] - user.pts[i][0]).toDouble(); val dy = (t[i][1] - user.pts[i][1]).toDouble()
            prop += dx * dx + dy * dy
        }
        val loc = (a + b) / (2 * N)
        if (loc > MAX_LOCATION) return null
        prop /= N
        val c = DoubleArray(3)
        for (k in template) {
            val d = minDist(k, user.pts)
            for ((j, tol) in COVERAGE_TOL.withIndex()) { val e = d - tol; if (e > 0) c[j] += e * e }
        }
        val tn = normalize(t)
        var sh = 0.0
        for (i in 0 until N) sh += hypot((tn[i][0] - user.norm[i][0]).toDouble(), (tn[i][1] - user.norm[i][1]).toDouble())
        sh /= N
        val lr = lengthMismatch(user.length, tLen)
        val ds = hypot((t[0][0] - user.pts[0][0]).toDouble(), (t[0][1] - user.pts[0][1]).toDouble())
        val de = hypot((t[N - 1][0] - user.pts[N - 1][0]).toDouble(), (t[N - 1][1] - user.pts[N - 1][1]).toDouble())
        return doubleArrayOf(-loc * loc, -prop, -sh * sh, -c[0], -c[1], -c[2], -lr * lr, -(ds * ds + de * de), lp)
    }

    /** Candidates with their channel values (for fitting the weights in tests). */
    fun candidateFeatures(layout: KeyboardLayout, path: List<TouchPoint>, prev: String?): List<Pair<String, DoubleArray>> {
        val out = ArrayList<Pair<String, DoubleArray>>()
        forEachCandidate(layout, path, prev) { key, f -> out += key to f }
        return out
    }

    companion object {
        const val N = 32
        const val PER_BUCKET = 3000
        const val END_RADIUS = 1.35
        const val END_KEYS = 5
        const val MAX_LOCATION = 1.1
        val COVERAGE_TOL = doubleArrayOf(0.3, 0.42, 0.55)
        /** Fitted weights (see GestureFitTest); starting point = the previous decoder's behaviour. */
        val FITTED = doubleArrayOf(39.488, 2.900, 30.000, 0.000, 0.000, 0.000, 90.000, 7.800, 1.000)
        /** Candidates that survive the cheap first pass and get the full comparison. */
        const val SHORTLIST = 300

        /** log length ratio outside the expected band [0.7, 1.15] of the template length. */
        private fun lengthMismatch(user: Double, template: Double): Double {
            val r = ln((user + 0.3) / (template + 0.3))
            return when { r < ln(0.7) -> r - ln(0.7); r > ln(1.15) -> r - ln(1.15); else -> 0.0 }
        }

        private fun minDist(p: FloatArray, pts: List<FloatArray>): Double {
            var m = Double.MAX_VALUE
            for (q in pts) { val d = ((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])).toDouble(); if (d < m) m = d }
            return sqrt(m)
        }

        private fun polyLength(p: List<FloatArray>): Double {
            var s = 0.0
            for (i in 1 until p.size) s += hypot((p[i][0] - p[i - 1][0]).toDouble(), (p[i][1] - p[i - 1][1]).toDouble())
            return s
        }

        /** Translate to the centroid and scale so the larger bounding-box side is 1. */
        private fun normalize(p: List<FloatArray>): List<FloatArray> {
            var cx = 0f; var cy = 0f
            for (q in p) { cx += q[0]; cy += q[1] }
            cx /= p.size; cy /= p.size
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (q in p) { minX = min(minX, q[0]); maxX = max(maxX, q[0]); minY = min(minY, q[1]); maxY = max(maxY, q[1]) }
            val s = max(max(maxX - minX, maxY - minY), 0.5f)
            return p.map { floatArrayOf((it[0] - cx) / s, (it[1] - cy) / s) }
        }

        /** Resamples a polyline to [n] points equally spaced along its length. */
        fun resample(pts: List<FloatArray>, n: Int): List<FloatArray> {
            if (pts.size == 1) return List(n) { pts[0] }
            val total = polyLength(pts)
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
