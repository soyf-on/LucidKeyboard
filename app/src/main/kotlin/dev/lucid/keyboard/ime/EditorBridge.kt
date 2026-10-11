package dev.lucid.keyboard.ime

import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.TextEditor

/**
 * [TextEditor] over the app's InputConnection. Keeps a model of the cursor and the
 * composing region so that selection updates caused by our own edits can be told
 * apart from the user moving the cursor (see [isOwnUpdate]).
 */
class EditorBridge(private val service: LucidInputMethodService) : TextEditor {
    private val ic: InputConnection? get() = service.currentInputConnection
    var editorInfo: EditorInfo? = null

    var selStart = 0; private set
    var selEnd = 0; private set
    private var composingStart = -1
    private var composingLen = 0
    /** Cursor positions our recent edits should produce; updates matching them are ours. */
    private val expected = ArrayDeque<Int>()

    fun reset(info: EditorInfo) {
        editorInfo = info
        selStart = info.initialSelStart.coerceAtLeast(0)
        selEnd = info.initialSelEnd.coerceAtLeast(0)
        composingStart = -1; composingLen = 0
        expected.clear()
    }

    private fun expect(pos: Int) { expected.addLast(pos); while (expected.size > 12) expected.removeFirst() }

    /** Called from onUpdateSelection. Returns true if the change came from our own edits. */
    fun isOwnUpdate(newStart: Int, newEnd: Int): Boolean {
        val own = newStart == newEnd && expected.contains(newStart)
        if (own) { while (expected.isNotEmpty() && expected.first() != newStart) expected.removeFirst() }
        else expected.clear()
        selStart = newStart; selEnd = newEnd
        if (!own) { composingStart = -1; composingLen = 0 }
        return own
    }

    override fun textBeforeCursor(n: Int): String? = ic?.getTextBeforeCursor(n, 0)?.toString()
    override fun textAfterCursor(n: Int): String? = ic?.getTextAfterCursor(n, 0)?.toString()
    override fun hasSelection() = selStart != selEnd

    override fun setComposing(text: String) {
        val start = if (composingStart >= 0) composingStart else selStart
        ic?.setComposingText(text, 1)
        if (text.isEmpty()) { composingStart = -1; composingLen = 0; selStart = start }
        else { composingStart = start; composingLen = text.length; selStart = start + text.length }
        selEnd = selStart; expect(selStart)
    }

    override fun finishComposing() {
        ic?.finishComposingText()
        composingStart = -1; composingLen = 0
    }

    override fun commit(text: String) {
        val start = if (composingStart >= 0) composingStart else selStart
        ic?.commitText(text, 1)
        composingStart = -1; composingLen = 0
        selStart = start + text.length; selEnd = selStart; expect(selStart)
    }

    override fun deleteBefore(chars: Int) {
        ic?.deleteSurroundingText(chars, 0)
        selStart = (selStart - chars).coerceAtLeast(0); selEnd = selStart; expect(selStart)
    }

    override fun deleteBackward() {
        val c = ic ?: return
        val raw = editorInfo?.let { (it.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL } ?: false
        if (raw) {
            // Raw editors (terminals etc.) only understand key events.
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            selStart = if (hasSelection()) minOf(selStart, selEnd) else (selStart - 1).coerceAtLeast(0)
            selEnd = selStart; expect(selStart)
            return
        }
        // Edit through the InputConnection, not a key event: key events are applied by the
        // app *later*, so text read right afterwards would still contain the deleted letter.
        val selected = c.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            c.commitText("", 1)
            selStart = minOf(selStart, selEnd); selEnd = selStart; expect(selStart)
            return
        }
        val n = graphemeLengthBeforeCursor()
        if (n <= 0) return
        c.deleteSurroundingText(n, 0)
        selStart = (selStart - n).coerceAtLeast(0); selEnd = selStart; expect(selStart)
    }

    /** The real cursor position, asked from the app (Android 12+), else our own tracking. */
    private fun cursorPosition(): Int {
        val c = ic ?: return selStart
        if (Build.VERSION.SDK_INT >= 31) {
            val st = runCatching { c.getSurroundingText(0, 0, 0) }.getOrNull()
            if (st != null && st.selectionStart >= 0 && st.offset >= 0) return st.offset + st.selectionStart
        }
        return selStart
    }

    private fun graphemeLengthBeforeCursor(): Int {
        val t = textBeforeCursor(16) ?: return 1
        if (t.isEmpty()) return 0
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.last(); val prev = it.previous()
        return if (prev == java.text.BreakIterator.DONE) t.length else end - prev
    }

    override fun setComposingRegionBefore(length: Int): Boolean {
        val c = ic ?: return false
        val cursor = cursorPosition()
        if (cursor < length || length <= 0) return false
        // Only mark a whole word: the text right before the cursor must be letters, preceded by a non-letter.
        val check = c.getTextBeforeCursor(length + 1, 0)?.toString() ?: return false
        val word = check.takeLast(length)
        if (word.length != length || word.any { !(it.isLetter() || it == '\'') }) return false
        if (check.length > length && check[0].isLetterOrDigit()) return false
        val ok = c.setComposingRegion(cursor - length, cursor)
        if (ok) { selStart = cursor; selEnd = cursor; composingStart = cursor - length; composingLen = length }
        return ok
    }

    override fun capsAtCursor(): Boolean {
        val info = editorInfo ?: return false
        return (ic?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    override fun beginBatch() { ic?.beginBatchEdit() }
    override fun endBatch() { ic?.endBatchEdit() }

    companion object {
        /** Maps the app's field description to our input policy. */
        fun fieldFor(info: EditorInfo): FieldInfo {
            val t = info.inputType
            val cls = t and InputType.TYPE_MASK_CLASS
            val variation = t and InputType.TYPE_MASK_VARIATION
            val flags = t and InputType.TYPE_MASK_FLAGS
            val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val multiLine = (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (cls == InputType.TYPE_NULL) return FieldInfo(literal = true, suggestionsAllowed = false, learningAllowed = false, autoCapAllowed = false, composing = false)
            if (cls != InputType.TYPE_CLASS_TEXT) return FieldInfo(literal = true, suggestionsAllowed = false, learningAllowed = false, autoCapAllowed = false, composing = false)
            val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            if (password) return FieldInfo.PASSWORD
            // E-mail and filter fields stay literal. URL fields are address/search bars: words are
            // corrected there, URL-like input is not (FieldInfo.urlAware).
            val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
            val literal = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER
            val noSuggestions = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val autoCorrect = (flags and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0
            val name = variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            // "No suggestions" loses when the field also asks for autocorrect (Chrome sets both on
            // web chat boxes such as Gemini), and in address/search bars, like Gboard.
            val wantsWords = !literal && (!noSuggestions || autoCorrect || uri)
            return FieldInfo(
                literal = literal,
                suggestionsAllowed = wantsWords,
                correctionAllowed = wantsWords && !name,
                learningAllowed = !noLearning && !literal && !uri,
                autoCapAllowed = !literal && !uri,
                composing = true,
                multiLine = multiLine,
                urlAware = uri,
            )
        }

        fun isNumeric(info: EditorInfo): Boolean {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        }
        fun isPhone(info: EditorInfo) = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_PHONE
    }
}
