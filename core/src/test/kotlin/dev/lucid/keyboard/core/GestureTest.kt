package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.gesture.GestureDecoder
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.TypingSettings
import dev.lucid.keyboard.core.touch.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GestureTest {
    private val layout = Fixtures.layout

    /** A human-ish swipe: through each key centre with wobble, sampled every few pixels. */
    private fun swipe(word: String, rng: Random, wobble: Double = 0.18): List<TouchPoint> {
        val keys = word.map { layout.letter(it)!! }
        val pts = ArrayList<TouchPoint>()
        val anchors = keys.map { k -> TouchPoint((k.cx + rng.nextGaussian() * wobble * layout.unitW).toFloat(), (k.cy + rng.nextGaussian() * wobble * layout.unitH).toFloat()) }
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]; val b = anchors[i + 1]
            for (t in 0 until 12) { val f = t / 12f; pts += TouchPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f) }
        }
        pts += anchors.last()
        return pts
    }

    @Test fun `realistic swipes decode in English and Dutch`() {
        // Human-like paths (curved, corner-cutting, sloppy middles); words not used for fitting weights.
        val dec = GestureDecoder(Fixtures.lm(langs = listOf("en", "nl")))
        for ((file, seed) in listOf("sentences.txt" to 1234L, "sentences_nl.txt" to 99L)) {
            val rng = Random(seed)
            val words = SwipeSim.words(file).shuffled(Random(seed)).take(80)
            var top1 = 0; var top3 = 0
            for (w in words) {
                val r = dec.decode(layout, SwipeSim.path(w, rng), null, 3).map { it.word.lowercase() }
                if (r.firstOrNull() == w) top1++; if (w in r) top3++
            }
            println("gesture $file top1 ${100 * top1 / words.size}% top3 ${100 * top3 / words.size}%")
            assertTrue("$file top1=$top1/${words.size}", top1 >= words.size * 0.68)
            assertTrue("$file top3=$top3/${words.size}", top3 >= words.size * 0.85)
        }
    }

    @Test fun `context resolves similar shapes`() {
        val dec = GestureDecoder(Fixtures.lm())
        val r = dec.decode(layout, swipe("you", Random(1), 0.05), "thank")
        assertEquals("you", r.first().word)
    }

    @Test fun `gesture words get auto spaces, alternatives and whole-word backspace`() {
        val e = Fixtures.engine()
        e.logic.settings = TypingSettings(correction = CorrectionMode.BALANCED)
        e.logic.startInput(FieldInfo.DEFAULT)
        e.logic.onGesture(listOf("hello", "jello"))
        e.logic.onGesture(listOf("world", "wield"))
        assertEquals("Hello world", e.editor.text)
        assertEquals(listOf("wield"), e.logic.stripState().suggestions)
        e.logic.onBackspace()
        assertEquals("Hello ", e.editor.text)
        e.logic.onGesture(listOf("there"))
        e.logic.onSeparator(".")
        assertEquals("Hello there.", e.editor.text)
    }

    @Test fun `decoding is fast enough`() {
        val dec = GestureDecoder(Fixtures.lm(langs = listOf("en", "nl")))
        dec.warmUp()
        val paths = listOf("keyboard", "because", "tomorrow", "the", "gezellig").map { SwipeSim.path(it, Random(2)) }
        repeat(3) { paths.forEach { dec.decode(layout, it, null) } }
        val t0 = System.nanoTime()
        repeat(4) { paths.forEach { dec.decode(layout, it, null) } }
        val ms = (System.nanoTime() - t0) / 1e6 / (4 * paths.size)
        println("gesture decode: %.1f ms (desktop JVM)".format(ms))
        assertTrue(ms < 60)
    }
}
