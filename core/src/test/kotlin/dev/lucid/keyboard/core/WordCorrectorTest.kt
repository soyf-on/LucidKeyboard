package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.correct.CorrectionMode.BALANCED
import dev.lucid.keyboard.core.correct.CorrectionMode.GENTLE
import dev.lucid.keyboard.core.correct.CorrectionMode.OFF
import dev.lucid.keyboard.core.correct.CorrectionMode.STRONG
import dev.lucid.keyboard.core.correct.ShiftSource
import dev.lucid.keyboard.core.correct.TypedChar
import dev.lucid.keyboard.core.correct.WordCorrector
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCorrectorTest {
    private val layout = Fixtures.layout

    /** Taps at key centres (a "clean" typist) for each character. */
    private fun centred(word: String, firstShift: ShiftSource = ShiftSource.NONE) = word.mapIndexed { i, c ->
        val k = layout.letter(c)
        TypedChar(c, k?.let { TouchPoint(it.cx, it.cy) }, if (i == 0) firstShift else if (c.isUpperCase()) ShiftSource.MANUAL else ShiftSource.NONE)
    }

    private fun corrector(user: UserVocabulary = UserVocabulary()) = WordCorrector(Fixtures.lm(user), SpatialModel())

    private fun fix(word: String, mode: CorrectionMode, user: UserVocabulary = UserVocabulary(), sentenceStart: Boolean = false, first: ShiftSource = ShiftSource.NONE) =
        corrector(user).correct(layout, centred(word, first), null, mode, sentenceStart).autoCorrection

    @Test fun `classic typos are fixed in balanced`() {
        assertEquals("the", fix("teh", BALANCED))
        assertEquals("their", fix("thier", BALANCED))
        assertEquals("keyboard", fix("keybaord", BALANCED))
        assertEquals("definitely", fix("definately", BALANCED))
        assertEquals("because", fix("becuase", BALANCED))
    }

    @Test fun `neighbouring key slip is fixed`() {
        // "hwllo": w is next to e.
        assertEquals("hello", fix("hwllo", BALANCED))
    }

    @Test fun `valid words are never replaced in gentle or balanced`() {
        for (w in listOf("form", "from", "then", "than", "ship", "shop", "duck", "sus", "ngl", "yeet", "lol", "fuck", "shit", "gonna"))
            for (m in listOf(GENTLE, BALANCED)) assertNull("$w/$m", fix(w, m))
    }

    @Test fun `off mode changes nothing`() {
        assertNull(fix("teh", OFF))
        assertNull(fix("dont", OFF))
    }

    @Test fun `gentle is stricter than balanced`() {
        // Two-edit repair: allowed in balanced, not gentle (max one edit).
        val two = "recieev"
        val g = fix(two, GENTLE); val b = fix(two, BALANCED)
        assertNull(g)
        assertEquals("receive", b)
    }

    @Test fun `contractions follow mode`() {
        assertNull(fix("dont", GENTLE))
        assertEquals("don't", fix("dont", BALANCED))
        assertEquals("I'm", fix("im", BALANCED))
        assertNull(fix("cant", BALANCED))
        assertEquals("can't", fix("cant", STRONG))
    }

    @Test fun `pronoun i is capitalised`() {
        assertEquals("I", fix("i", GENTLE))
    }

    @Test fun `manually capitalised unknown word mid-sentence is treated as a name`() {
        assertNull(fix("Siobhab", BALANCED, first = ShiftSource.MANUAL))
        assertNull(fix("Kwame", BALANCED, first = ShiftSource.MANUAL))
    }

    @Test fun `sentence-start capital still gets corrected and keeps case`() {
        assertEquals("The", fix("Teh", BALANCED, sentenceStart = true, first = ShiftSource.AUTO))
    }

    @Test fun `acronyms and mixed alphanumerics are left alone`() {
        assertNull(fix("NASDA", STRONG, first = ShiftSource.MANUAL))
        val typed = listOf(TypedChar('b'), TypedChar('2'), TypedChar('b'))
        assertNull(corrector().correct(layout, typed, null, STRONG, false).autoCorrection)
        val covid = "covid19".map { TypedChar(it, layout.letter(it)?.let { k -> TouchPoint(k.cx, k.cy) }) }
        assertNull(corrector().correct(layout, covid, null, STRONG, false).autoCorrection)
    }

    @Test fun `learned and never-correct words are protected immediately`() {
        val u = UserVocabulary()
        assertEquals("keyboard", fix("keybaord", BALANCED, u))
        u.learnExplicit("keybaord")
        assertNull(fix("keybaord", STRONG, u))
        val v = UserVocabulary(); v.setNeverCorrect("teh")
        assertNull(fix("teh", STRONG, v))
    }

    @Test fun `rejected correction is not re-applied`() {
        val u = UserVocabulary()
        u.rejectCorrection("teh", "the")
        val r = corrector(u).correct(layout, centred("teh"), null, BALANCED, false)
        assertTrue(r.autoCorrection != "the")
    }

    @Test fun `personal replacement applies`() {
        val u = UserVocabulary(); u.setReplacement("omw", "On my way!")
        assertEquals("On my way!", fix("omw", GENTLE, u))
    }

    @Test fun `blocked word is never suggested or used`() {
        val u = UserVocabulary(); u.block("the")
        val r = corrector(u).correct(layout, centred("teh"), null, STRONG, false)
        assertTrue(r.autoCorrection?.lowercase() != "the")
        assertTrue("the" !in r.suggestions.map { it.lowercase() })
    }

    @Test fun `profanity is suggested without sanitising`() {
        val r = corrector().correct(layout, centred("fucl"), null, BALANCED, false)
        assertTrue(r.suggestions.toString(), r.suggestions.any { it.startsWith("fuck") } || r.autoCorrection == "fuck")
    }

    @Test fun `personal words are candidates for correction`() {
        val u = UserVocabulary(); u.learnExplicit("Zyzzyva")
        val r = corrector(u).correct(layout, centred("zyzzuva"), null, BALANCED, false)
        assertEquals("Zyzzyva", r.autoCorrection)
    }

    @Test fun `completions offer common words for a prefix`() {
        val c = corrector().completions("keyb")
        assertTrue(c.toString(), c.contains("keyboard"))
    }

    @Test fun `correction search is fast`() {
        val c = corrector()
        val words = listOf("teh", "keybaord", "definately", "becuase", "hwllo", "internationalizaton", "abcdefghij", "thier")
        repeat(3) { words.forEach { c.correct(layout, centred(it), null, BALANCED, false) } } // warm-up
        val t0 = System.nanoTime()
        val n = 20
        repeat(n) { words.forEach { c.correct(layout, centred(it), null, BALANCED, false) } }
        val ms = (System.nanoTime() - t0) / 1e6 / (n * words.size)
        println("correct(): %.2f ms per word (desktop JVM)".format(ms))
        assertTrue("too slow: $ms ms", ms < 25.0)
    }
}
