package dev.lucid.keyboard.core.touch

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Running weighted statistics of touch offsets for one key (or the global pool),
 * in key-normalised units: dx / key pitch (horizontal), dy / row pitch.
 */
@Serializable
data class OffsetStats(
    val n: Double = 0.0,
    val mx: Double = 0.0,
    val my: Double = 0.0,
    val m2x: Double = 0.0,
    val m2y: Double = 0.0,
) {
    val varX get() = if (n > 1) m2x / n else 0.0
    val varY get() = if (n > 1) m2y / n else 0.0

    /** Weighted Welford update with exponential forgetting once [cap] is reached. */
    fun add(dx: Double, dy: Double, w: Double, cap: Double): OffsetStats {
        // Forgetting keeps the model responsive if the user changes grip or device.
        val scale = if (n + w > cap) (cap - w) / n else 1.0
        val n0 = n * scale
        val nn = n0 + w
        val dX = dx - mx; val dY = dy - my
        val nmx = mx + dX * w / nn
        val nmy = my + dY * w / nn
        return OffsetStats(nn, nmx, nmy, m2x * scale + w * dX * (dx - nmx), m2y * scale + w * dY * (dy - nmy))
    }
}

@Serializable
data class SpatialModelData(
    val version: Int = 1,
    val global: OffsetStats = OffsetStats(),
    val perKey: Map<String, OffsetStats> = emptyMap(),
    val samples: Long = 0,
)

/** Gaussian touch distribution for one key, normalised units. */
data class KeyGaussian(val mx: Double, val my: Double, val sx: Double, val sy: Double)

/**
 * Hierarchical spatial backoff model (after Yin et al., CHI 2013):
 * per-key estimates are shrunk toward the user's global offset, which is shrunk
 * toward the population prior (no offset, default spread). A key with few samples
 * therefore behaves like the user's average key; with many samples it becomes
 * specific. Offsets and spreads are clamped so learning can never move a key's
 * centre of attraction far from where it is drawn.
 */
class SpatialModel(data: SpatialModelData = SpatialModelData()) {
    var data: SpatialModelData = data
        private set

    fun gaussian(keyId: String): KeyGaussian {
        val g = data.global
        val gw = g.n / (g.n + GLOBAL_PRIOR_N)
        val gmx = gw * g.mx
        val gmy = gw * g.my
        val gvx = blend(g.varX, DEFAULT_SX * DEFAULT_SX, g.n, GLOBAL_PRIOR_N)
        val gvy = blend(g.varY, DEFAULT_SY * DEFAULT_SY, g.n, GLOBAL_PRIOR_N)
        val k = data.perKey[keyId]
        if (k == null || k.n <= 0.0) return make(gmx, gmy, gvx, gvy)
        val kw = k.n / (k.n + KEY_PRIOR_N)
        return make(
            kw * k.mx + (1 - kw) * gmx,
            kw * k.my + (1 - kw) * gmy,
            blend(k.varX, gvx, k.n, KEY_PRIOR_N),
            blend(k.varY, gvy, k.n, KEY_PRIOR_N),
        )
    }

    private fun blend(sample: Double, prior: Double, n: Double, priorN: Double) =
        if (n <= 1) prior else (n * sample + priorN * prior) / (n + priorN)

    private fun make(mx: Double, my: Double, vx: Double, vy: Double) = KeyGaussian(
        mx.coerceIn(-MAX_OFFSET, MAX_OFFSET),
        my.coerceIn(-MAX_OFFSET, MAX_OFFSET),
        sqrt(vx).coerceIn(MIN_SIGMA, MAX_SIGMA),
        sqrt(vy).coerceIn(MIN_SIGMA, MAX_SIGMA),
    )

    /**
     * Adds a labelled sample. Returns false (and ignores it) when the tap is too far
     * from the labelled key to plausibly be an aim at it — this rejects mislabels.
     */
    fun learn(keyId: String, dx: Double, dy: Double, weight: Double = 1.0): Boolean {
        if (abs(dx) > OUTLIER || abs(dy) > OUTLIER || weight <= 0) return false
        val k = (data.perKey[keyId] ?: OffsetStats()).add(dx, dy, weight, KEY_CAP)
        data = data.copy(
            global = data.global.add(dx, dy, weight, GLOBAL_CAP),
            perKey = data.perKey + (keyId to k),
            samples = data.samples + 1,
        )
        return true
    }

    fun reset() { data = SpatialModelData() }

    companion object {
        /** Population prior spreads (fraction of key pitch). Typical thumb-typing SDs
         *  reported in the literature are ~1/5 to 1/4 of a key's width. */
        const val DEFAULT_SX = 0.24
        const val DEFAULT_SY = 0.22
        const val MIN_SIGMA = 0.15
        const val MAX_SIGMA = 0.36
        /** A learned mean can drift at most this far from the drawn centre. */
        const val MAX_OFFSET = 0.30
        /** Samples farther than this from the labelled key's centre are rejected. */
        const val OUTLIER = 0.85
        const val GLOBAL_PRIOR_N = 30.0
        const val KEY_PRIOR_N = 15.0
        const val GLOBAL_CAP = 600.0
        const val KEY_CAP = 150.0
    }
}
