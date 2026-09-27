package dev.lucid.keyboard.core.input

/** A [TextEditor] over an in-memory buffer (practice screen, tests). */
class MemoryEditor : TextEditor {
    private val sb = StringBuilder()
    private var cursor = 0
    private var composingStart = -1
    private var composingEnd = -1
    val text: String get() = sb.toString()

    fun clear() { sb.clear(); cursor = 0; composingStart = -1; composingEnd = -1 }

    override fun textBeforeCursor(n: Int) = sb.substring(maxOf(0, cursor - n), cursor)
    override fun textAfterCursor(n: Int) = sb.substring(cursor, minOf(sb.length, cursor + n))
    override fun hasSelection() = false

    private fun replaceComposing(t: String) {
        if (composingStart >= 0) { sb.replace(composingStart, composingEnd, t); cursor = composingStart + t.length }
        else { sb.insert(cursor, t); cursor += t.length }
    }

    override fun setComposing(text: String) {
        val start = if (composingStart >= 0) composingStart else cursor
        replaceComposing(text)
        if (text.isEmpty()) { composingStart = -1; composingEnd = -1 } else { composingStart = start; composingEnd = start + text.length }
    }

    override fun finishComposing() { composingStart = -1; composingEnd = -1 }
    override fun commit(text: String) { replaceComposing(text); finishComposing() }
    override fun deleteBefore(chars: Int) { finishComposing(); val s = maxOf(0, cursor - chars); sb.delete(s, cursor); cursor = s }
    override fun deleteBackward() {
        finishComposing()
        if (cursor == 0) return
        deleteBefore(if (cursor >= 2 && Character.isSurrogatePair(sb[cursor - 2], sb[cursor - 1])) 2 else 1)
    }
    override fun setComposingRegionBefore(length: Int): Boolean {
        if (length > cursor) return false
        composingStart = cursor - length; composingEnd = cursor; return true
    }
    override fun capsAtCursor() = false
}
