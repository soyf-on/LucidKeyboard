package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.WordCorrector
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.geometry.LayoutParams
import dev.lucid.keyboard.core.geometry.Layouts
import dev.lucid.keyboard.core.input.InputLogic
import dev.lucid.keyboard.core.input.TextEditor
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.lm.LanguagePack
import dev.lucid.keyboard.core.lm.ModelBundle
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchDecoder
import dev.lucid.keyboard.core.touch.TouchPoint
import java.io.File
import java.util.Random

object Fixtures {
    private val assets = File(System.getProperty("assets.dir") ?: "../app/src/main/assets")

    private val packCache = HashMap<String, LanguagePack>()
    fun pack(lang: String): LanguagePack = packCache.getOrPut(lang) { ModelBundle.loadPack(lang) { File(assets, it).inputStream() } }

    /** Portrait geometry close to a 1200 px-wide phone (Xiaomi 17 Pro Max class). */
    val params = LayoutParams(
        width = 1200f, rowHeight = 150f, hGap = 16f, vGap = 26f,
        sidePadding = 8f, topPadding = 8f, bottomPadding = 8f,
    )
    val layout: KeyboardLayout by lazy { Layouts.qwerty(params) }

    fun lm(user: UserVocabulary = UserVocabulary(), langs: List<String> = listOf("en")) = LanguageModel(langs.map { pack(it) }, user)

    class Engine(val lm: LanguageModel, val spatial: SpatialModel = SpatialModel()) {
        val decoder = TouchDecoder(lm, spatial)
        val corrector = WordCorrector(lm, spatial)
        val editor = FakeEditor()
        val logic = InputLogic(lm, decoder, corrector, editor).also { it.setLetterLayout(layout) }

        /** Types [text] with taps at the given points (letters) or literal keys (others). */
        fun tapLetter(p: TouchPoint) {
            val d = logic.decide(layout, p)
            logic.onLetterTap(layout, d.key, p)
        }

        fun typeCentered(text: String) {
            for (c in text) when {
                c == ' ' -> logic.onSeparator(" ")
                c.isLetter() && layout.letter(c) != null -> {
                    if (c.isUpperCase() && logic.shift == dev.lucid.keyboard.core.input.ShiftState.OFF) logic.onShiftTap(false)
                    val k = layout.letter(c)!!
                    tapLetter(TouchPoint(k.cx, k.cy))
                }
                else -> logic.onText(c.toString())
            }
        }
    }

    fun engine(user: UserVocabulary = UserVocabulary(), langs: List<String> = listOf("en")) = Engine(lm(user, langs))

    /** Synthetic touch for an intended key: Gaussian around the key centre plus a bias. */
    fun touchFor(c: Char, rng: Random, sx: Double, sy: Double, biasX: Double = 0.0, biasY: Double = 0.0, lay: KeyboardLayout = layout): TouchPoint {
        val k = if (c == ' ') lay.space!! else lay.letter(c)!!
        val cx = if (c == ' ') k.cx + ((rng.nextDouble() - 0.5) * k.w * 0.5).toFloat() else k.cx
        return TouchPoint(
            (cx + (rng.nextGaussian() * sx + biasX) * lay.unitW).toFloat(),
            (k.cy + (rng.nextGaussian() * sy + biasY) * lay.unitH).toFloat(),
        )
    }
}

/** In-memory editor with a cursor and a composing region, mirroring InputConnection semantics. */
class FakeEditor(initial: String = "") : TextEditor {
    val sb = StringBuilder(initial)
    var cursor = initial.length
    var composingStart = -1
    var composingEnd = -1
    var selectionEnd = cursor

    val text get() = sb.toString()

    override fun textBeforeCursor(n: Int) = sb.substring(maxOf(0, cursor - n), cursor)
    override fun textAfterCursor(n: Int) = sb.substring(cursor, minOf(sb.length, cursor + n))
    override fun hasSelection() = selectionEnd != cursor

    private fun replaceComposing(t: String) {
        if (composingStart >= 0) {
            sb.replace(composingStart, composingEnd, t)
            cursor = composingStart + t.length
        } else {
            sb.insert(cursor, t)
            cursor += t.length
        }
        selectionEnd = cursor
    }

    override fun setComposing(text: String) {
        val start = if (composingStart >= 0) composingStart else cursor
        replaceComposing(text)
        if (text.isEmpty()) { composingStart = -1; composingEnd = -1 } else { composingStart = start; composingEnd = start + text.length }
    }

    override fun finishComposing() { composingStart = -1; composingEnd = -1 }

    override fun commit(text: String) { replaceComposing(text); composingStart = -1; composingEnd = -1 }

    override fun deleteBefore(chars: Int) {
        finishComposing()
        val s = maxOf(0, cursor - chars)
        sb.delete(s, cursor); cursor = s; selectionEnd = cursor
    }

    override fun deleteBackward() {
        finishComposing()
        if (hasSelection()) {
            val a = minOf(cursor, selectionEnd); val b = maxOf(cursor, selectionEnd)
            sb.delete(a, b); cursor = a; selectionEnd = a; return
        }
        if (cursor == 0) return
        val n = if (cursor >= 2 && Character.isSurrogatePair(sb[cursor - 2], sb[cursor - 1])) 2 else 1
        deleteBefore(n)
    }

    override fun setComposingRegionBefore(length: Int): Boolean {
        if (length > cursor) return false
        composingStart = cursor - length; composingEnd = cursor
        return true
    }

    override fun capsAtCursor(): Boolean {
        val t = textBeforeCursor(3).trimEnd(' ')
        return cursor == 0 || textBeforeCursor(cursor).isBlank() || (t.isNotEmpty() && t.last() in ".!?" && textBeforeCursor(1) == " ") || textBeforeCursor(1) == "\n"
    }

    /** Simulates the user tapping somewhere in the text. */
    fun moveCursor(to: Int) { finishComposing(); cursor = to; selectionEnd = to }
}
