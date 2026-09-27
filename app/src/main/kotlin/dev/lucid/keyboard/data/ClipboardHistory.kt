package dev.lucid.keyboard.data

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle

/**
 * Recently copied text, for the keyboard's clipboard panel.
 *
 * Privacy: kept in memory only (never written to disk, gone when the keyboard process
 * ends). Clips that the copying app marks as sensitive (Android 13+: passwords, one-time
 * codes from password managers and banking apps) are never recorded, and nothing is
 * recorded in private mode.
 */
class ClipboardHistory(private val max: Int = 20) {
    private val items = ArrayDeque<String>()
    /** When the newest item was copied (SystemClock.elapsedRealtime ms), to offer it briefly in the suggestion bar. */
    var newestAt = 0L; private set

    fun items(): List<String> = items.toList()

    fun remove(text: String) { items.remove(text) }

    fun clear() { items.clear(); newestAt = 0L }

    /** The fresh-copy suggestion was used: stop offering it in the bar. */
    fun markUsed() { newestAt = 0L }

    /** Reads the current clip and records it if allowed. Returns the text if it was added. */
    fun capture(cm: ClipboardManager, privateMode: Boolean, now: Long): String? {
        if (privateMode) return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (isSensitive(clip.description)) return null
        val text = (0 until clip.itemCount).asSequence()
            .mapNotNull { clip.getItemAt(it).text?.toString() }
            .firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (text.length > MAX_CHARS) return null
        val isNew = items.firstOrNull() != text
        items.remove(text)
        items.addFirst(text)
        while (items.size > max) items.removeLast()
        // Use the time the clip was actually copied (Android stamps it), not when we read it.
        if (isNew) newestAt = clip.description?.timestamp?.takeIf { it > 0 } ?: now
        return text
    }

    private fun isSensitive(d: ClipDescription?): Boolean {
        val extras: PersistableBundle = d?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= 33) extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        else extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    companion object {
        const val MAX_CHARS = 5000
        /** How long a fresh copy is offered in the suggestion bar. */
        const val FRESH_MS = 60_000L
    }
}
