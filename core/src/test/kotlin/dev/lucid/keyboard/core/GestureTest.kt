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

    @Test fun `swiped words decode in English and Dutch`() {
        val dec = GestureDecoder(Fixtures.lm(langs = listOf("en", "nl")))
        val rng = Random(5)
        val words = listOf("hello", "keyboard", "tomorrow", "because", "gezellig", "morgen", "the", "thanks", "fiets", "werken")
        var hits = 0
        for (w in words) repeat(5) {
            val r = dec.decode(layout, swipe(w, rng), null)
            if (r.firstOrNull()?.word?.lowercase() == w) hits++ else println("MISS $w -> ${r.take(3).map { "${it.word}:${"%.2f".format(it.distance)}:${"%.1f".format(it.score)}" }}")
        }
        println("gesture top-1: $hits / ${words.size * 5}")
        assertTrue("top-1 hits $hits", hits >= words.size * 5 * 0.8)
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
        val path = swipe("keyboard", Random(2))
        repeat(3) { dec.decode(layout, path, null) }
        val t0 = System.nanoTime()
        repeat(10) { dec.decode(layout, path, null) }
        val ms = (System.nanoTime() - t0) / 1e6 / 10
        println("gesture decode: %.1f ms (desktop JVM)".format(ms))
        assertTrue(ms < 80)
    }
}
