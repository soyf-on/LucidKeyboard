package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.TypingSettings
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** English + Dutch typed in the same session, without switching languages. */
class MultilingualTest {
    private val both = listOf("en", "nl")

    private fun engine(mode: CorrectionMode = CorrectionMode.BALANCED) =
        Fixtures.engine(langs = both).also { it.logic.settings = TypingSettings(correction = mode); it.logic.startInput(FieldInfo.DEFAULT) }

    @Test fun `plain Dutch is left alone`() {
        val e = engine()
        e.typeCentered("ik heb een nieuwe fiets gekocht en het was echt gezellig ")
        assertEquals("Ik heb een nieuwe fiets gekocht en het was echt gezellig ", e.editor.text)
    }

    @Test fun `plain English is left alone with Dutch enabled`() {
        val e = engine()
        e.typeCentered("i think the meeting went well and we should do this again ")
        assertEquals("I think the meeting went well and we should do this again ", e.editor.text)
    }

    @Test fun `mixed sentences are left alone`() {
        val e = engine()
        e.typeCentered("that meeting was echt gezellig maar de deadline is tomorrow ")
        assertEquals("That meeting was echt gezellig maar de deadline is tomorrow ", e.editor.text)
    }

    @Test fun `Dutch typos are fixed`() {
        val e = engine()
        e.typeCentered("het was gezelig ")
        assertEquals("Het was gezellig ", e.editor.text)
    }

    @Test fun `English typos are still fixed with Dutch enabled`() {
        val e = engine()
        e.typeCentered("i like teh keybaord ")
        assertEquals("I like the keyboard ", e.editor.text)
    }

    @Test fun `accents are restored from the dictionary`() {
        val e = engine()
        e.typeCentered("goede ideeen ")
        assertEquals("Goede ideeën ", e.editor.text)
    }

    @Test fun `Dutch IJ is capitalised together at sentence start`() {
        val e = engine()
        e.typeCentered("ijs is lekker ")
        assertEquals("IJs is lekker ", e.editor.text)
    }

    @Test fun `language estimate follows the text`() {
        val e = engine()
        e.typeCentered("ik ben niet thuis want ik moet werken ")
        assertTrue("nl=${e.lm.weightOf("nl")}", e.lm.weightOf("nl") > 0.7)
        e.typeCentered("but tomorrow i will be home all day ")
        assertTrue("en=${e.lm.weightOf("en")}", e.lm.weightOf("en") > 0.7)
    }

    @Test fun `English contractions are not forced on Dutch text`() {
        val e = engine()
        e.typeCentered("ik ben er morgen niet want ik heb ")
        // "hes" is not Dutch, but with Dutch dominant the English contraction table is off.
        val before = e.lm.weightOf("nl")
        assertTrue(before > 0.6)
        assertTrue(e.lm.replacementFor("dont") == null)
    }

    @Test fun `next-letter prior adapts to Dutch after Dutch words`() {
        val lm = Fixtures.lm(langs = both)
        repeat(4) { lm.observeWord("gezellig"); lm.observeWord("niet") }
        val d = TouchDecoder(lm, SpatialModel()).also { it.settings = DecoderSettings(strength = 1.0) }
        d.beginWord()
        "gezel".forEach { c -> val k = Fixtures.layout.letter(c)!!; d.commitTap(Fixtures.layout, k.cx, k.cy, k) }
        val (prior, _) = d.nextPrior()
        assertTrue(prior['l' - 'a'] > 0.5)
    }
}
