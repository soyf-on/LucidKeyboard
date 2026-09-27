package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.TouchDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TouchDecoderTest {
    private val layout = Fixtures.layout

    private fun decoderAfter(prefix: String, strength: Double = 1.0): TouchDecoder {
        val d = TouchDecoder(Fixtures.lm(), dev.lucid.keyboard.core.touch.SpatialModel())
        d.settings = DecoderSettings(adaptive = true, strength = strength)
        d.beginWord()
        for (c in prefix) { val k = layout.letter(c)!!; d.commitTap(layout, k.cx, k.cy, k) }
        return d
    }

    @Test fun `likely next letter wins an ambiguous boundary tap`() {
        val d = decoderAfter("keyboar")
        val dKey = layout.letter('d')!!; val sKey = layout.letter('s')!!
        // 56% of the way from d's centre toward s: visibly just inside 's'.
        val x = dKey.cx + (sKey.cx - dKey.cx) * 0.56f
        val decision = d.decide(layout, x, dKey.cy)
        assertEquals('s', decision.geometricKey!!.char)
        assertEquals('d', decision.key.char)
        assertTrue(decision.overrodeGeometry)
    }

    @Test fun `deliberate centre tap on an unlikely letter is always honoured`() {
        val d = decoderAfter("keyboar")
        for (c in "sfexcq") {
            val k = layout.letter(c)!!
            assertEquals(c, d.decide(layout, k.cx, k.cy).key.char)
        }
    }

    @Test fun `anchor guarantee holds for every key under many contexts`() {
        val rng = Random(7)
        val contexts = listOf("", "th", "keyboar", "qu", "xylophon", "zzz", "abcdefg", "the", "n", "tomorro")
        for (ctx in contexts) {
            val d = decoderAfter(ctx)
            for (k in layout.decodableKeys) repeat(40) {
                // Uniform over the anchor box (±0.25 pitch around the aim point).
                val ax = if (k.kind == KeyKind.SPACE) k.cx + ((rng.nextFloat() - 0.5f) * (k.w - layout.unitW)) else k.cx
                val x = ax + (rng.nextFloat() - 0.5f) * 0.5f * layout.unitW * 0.999f
                val y = k.cy + (rng.nextFloat() - 0.5f) * 0.5f * layout.unitH * 0.999f
                assertEquals("ctx='$ctx' key=${k.label}", k, d.decide(layout, x, y).key)
            }
        }
    }

    @Test fun `effective regions never extend more than a quarter pitch past the visible key`() {
        val contexts = listOf("", "keyboar", "q", "th", "xylophon")
        for (ctx in contexts) {
            val d = decoderAfter(ctx)
            var y = 0f
            while (y < layout.height) {
                var x = 0f
                while (x < layout.width) {
                    val dec = d.decide(layout, x, y)
                    if (dec.overrodeGeometry) {
                        val reach = d.reachOf(layout, dec.key, x, y)
                        assertTrue("ctx=$ctx at ($x,$y) ${dec.key} reach=$reach", reach <= TouchDecoder.MAX_REACH + 1e-6)
                    }
                    x += 6f
                }
                y += 6f
            }
        }
    }

    @Test fun `adaptive off is exactly the visible geometry`() {
        val d = decoderAfter("keyboar")
        d.settings = DecoderSettings(adaptive = false)
        val rng = Random(3)
        repeat(2000) {
            val x = rng.nextFloat() * layout.width; val y = rng.nextFloat() * layout.height
            assertEquals(layout.geometricKeyAt(x, y), d.decide(layout, x, y).key)
        }
    }

    @Test fun `unknown context falls back to spatial only`() {
        val d = decoderAfter("keyboar")
        d.beginUnknownContext()
        val (_, conf) = d.nextPrior()
        assertEquals(0.0, conf, 0.0)
        val dKey = layout.letter('d')!!; val sKey = layout.letter('s')!!
        val x = dKey.cx + (sKey.cx - dKey.cx) * 0.56f
        assertEquals('s', d.decide(layout, x, dKey.cy).key.char)
    }

    @Test fun `out of vocabulary prefix lowers prior confidence`() {
        val inVocab = decoderAfter("keyboar").nextPrior().second
        val oov = decoderAfter("zxqvj").nextPrior().second
        assertTrue("in=$inVocab oov=$oov", inVocab > 0.8 && oov < 0.5)
    }

    @Test fun `decision is pure until committed`() {
        val d = decoderAfter("th")
        val e = layout.letter('e')!!
        val a = d.decide(layout, e.cx + 40, e.cy + 30)
        val b = d.decide(layout, e.cx + 40, e.cy + 30)
        assertEquals(a.key, b.key); assertEquals(a.posterior, b.posterior, 0.0)
    }

    @Test fun `beam keeps alternatives so an early boundary miss still informs later priors`() {
        // "keyboar" where the 'e' tap landed on the e/r boundary (decoded as r).
        val d = TouchDecoder(Fixtures.lm(), dev.lucid.keyboard.core.touch.SpatialModel())
        d.settings = DecoderSettings(strength = 1.0)
        d.beginWord()
        fun tap(c: Char) { val k = layout.letter(c)!!; d.commitTap(layout, k.cx, k.cy, k) }
        tap('k')
        val e = layout.letter('e')!!; val r = layout.letter('r')!!
        d.commitTap(layout, (e.cx + r.cx) / 2 + 4, e.cy, r)
        "yboar".forEach { tap(it) }
        val (prior, _) = d.nextPrior()
        val di = 'd' - 'a'; val si = 's' - 'a'
        assertTrue("P(d)=${prior[di]} P(s)=${prior[si]}", prior[di] > 5 * prior[si])
    }
}
