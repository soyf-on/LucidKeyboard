package dev.lucid.keyboard.core.eval

import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.SpatialModelData
import dev.lucid.keyboard.core.touch.TouchDecoder
import dev.lucid.keyboard.core.touch.TouchPoint

/** One recorded tap: where the finger landed and which character was intended. */
data class RecordedTap(val intended: Char, val point: TouchPoint)

/** Tap-level outcome counts for one decoder configuration. */
data class TapStats(
    var taps: Int = 0,
    var errors: Int = 0,
    /** Geometry alone was right, but this decoder chose something else. */
    var unwantedSubstitutions: Int = 0,
    /** Geometry alone was wrong, and this decoder got it right. */
    var rescues: Int = 0,
) {
    val cer get() = if (taps == 0) 0.0 else errors.toDouble() / taps
    operator fun plusAssign(o: TapStats) { taps += o.taps; errors += o.errors; unwantedSubstitutions += o.unwantedSubstitutions; rescues += o.rescues }
}

/**
 * Replays the *same* recorded touches through a decoder configuration and scores
 * each tap against the intended character. Words are separated by ' ' taps; the
 * decoder's context evolves from its own decisions, exactly as when typing.
 */
object Replay {
    fun run(
        lm: LanguageModel,
        layout: KeyboardLayout,
        taps: List<RecordedTap>,
        settings: DecoderSettings,
        spatial: SpatialModelData = SpatialModelData(),
    ): TapStats {
        val dec = TouchDecoder(lm, SpatialModel(spatial))
        dec.settings = settings
        dec.beginWord()
        val st = TapStats()
        for (t in taps) {
            val d = dec.decide(layout, t.point.x, t.point.y)
            val got = if (d.key.kind == KeyKind.SPACE) ' ' else d.key.char
            val geo = d.geometricKey?.let { if (it.kind == KeyKind.SPACE) ' ' else it.char }
            val want = t.intended.lowercaseChar()
            st.taps++
            if (got != want) st.errors++
            if (geo == want && got != want) st.unwantedSubstitutions++
            if (geo != want && got == want) st.rescues++
            if (d.key.isDecodable) dec.commitTap(layout, t.point.x, t.point.y, d.key) else dec.beginWord()
        }
        return st
    }

    val FIXED = DecoderSettings(adaptive = false)
}
