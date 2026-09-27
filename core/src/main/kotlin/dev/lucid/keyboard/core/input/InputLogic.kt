package dev.lucid.keyboard.core.input

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.correct.ShiftSource
import dev.lucid.keyboard.core.correct.TypedChar
import dev.lucid.keyboard.core.correct.WordCorrector
import dev.lucid.keyboard.core.geometry.Key
import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.lm.Alphabet
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.TapDecision
import dev.lucid.keyboard.core.touch.TouchDecoder
import dev.lucid.keyboard.core.touch.TouchPoint

/** Minimal editor surface; the IME implements it over InputConnection, tests over a buffer. */
interface TextEditor {
    fun textBeforeCursor(n: Int): String?
    fun textAfterCursor(n: Int): String?
    fun hasSelection(): Boolean
    /** Replaces the current composing region (or inserts one) with [text]. */
    fun setComposing(text: String)
    fun finishComposing()
    /** Commits [text], replacing the composing region if there is one. */
    fun commit(text: String)
    fun deleteBefore(chars: Int)
    /** Deletes the selection, or one grapheme before the cursor (like a DEL key). */
    fun deleteBackward()
    /** Marks the [length] characters before the cursor as the composing region. */
    fun setComposingRegionBefore(length: Int): Boolean
    /** Whether the editor asks for a capital at the cursor (sentence start). */
    fun capsAtCursor(): Boolean
    fun beginBatch() {}
    fun endBatch() {}
}

data class FieldInfo(
    /** Passwords, URLs, e-mail, usernames, codes: never correct, never learn text. */
    val literal: Boolean = false,
    val password: Boolean = false,
    val suggestionsAllowed: Boolean = true,
    /** Auto-correction allowed (off for e.g. person-name fields, where suggestions still help). */
    val correctionAllowed: Boolean = true,
    /** False for IME_FLAG_NO_PERSONALIZED_LEARNING, incognito or sensitive fields. */
    val learningAllowed: Boolean = true,
    val autoCapAllowed: Boolean = true,
    /** Use composing text (underlined word). Off for passwords and raw-key fields. */
    val composing: Boolean = true,
    val multiLine: Boolean = false,
) {
    companion object {
        val DEFAULT = FieldInfo()
        val PASSWORD = FieldInfo(literal = true, password = true, suggestionsAllowed = false, correctionAllowed = false, learningAllowed = false, autoCapAllowed = false, composing = false)
    }
}

data class TypingSettings(
    val decoder: DecoderSettings = DecoderSettings(),
    val correction: CorrectionMode = CorrectionMode.BALANCED,
    val suggestions: Boolean = true,
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /** Private mode: nothing is learned or retained. */
    val privateMode: Boolean = false,
    /** Learn touch offsets from confirmed words. */
    val learnTouch: Boolean = true,
    /** Learn new words from repeated use. */
    val learnWords: Boolean = true,
)

enum class ShiftState { OFF, AUTO, ONESHOT, LOCKED }

/** What the suggestion strip should show. */
data class StripState(
    /** Exact letters typed; always offered so the user can keep them. */
    val literal: String = "",
    /** The auto-correction that a separator would apply, if any. */
    val pendingCorrection: String? = null,
    val suggestions: List<String> = emptyList(),
    val literalKnown: Boolean = true,
    /** Set right after a correction was reverted: offers "Learn" for the original. */
    val revertedWord: String? = null,
    /** Set right after an auto-correction: lets the user tap to get the original back. */
    val lastCorrection: Pair<String, String>? = null,
) {
    val isEmpty get() = literal.isEmpty() && revertedWord == null && lastCorrection == null && suggestions.isEmpty()
}

/** Word committed but not yet learned from; learning waits so an immediate undo can cancel it. */
internal data class PendingWord(
    val committed: String,
    val literal: String,
    val typed: List<TypedChar>,
    val layout: KeyboardLayout?,
    val prev: String?,
    val autoCorrected: Boolean,
    val overrodeCorrection: Boolean,
)

class InputLogic(
    val lm: LanguageModel,
    val decoder: TouchDecoder,
    val corrector: WordCorrector,
    private val editor: TextEditor,
) {
    var settings = TypingSettings()
        set(v) { field = v; decoder.settings = v.decoder; corrector.personalOffsets = v.decoder.personalOffsets }
    var fieldInfo = FieldInfo.DEFAULT
        private set

    var shift = ShiftState.OFF
        private set

    private val word = ArrayList<TypedChar>()
    private var wordLayout: KeyboardLayout? = null
    private var prevWord: String? = null
    private var lastCorrection: LastCorrection? = null
    private var pending: PendingWord? = null
    private var revertedWord: String? = null
    /** Space we inserted after a picked suggestion; punctuation may replace it. */
    private var phantomSpace = false
    private var lastSeparatorWasSpace = false
    private var lastSpaceAt = 0L
    /** Called with each learning event; the IME persists the model/vocabulary. */
    var onLearned: (() -> Unit)? = null

    private data class LastCorrection(val original: String, val corrected: String, val separator: String, val typed: List<TypedChar>, val layout: KeyboardLayout?, val prev: String?)

    val composingText: String get() = word.joinToString("") { it.char.toString() }
    val isComposing get() = word.isNotEmpty()
    val learningEnabled get() = !settings.privateMode && fieldInfo.learningAllowed

    val correctionMode: CorrectionMode
        get() = if (fieldInfo.literal || !fieldInfo.suggestionsAllowed || !fieldInfo.correctionAllowed) CorrectionMode.OFF else settings.correction

    // ---- lifecycle -------------------------------------------------------------------

    fun startInput(info: FieldInfo) {
        flushPending()
        fieldInfo = info
        resetWordState()
        val before = editor.textBeforeCursor(64).orEmpty()
        prevWord = lastWordOf(before)
        if (before.isNotEmpty() && before.last().isLetterOrDigit()) decoder.beginUnknownContext() else decoder.beginWord()
        updateAutoShift()
    }

    fun finishInput() {
        if (isComposing) editor.finishComposing()
        flushPending()
        resetWordState()
    }

    /** The cursor moved for a reason other than our own edits (tap in text, app change). */
    fun onExternalCursorMove() {
        if (isComposing) editor.finishComposing()
        flushPending()
        resetWordState()
        val before = editor.textBeforeCursor(64).orEmpty()
        prevWord = lastWordOf(before)
        val after = editor.textAfterCursor(1).orEmpty()
        val midWord = (before.isNotEmpty() && before.last().isLetterOrDigit()) || (after.isNotEmpty() && after[0].isLetterOrDigit())
        if (midWord) decoder.beginUnknownContext() else decoder.beginWord()
        updateAutoShift()
    }

    private fun resetWordState() {
        word.clear(); wordLayout = null
        lastCorrection = null; revertedWord = null; phantomSpace = false; lastSeparatorWasSpace = false
        decoder.beginWord()
    }

    // ---- keys ----------------------------------------------------------------------------

    /** Decide which key a touch-down means. Called once per touch; never revised. */
    fun decide(layout: KeyboardLayout, point: TouchPoint): TapDecision {
        val literalField = fieldInfo.literal || fieldInfo.password
        if (literalField && decoder.settings.adaptive) {
            // Passwords/URLs: no language prior (it would bias toward words), offsets only.
            val saved = decoder.settings
            decoder.settings = saved.copy(strength = 0.0)
            return decoder.decide(layout, point.x, point.y).also { decoder.settings = saved }
        }
        return decoder.decide(layout, point.x, point.y)
    }

    fun onShiftTap(doubleTap: Boolean) {
        shift = when {
            doubleTap -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ONESHOT
            else -> ShiftState.OFF
        }
    }

    /** A letter or character key was released. [point] is null for non-spatial input. */
    fun onKey(key: Key, point: TouchPoint?, now: Long = System.currentTimeMillis()) {
        when (key.kind) {
            KeyKind.LETTER -> onLetter(key.char, point)
            KeyKind.SPACE -> onSeparator(" ", now)
            KeyKind.CHAR -> onText(key.output, now)
            KeyKind.BACKSPACE -> onBackspace()
            else -> {}
        }
    }

    private var layoutForLetters: KeyboardLayout? = null
    fun setLetterLayout(layout: KeyboardLayout) { layoutForLetters = layout }

    private fun onLetter(c: Char, point: TouchPoint?) {
        val src = when (shift) {
            ShiftState.OFF -> ShiftSource.NONE
            ShiftState.AUTO -> ShiftSource.AUTO
            ShiftState.ONESHOT -> ShiftSource.MANUAL
            ShiftState.LOCKED -> ShiftSource.CAPS_LOCK
        }
        val ch = if (shift != ShiftState.OFF) c.uppercaseChar() else c
        typeChar(TypedChar(ch, point, src))
        if (shift == ShiftState.AUTO || shift == ShiftState.ONESHOT) shift = ShiftState.OFF
    }

    /** Entry used by the IME for letter taps with the decision's layout. */
    fun onLetterTap(layout: KeyboardLayout, key: Key, point: TouchPoint) {
        layoutForLetters = layout
        if (key.kind == KeyKind.SPACE) { onSeparator(" "); return }
        onLetter(key.char, point)
    }

    private fun typeChar(t: TypedChar) {
        leaveCommittedState()
        val lay = layoutForLetters
        val k = lay?.letter(t.char)
        if (t.point != null && lay != null && k != null) decoder.commitTap(lay, t.point.x, t.point.y, k)
        else decoder.commitLiteral(t.char)
        if (!fieldInfo.composing) {
            // Raw fields (passwords etc.): each character is final immediately.
            editor.commit(t.char.toString())
            return
        }
        if (word.isEmpty()) wordLayout = lay
        word += t
        editor.setComposing(composingText)
    }

    /** Text from the symbols page, long-press popups or emoji. */
    fun onText(text: String, now: Long = System.currentTimeMillis()) {
        if (text.length == 1) {
            val c = text[0]
            val joinsWord = c.isLetterOrDigit() || ((c == '\'' || c == '’' || c == '-') && isComposing)
            if (joinsWord) {
                typeChar(TypedChar(if (shift != ShiftState.OFF) c.uppercaseChar() else c, null,
                    if (shift == ShiftState.OFF) ShiftSource.NONE else ShiftSource.MANUAL))
                if (shift == ShiftState.AUTO || shift == ShiftState.ONESHOT) shift = ShiftState.OFF
                return
            }
            if (c in SEPARATOR_PUNCT) { onSeparator(text, now); return }
        }
        // Emoji or other symbols: end the word as typed (no correction into emoji context).
        if (isComposing) commitWord(correct = false, separator = "")
        leaveCommittedState()
        editor.commit(text)
        decoder.beginWord()
        updateAutoShift()
    }

    fun onEnter() {
        if (isComposing) commitWord(correct = true, separator = "")
        leaveCommittedState()
        decoder.beginWord()
    }

    private fun leaveCommittedState() {
        lastCorrection = null
        revertedWord = null
        lastSeparatorWasSpace = false
    }

    fun onSeparator(sep: String, now: Long = System.currentTimeMillis()) {
        editor.beginBatch()
        try {
            if (sep == " " && !isComposing && settings.doubleSpacePeriod && lastSeparatorWasSpace && now - lastSpaceAt < DOUBLE_SPACE_MS) {
                val before = editor.textBeforeCursor(2).orEmpty()
                if (before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in "\"')")) {
                    editor.deleteBefore(1)
                    editor.commit(". ")
                    lastSeparatorWasSpace = false; lastCorrection = null; phantomSpace = false
                    updateAutoShift()
                    return
                }
            }
            if (isComposing) {
                commitWord(correct = true, separator = sep)
            } else {
                // "word" + auto space from a suggestion, then "." -> "word. " instead of "word ."
                if (sep != " " && phantomSpace && editor.textBeforeCursor(1) == " ") {
                    editor.deleteBefore(1)
                    editor.commit(if (sep in ".,!?;:") "$sep " else sep)
                } else editor.commit(sep)
                lastCorrection = null; revertedWord = null
            }
            phantomSpace = false
            lastSeparatorWasSpace = sep == " "
            if (sep == " ") lastSpaceAt = now
            decoder.beginWord()
            updateAutoShift()
        } finally {
            editor.endBatch()
        }
    }

    private fun commitWord(correct: Boolean, separator: String) {
        val literal = composingText
        val sentenceStart = word.first().shift == ShiftSource.AUTO
        val mode = if (correct) correctionMode else CorrectionMode.OFF
        val res = corrector.correct(wordLayout, word, prevWord, mode, sentenceStart, suggestionsWanted = false)
        val final = dutchIJ(res.autoCorrection ?: literal)
        editor.commit(final + separator)
        lm.observeWord(final)
        flushPending()
        val corrected = final != literal
        lastCorrection = if (corrected) LastCorrection(literal, final, separator, word.toList(), wordLayout, prevWord) else null
        revertedWord = null
        pending = PendingWord(final, literal, word.toList(), wordLayout, prevWord, corrected, overrodeCorrection = false)
        prevWord = final
        word.clear(); wordLayout = null
    }

    fun onBackspace() {
        editor.beginBatch()
        try { backspace() } finally { editor.endBatch() }
    }

    private fun backspace() {
        phantomSpace = false
        lastSeparatorWasSpace = false
        if (isComposing) {
            word.removeAt(word.lastIndex)
            if (word.isEmpty()) {
                editor.setComposing("")
                editor.finishComposing()
                decoder.beginWord()
                wordLayout = null
            } else {
                editor.setComposing(composingText)
                decoder.replay(wordLayout, word.map { TouchDecoder.ReplayEvent(it.char, it.point) })
            }
            updateAutoShift()
            return
        }
        val lc = lastCorrection
        if (lc != null && editor.textBeforeCursor(lc.corrected.length + lc.separator.length) == lc.corrected + lc.separator) {
            // Revert the auto-correction: restore exactly what was typed, keep the separator.
            editor.deleteBefore(lc.corrected.length + lc.separator.length)
            editor.commit(lc.original + lc.separator)
            lastCorrection = null
            revertedWord = lc.original
            if (learningEnabled) lm.user.rejectCorrection(lc.original, lc.corrected)
            // The kept literal is a strong signal the word is intended; touch labels are unknown now.
            pending = PendingWord(lc.original, lc.original, lc.typed, null, lc.prev, autoCorrected = false, overrodeCorrection = true)
            prevWord = lc.original
            onLearned?.invoke()
            return
        }
        lastCorrection = null
        revertedWord = null
        // Any edit reaching back into the last word means it may not be right: don't learn from it.
        val pend = pending
        pending = null
        editor.deleteBackward()
        // Backspacing into the end of the last word resumes it, so suggestions return.
        val before = editor.textBeforeCursor(48).orEmpty()
        val after = editor.textAfterCursor(1).orEmpty()
        val tail = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (fieldInfo.composing && tail.isNotEmpty() && (after.isEmpty() || !after[0].isLetterOrDigit()) && tail.length <= 32) {
            if (editor.setComposingRegionBefore(tail.length)) {
                val restored = if (pend != null && pend.committed == tail && pend.layout != null && pend.typed.size == tail.length && !pend.autoCorrected) pend.typed
                else tail.map { TypedChar(it, null, if (it.isUpperCase()) ShiftSource.MANUAL else ShiftSource.NONE) }
                word.clear(); word += restored
                wordLayout = if (restored === pend?.typed) pend.layout else null
                prevWord = lastWordOf(before.dropLast(tail.length))
                decoder.replay(wordLayout, word.map { TouchDecoder.ReplayEvent(it.char, it.point) })
            }
        }
        if (!isComposing && (before.isEmpty() || !before.last().isLetterOrDigit())) decoder.beginWord()
        updateAutoShift()
    }

    /** User tapped a suggestion (or the literal) in the strip. */
    fun onSuggestionPicked(text: String, isLiteral: Boolean) {
        val literal = composingText
        if (isComposing) {
            editor.commit("$text ")
            flushPending()
            val decline = isLiteral && lm.isKnown(literal.lowercase()).not()
            if (isLiteral) {
                corrector.correct(wordLayout, word, prevWord, correctionMode, false, false).autoCorrection
                    ?.let { if (learningEnabled && it != literal) lm.user.rejectCorrection(literal, it) }
            }
            pending = PendingWord(text, literal, word.toList(), if (text == literal) wordLayout else null, prevWord,
                autoCorrected = false, overrodeCorrection = decline)
            prevWord = text
            lm.observeWord(text)
            word.clear(); wordLayout = null
        } else {
            // Not composing: the only literal chip shown is the original of the last
            // auto-correction; choosing it is the same as backspace-revert.
            val lc = lastCorrection
            if (isLiteral && lc != null && text == lc.original) { backspace(); return }
            if (isLiteral) return
            editor.commit("$text ")
        }
        lastCorrection = null; revertedWord = null
        phantomSpace = true
        lastSeparatorWasSpace = false
        decoder.beginWord()
        updateAutoShift()
    }

    // ---- learning ----------------------------------------------------------------------

    /** "Learn this word" from the strip. Active immediately. */
    fun learnWord(word: String) {
        if (settings.privateMode) return
        lm.user.learnExplicit(word)
        lm.user.clearRejection(word)
        revertedWord = null
        onLearned?.invoke()
    }

    fun neverCorrect(word: String) {
        if (settings.privateMode) return
        lm.user.setNeverCorrect(word)
        onLearned?.invoke()
    }

    fun removeSuggestion(word: String) {
        lm.user.block(word)
        onLearned?.invoke()
    }

    /** Commits pending learning: called when the word has clearly been kept. */
    fun flushPending() {
        val p = pending ?: return
        pending = null
        if (!learningEnabled) return
        val lower = p.committed.lowercase()
        if (settings.learnWords) {
            if (!p.autoCorrected) lm.user.observeKept(p.committed, lm.inLexicon(lower), p.overrodeCorrection)
            p.prev?.let { lm.user.observeBigram(it, p.committed) }
        }
        if (settings.learnTouch && settings.decoder.adaptive) learnTouches(p)
        onLearned?.invoke()
    }

    /**
     * Touch-offset learning only from words we're confident about: the final word is
     * known, it aligns 1:1 with the taps, and at most one tap was changed by correction
     * (that one gets half weight). Reverted corrections never teach the touch model.
     */
    private fun learnTouches(p: PendingWord) {
        val layout = p.layout ?: return
        val target = p.committed.lowercase()
        if (target.length != p.typed.size || target.length < 2) return
        if (!lm.isKnown(target)) return
        if (p.typed.any { it.point == null }) return
        val mismatches = target.indices.count { target[it] != p.typed[it].char.lowercaseChar() }
        if (mismatches > 1) return
        for (i in target.indices) {
            val k = layout.letter(target[i]) ?: continue
            val pt = p.typed[i].point!!
            val w = if (target[i] == p.typed[i].char.lowercaseChar()) 1.0 else 0.5
            decoder.spatial.learn(k.output, ((pt.x - k.cx) / layout.unitW).toDouble(), ((pt.y - k.cy) / layout.unitH).toDouble(), w)
        }
    }

    // ---- strip -------------------------------------------------------------------------------

    fun stripState(): StripState {
        if (!fieldInfo.suggestionsAllowed || !settings.suggestions && correctionMode == CorrectionMode.OFF) {
            return StripState(literal = if (fieldInfo.password) "" else composingText)
        }
        if (!isComposing) {
            val lc = lastCorrection
            return StripState(revertedWord = revertedWord, lastCorrection = lc?.let { it.original to it.corrected })
        }
        val literal = composingText
        val res = corrector.correct(wordLayout, word, prevWord, correctionMode, word.first().shift == ShiftSource.AUTO, settings.suggestions)
        val completions = if (settings.suggestions && literal.length >= 1 && literal.all { Alphabet.index(it) >= 0 })
            corrector.completions(literal.lowercase(), 3).map { WordCorrector.restoreCase(it, word) } else emptyList()
        val sugg = (res.suggestions + completions).filter { it != literal && it != res.autoCorrection }.distinct()
        return StripState(literal, res.autoCorrection, if (settings.suggestions) sugg.take(4) else emptyList(), res.literalKnown)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun updateAutoShift() {
        if (shift == ShiftState.LOCKED || shift == ShiftState.ONESHOT) return
        shift = if (settings.autoCapitalize && fieldInfo.autoCapAllowed && !isComposing && editor.capsAtCursor()) ShiftState.AUTO
        else ShiftState.OFF
    }

    fun refreshAutoShift() = updateAutoShift()

    /** Dutch capitalises the IJ digraph together: "Ijs" -> "IJs", "Ijsland" -> "IJsland". */
    private fun dutchIJ(w: String): String {
        if (w.length < 2 || w[0] != 'I' || w[1] != 'j') return w
        if (lm.weightOf("nl") < 0.25) return w
        val nl = lm.packs.firstOrNull { it.code == "nl" } ?: return w
        return if (nl.lexicon.contains(w.lowercase())) "IJ" + w.substring(2) else w
    }

    companion object {
        const val DOUBLE_SPACE_MS = 700L
        const val SEPARATOR_PUNCT = ".,!?;:)]}\"…"

        fun lastWordOf(text: String): String? {
            val t = text.trimEnd()
            if (t.isEmpty() || !t.last().isLetter()) return null
            return t.takeLastWhile { it.isLetter() || it == '\'' }.ifEmpty { null }
        }
    }
}
