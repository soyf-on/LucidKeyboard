package dev.lucid.keyboard.core.touch

import dev.lucid.keyboard.core.geometry.Key
import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.Alphabet
import dev.lucid.keyboard.core.lm.LanguageModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max


data class DecoderSettings(
    /** Master switch for adaptive targets. Off = purely the visible key under the finger. */
    val adaptive: Boolean = true,
    /** 0..1 — scales how strongly the language prior can move boundaries. */
    val strength: Double = 0.7,
    /** Use the personal touch-offset model (learned or calibrated). */
    val personalOffsets: Boolean = true,
)

/** A tap position in layout pixels. */
data class TouchPoint(val x: Float, val y: Float)

data class TapDecision(
    val key: Key,
    /** What the visible geometry alone says (the fixed-target baseline). */
    val geometricKey: Key?,
    /** Posterior probability of [key] among the candidates, 0..1. */
    val posterior: Double,
    /** The tap was in the key's protected centre, so the prior was not consulted. */
    val anchored: Boolean,
    /** Confidence of the language prior used for this tap (0 = none). */
    val priorConfidence: Double,
    /** Candidate keys with posteriors, best first (for the developer overlay). */
    val candidates: List<Pair<Key, Double>>,
) {
    val overrodeGeometry get() = geometricKey != null && key !== geometricKey
}

/**
 * Context-sensitive tap decoder.
 *
 * For a tap at x the decoder picks
 *
 *     k* = argmax_k  log p(x | k) + λ · log P̃(k | context)
 *
 * where p(x | k) is the key's 2-D Gaussian ([SpatialModel]) and P̃ is the language
 * prior clamped to a maximum log-ratio [MAX_LOG_RATIO] between any two candidates.
 * λ = strength × prior confidence. Taps inside a key's anchor core (the central
 * [ANCHOR] × [ANCHOR] pitch box) always return that key.
 *
 * With isotropic σ and neighbours d apart, the boundary between keys i and j moves
 * from the midpoint toward the less likely key by σ²·λ·ln(P̃ᵢ/P̃ⱼ)/d, which the clamp
 * bounds by σ²·λ·[MAX_LOG_RATIO]/d. Two hard limits hold regardless of the model:
 * a tap in a key's anchor core is always that key, and no key's effective area
 * extends more than [MAX_REACH] pitch beyond its visible cell.
 *
 * The decoder is deterministic and side-effect free per tap; [commit] advances the
 * word-level beam after a decision, so predictions are recomputed *between* taps.
 */
class TouchDecoder(
    private val lm: LanguageModel,
    var spatial: SpatialModel,
) {
    var settings = DecoderSettings()

    private class Hyp(val prefix: LanguageModel.Prefix, val logP: Double)

    private var beam: List<Hyp> = listOf(Hyp(lm.root(), 0.0))
    /** False when we don't know what precedes the cursor: use geometry + offsets only. */
    private var contextKnown = true

    /** Cached prior for the *next* tap: recomputed only when the beam changes. */
    private val prior = DoubleArray(Alphabet.SIZE)
    private var priorConfidence = 0.0
    private var priorValid = false

    fun beginWord() {
        beam = listOf(Hyp(lm.root(), 0.0)); contextKnown = true; priorValid = false
    }

    /** Called when the cursor lands mid-word or text before it can't be read. */
    fun beginUnknownContext() {
        beam = emptyList(); contextKnown = false; priorValid = false
    }

    /** Restarts the beam from literal text (e.g. after backspacing into a word). */
    fun resumeFrom(text: CharSequence) {
        beginWord()
        for (c in text) commitLiteral(c)
    }

    val beamSize get() = beam.size
    fun bestPrefix(): String? = beam.firstOrNull()?.prefix?.text

    // ------------------------------------------------------------------------------

    private fun ensurePrior() {
        if (priorValid) return
        prior.fill(0.0)
        if (!contextKnown || beam.isEmpty()) {
            priorConfidence = 0.0
        } else {
            val tmp = DoubleArray(Alphabet.SIZE)
            var conf = 0.0
            for (h in beam) {
                val w = exp(h.logP)
                conf += w * lm.distribution(h.prefix, tmp)
                for (i in 0 until Alphabet.SIZE) prior[i] += w * tmp[i]
            }
            priorConfidence = conf
        }
        priorValid = true
    }

    /** Language prior for the next tap (copy) and its confidence; for overlays/tests. */
    fun nextPrior(): Pair<DoubleArray, Double> { ensurePrior(); return prior.copyOf() to priorConfidence }

    private fun symbolOf(k: Key): Int = if (k.kind == KeyKind.SPACE) Alphabet.END else Alphabet.index(k.char)

    /**
     * Normalised offset of point from the key's aim point. For the space bar the aim
     * point is the nearest point of a horizontal segment inset by half a key, so the
     * long bar is not penalised for its width.
     */
    private fun offset(layout: KeyboardLayout, k: Key, x: Float, y: Float): Pair<Double, Double> {
        val dxPx = if (k.kind == KeyKind.SPACE) {
            val half = layout.unitW / 2f
            val left = k.x + half; val right = k.x + k.w - half
            if (x < left) x - left else if (x > right) x - right else 0f
        } else x - k.cx
        return (dxPx / layout.unitW).toDouble() to ((y - k.cy) / layout.unitH).toDouble()
    }

    private fun logLikelihood(layout: KeyboardLayout, k: Key, x: Float, y: Float): Double {
        val (dx, dy) = offset(layout, k, x, y)
        val g = if (settings.personalOffsets && k.kind == KeyKind.LETTER) spatial.gaussian(k.output)
        else KeyGaussian(0.0, 0.0, SpatialModel.DEFAULT_SX, SpatialModel.DEFAULT_SY)
        val zx = (dx - g.mx) / g.sx
        val zy = (dy - g.my) / g.sy
        return -0.5 * (zx * zx + zy * zy) - ln(g.sx * g.sy)
    }

    fun isInAnchor(layout: KeyboardLayout, k: Key, x: Float, y: Float): Boolean {
        val (dx, dy) = offset(layout, k, x, y)
        return abs(dx) <= ANCHOR / 2 && abs(dy) <= ANCHOR / 2
    }

    private fun candidates(layout: KeyboardLayout, x: Float, y: Float): List<Key> =
        layout.decodableKeys.filter { it.distanceToHit(x, y) <= CANDIDATE_RADIUS * layout.unitW }

    /** Decides the key for a tap. Pure: does not change decoder state. */
    fun decide(layout: KeyboardLayout, x: Float, y: Float): TapDecision {
        val geo = layout.geometricKeyAt(x, y)
        if (geo == null || !geo.isDecodable || !settings.adaptive) {
            return TapDecision(geo ?: layout.keys.first(), geo, 1.0, false, 0.0, emptyList())
        }
        val cands = candidates(layout, x, y).ifEmpty { listOf(geo) }
        ensurePrior()
        val lambda = settings.strength.coerceIn(0.0, 1.0) * priorConfidence
        val lp = DoubleArray(cands.size) { i ->
            val s = symbolOf(cands[i])
            if (s < 0 || priorConfidence == 0.0) 0.0 else ln(max(prior[s], 1e-12))
        }
        val maxLp = lp.maxOrNull() ?: 0.0
        val scores = DoubleArray(cands.size) { i ->
            logLikelihood(layout, cands[i], x, y) + lambda * max(lp[i], maxLp - MAX_LOG_RATIO)
        }
        val post = softmax(scores)
        val order = cands.indices.sortedByDescending { scores[it] }
        val ranked = order.map { cands[it] to post[it] }
        val anchored = isInAnchor(layout, geo, x, y)
        val best = cands[order.first()]
        // Hard limit on expansion: a key may claim at most MAX_REACH pitch beyond its
        // visible cell on each axis, whatever the prior and learned offsets say.
        val chosen = if (anchored || reachOf(layout, best, x, y) > MAX_REACH) geo else best
        return TapDecision(chosen, geo, post[cands.indexOf(chosen)], anchored, priorConfidence, ranked)
    }

    /** How far (in pitches, worst axis) a point lies outside a key's visible cell. */
    fun reachOf(layout: KeyboardLayout, k: Key, x: Float, y: Float): Double {
        val ox = max(0f, max(k.hitLeft - x, x - k.hitRight)) / layout.unitW
        val oy = max(0f, max(k.hitTop - y, y - k.hitBottom)) / layout.unitH
        return max(ox, oy).toDouble()
    }

    /** Effective key at a point under the current prior — used for the region overlay. */
    fun effectiveKeyAt(layout: KeyboardLayout, x: Float, y: Float): Key = decide(layout, x, y).key

    /**
     * Advances the word beam with the tap's full spatial evidence (not just the chosen
     * key), so alternative spellings of earlier taps keep informing later priors.
     */
    fun commitTap(layout: KeyboardLayout, x: Float, y: Float, decided: Key) {
        priorValid = false
        if (decided.kind != KeyKind.LETTER) { if (decided.kind == KeyKind.SPACE) beginWord(); return }
        if (!contextKnown) return
        val cands = candidates(layout, x, y).filter { it.kind == KeyKind.LETTER }.ifEmpty { listOf(decided) }
        val ll = cands.map { logLikelihood(layout, it, x, y) }
        val llMax = ll.max()
        val next = ArrayList<Hyp>()
        val tmp = DoubleArray(Alphabet.SIZE)
        for (h in beam) {
            lm.distribution(h.prefix, tmp)
            for ((i, k) in cands.withIndex()) {
                if (ll[i] < llMax - BEAM_SPATIAL_CUTOFF) continue
                val s = Alphabet.index(k.char)
                next += Hyp(lm.extend(h.prefix, k.char), h.logP + (ll[i] - llMax) + ln(max(tmp[s], 1e-12)))
            }
        }
        beam = normalize(next)
    }

    /** Advances the beam with a character that did not come from a spatial tap. */
    fun commitLiteral(c: Char) {
        priorValid = false
        if (!contextKnown) return
        beam = normalize(beam.map { Hyp(lm.extend(it.prefix, c), it.logP) })
    }

    /** Removes the last symbol by replaying: callers pass the remaining taps. */
    fun replay(layout: KeyboardLayout?, events: List<ReplayEvent>) {
        beginWord()
        for (e in events) {
            if (e.point != null && layout != null) {
                val k = layout.letter(e.char) ?: run { commitLiteral(e.char); null }
                if (k != null) commitTap(layout, e.point.x, e.point.y, k)
            } else commitLiteral(e.char)
        }
    }

    data class ReplayEvent(val char: Char, val point: TouchPoint?)

    private fun normalize(h: List<Hyp>): List<Hyp> {
        if (h.isEmpty()) return h
        val top = h.sortedByDescending { it.logP }.take(BEAM_WIDTH)
        val m = top.first().logP
        val z = m + ln(top.sumOf { exp(it.logP - m) })
        return top.map { Hyp(it.prefix, it.logP - z) }
    }

    companion object {
        /** Side of the protected centre box, in key pitches (0.5 = central quarter of the cell). */
        const val ANCHOR = 0.5
        /** Max log-ratio of prior between two candidates (e^3 ≈ 20×). */
        const val MAX_LOG_RATIO = 3.0
        /** Max expansion beyond the visible cell, in pitches (see [reachOf]). */
        const val MAX_REACH = 0.25
        const val CANDIDATE_RADIUS = 0.9
        const val BEAM_WIDTH = 8
        const val BEAM_SPATIAL_CUTOFF = 8.0

        fun softmax(s: DoubleArray): DoubleArray {
            val m = s.maxOrNull() ?: 0.0
            val e = DoubleArray(s.size) { exp(s[it] - m) }
            val z = e.sum()
            for (i in e.indices) e[i] /= z
            return e
        }
    }
}

