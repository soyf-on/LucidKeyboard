package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.ShiftState
import dev.lucid.keyboard.core.input.TypingSettings
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.lm.WordSource
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InputLogicTest {

    private fun engine(user: UserVocabulary = UserVocabulary(), settings: TypingSettings = TypingSettings(correction = CorrectionMode.BALANCED)) =
        Fixtures.engine(user).also { it.logic.settings = settings; it.logic.startInput(FieldInfo.DEFAULT) }

    @Test fun `typing a word and space auto-corrects and capitalises sentence start`() {
        val e = engine()
        e.typeCentered("teh ")
        assertEquals("The ", e.editor.text)
    }

    @Test fun `backspace right after a correction reverts to the exact typed text`() {
        val e = engine()
        e.typeCentered("i like teh ")
        assertEquals("I like the ", e.editor.text)
        e.logic.onBackspace()
        assertEquals("I like teh ", e.editor.text)
        assertEquals("teh", e.logic.stripState().revertedWord)
        // A second backspace is an ordinary delete.
        e.logic.onBackspace()
        assertEquals("I like teh", e.editor.text)
    }

    @Test fun `reverted correction is not applied again, now or after restart`() {
        val user = UserVocabulary()
        val e = engine(user)
        e.typeCentered("teh ")
        e.logic.onBackspace()
        e.typeCentered("and teh ")
        assertEquals("teh and teh ", e.editor.text.lowercase())
        // Persist + reload.
        val reloaded = UserVocabulary.fromJson(user.toJson())
        val e2 = engine(reloaded)
        e2.typeCentered("so teh ")
        assertEquals("So teh ", e2.editor.text)
    }

    @Test fun `learn word from the strip works immediately and persists`() {
        val user = UserVocabulary()
        val e = engine(user)
        e.typeCentered("keybaord")
        assertEquals("Keyboard", e.logic.stripState().pendingCorrection)
        e.logic.learnWord("keybaord")
        assertNull(e.logic.stripState().pendingCorrection)
        e.logic.onSeparator(" ")
        assertEquals("Keybaord ", e.editor.text)
        val reloaded = UserVocabulary.fromJson(user.toJson())
        assertTrue(reloaded.isActive("keybaord"))
        assertEquals(WordSource.EXPLICIT, reloaded.entry("keybaord")!!.source)
    }

    @Test fun `repeated intentional use teaches a word, a single typo does not`() {
        val user = UserVocabulary()
        val e = engine(user, TypingSettings(correction = CorrectionMode.OFF))
        e.typeCentered("florp ")
        e.typeCentered("then ") // flushes learning of "florp"
        assertFalse(user.isActive("florp"))
        e.typeCentered("florp ")
        e.typeCentered("again ")
        assertTrue(user.isActive("florp"))
        assertEquals(WordSource.INFERRED, user.entry("florp")!!.source)
        // A typo that is immediately deleted never counts.
        e.typeCentered("qwzx")
        repeat(4) { e.logic.onBackspace() }
        e.typeCentered("ok ")
        e.typeCentered("qwzx")
        repeat(4) { e.logic.onBackspace() }
        e.typeCentered("ok ")
        assertNull(user.entry("qwzx"))
    }

    @Test fun `reverting a correction teaches the word in one go`() {
        val user = UserVocabulary()
        val e = engine(user)
        e.typeCentered("teh ")
        assertEquals("The ", e.editor.text)
        e.logic.onBackspace()
        assertFalse(user.isActive("teh"))
        e.typeCentered("daily ") // the next word confirms the reverted one was kept
        assertTrue(user.isActive("teh"))
    }

    @Test fun `private mode learns and retains nothing`() {
        val user = UserVocabulary()
        val spatial = SpatialModel()
        val e = Fixtures.Engine(Fixtures.lm(user), spatial)
        e.logic.settings = TypingSettings(privateMode = true)
        e.logic.startInput(FieldInfo.DEFAULT)
        e.typeCentered("teh ")
        e.logic.onBackspace() // revert
        e.typeCentered("florp florp florp the the the ")
        e.logic.finishInput()
        assertTrue(user.allWords().isEmpty())
        assertFalse(user.wasRejected("teh", "the"))
        assertEquals(0L, spatial.data.samples)
    }

    @Test fun `no-personalized-learning fields do not learn`() {
        val user = UserVocabulary()
        val e = Fixtures.engine(user)
        e.logic.startInput(FieldInfo(learningAllowed = false))
        e.typeCentered("florp florp florp ")
        e.logic.finishInput()
        assertTrue(user.allWords().isEmpty())
    }

    @Test fun `password fields never correct, compose or learn`() {
        val user = UserVocabulary()
        val e = Fixtures.engine(user)
        e.logic.startInput(FieldInfo.PASSWORD)
        e.typeCentered("teh")
        e.logic.onSeparator(" ")
        assertEquals("teh ", e.editor.text)
        assertEquals(-1, e.editor.composingStart)
        assertTrue(e.logic.stripState().literal.isEmpty())
        e.logic.finishInput()
        assertTrue(user.allWords().isEmpty())
    }

    @Test fun `url and email style fields keep literal text`() {
        val e = Fixtures.engine()
        e.logic.startInput(FieldInfo(literal = true, autoCapAllowed = false, learningAllowed = false))
        e.typeCentered("teh")
        e.logic.onText(".")
        e.typeCentered("com")
        assertEquals("teh.com", e.editor.text)
    }

    @Test fun `touch model learns only from confirmed words`() {
        val spatial = SpatialModel()
        val e = Fixtures.Engine(Fixtures.lm(), spatial)
        e.logic.settings = TypingSettings(correction = CorrectionMode.BALANCED)
        e.logic.startInput(FieldInfo.DEFAULT)
        e.typeCentered("hello there ")
        e.logic.finishInput()
        assertEquals(10L, spatial.data.samples) // h e l l o + t h e r e
        // A reverted correction must not produce samples.
        val s2 = SpatialModel()
        val e2 = Fixtures.Engine(Fixtures.lm(), s2)
        e2.logic.settings = TypingSettings(correction = CorrectionMode.BALANCED)
        e2.logic.startInput(FieldInfo.DEFAULT)
        e2.typeCentered("teh ")
        e2.logic.onBackspace()
        e2.logic.finishInput()
        assertEquals(0L, s2.data.samples)
        // Nonsense (unknown) words don't train it either.
        val s3 = SpatialModel()
        val e3 = Fixtures.Engine(Fixtures.lm(), s3)
        e3.logic.settings = TypingSettings(correction = CorrectionMode.OFF)
        e3.logic.startInput(FieldInfo.DEFAULT)
        e3.typeCentered("zxqv ")
        e3.logic.finishInput()
        assertEquals(0L, s3.data.samples)
    }

    @Test fun `picking the literal chip keeps exactly what was typed and remembers it`() {
        val user = UserVocabulary()
        val e = engine(user)
        e.typeCentered("teh")
        val st = e.logic.stripState()
        assertEquals("The", st.pendingCorrection)
        e.logic.onSuggestionPicked(st.literal, isLiteral = true)
        assertEquals("Teh ", e.editor.text)
        assertTrue(user.wasRejected("teh", "the"))
    }

    @Test fun `picking a suggestion then punctuation swaps the auto space`() {
        val e = engine()
        e.typeCentered("hel")
        e.logic.onSuggestionPicked("hello", isLiteral = false)
        e.logic.onText(",")
        assertEquals("hello, ", e.editor.text.lowercase())
    }

    @Test fun `double space inserts a period`() {
        val e = engine()
        e.typeCentered("ok")
        e.logic.onSeparator(" ", now = 1000)
        e.logic.onSeparator(" ", now = 1200)
        assertEquals("Ok. ", e.editor.text)
        assertEquals(ShiftState.AUTO, e.logic.shift)
    }

    @Test fun `backspace into the previous word resumes composing with its taps`() {
        val e = engine()
        e.typeCentered("hello wirld")
        e.logic.onSeparator(" ")
        val after = e.editor.text
        e.logic.onBackspace() // revert or delete space
        if (after.endsWith("world ")) e.logic.onBackspace()
        assertTrue(e.logic.isComposing || e.editor.text.endsWith("wirld"))
    }

    @Test fun `cursor moved by the user resets word state safely`() {
        val e = engine()
        e.typeCentered("hello wor")
        e.editor.moveCursor(2)
        e.logic.onExternalCursorMove()
        assertFalse(e.logic.isComposing)
        e.typeCentered("x")
        assertEquals("Hexllo wor", e.editor.text)
        // Mid-word: the decoder must not use a language prior it can't trust.
        assertEquals(0.0, e.decoder.nextPrior().second, 0.0)
    }

    @Test fun `emoji deletes as one unit`() {
        val e = engine()
        e.logic.onText("hi ")
        e.logic.onText("😀")
        e.logic.onBackspace()
        assertEquals("hi ", e.editor.text)
    }

    @Test fun `caps lock types uppercase and still corrects`() {
        val e = engine()
        e.logic.onShiftTap(doubleTap = true)
        e.typeCentered("teh ")
        assertEquals("THE ", e.editor.text)
    }

    @Test fun `fast noisy typing of a sentence mostly lands`() {
        // Integration smoke test: taps with realistic noise through the whole pipeline.
        val e = engine()
        val rng = Random(11)
        val sentence = "the quick brown fox jumps over the lazy dog"
        for (c in sentence) {
            if (c == ' ') e.logic.onSeparator(" ")
            else e.tapLetter(Fixtures.touchFor(c, rng, 0.25, 0.22))
        }
        e.logic.onSeparator(" ")
        val got = e.editor.text.trim().lowercase().split(" ")
        val want = sentence.split(" ")
        val correct = got.zip(want).count { it.first == it.second }
        assertTrue("got=$got", correct >= want.size - 1)
    }

    @Test fun `unwanted suggestion removal works`() {
        val user = UserVocabulary()
        val e = engine(user)
        e.typeCentered("teh")
        e.logic.removeSuggestion("the")
        val st = e.logic.stripState()
        assertTrue(st.pendingCorrection?.lowercase() != "the")
        assertTrue(st.suggestions.none { it.lowercase() == "the" })
    }

    @Test fun `vocabulary export import and reset`() {
        val u = UserVocabulary()
        u.learnExplicit("Kwame"); u.observeKept("florp", false, false); u.observeKept("florp", false, false)
        u.setReplacement("brb", "be right back"); u.block("moist")
        val json = u.toJson()
        val v = UserVocabulary.fromJson(json)
        assertTrue(v.isActive("kwame")); assertTrue(v.isActive("florp"))
        assertEquals("be right back", v.replacementFor("brb")); assertTrue(v.isBlocked("moist"))
        v.resetInferred()
        assertTrue(v.isActive("kwame")); assertFalse(v.isActive("florp"))
        v.resetAll()
        assertTrue(v.allWords().isEmpty())
    }

    @Test fun `bar punctuation attaches to the previous word`() {
        val e = engine()
        e.typeCentered("hello ")
        e.logic.onPunctuationShortcut(",")
        e.typeCentered("world")
        e.logic.onPunctuationShortcut("!")
        assertEquals("Hello, world! ", e.editor.text)
        assertEquals(ShiftState.AUTO, e.logic.shift)
    }

    @Test fun `single letters and digit-only strings are never learned implicitly`() {
        val user = UserVocabulary()
        val e = engine(user, TypingSettings(correction = CorrectionMode.OFF))
        e.typeCentered("g g g ok ")
        e.logic.onText("3"); e.logic.onSeparator(" "); e.logic.onText("3"); e.logic.onSeparator(" ")
        e.logic.finishInput()
        assertNull(user.entry("g")); assertNull(user.entry("3"))
    }
}
