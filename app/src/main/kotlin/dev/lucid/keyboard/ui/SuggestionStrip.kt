package dev.lucid.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import dev.lucid.keyboard.core.input.StripState

/**
 * The strip above the keys. Always shows the exact letters typed (so the user can keep
 * them with one tap), the auto-correction a separator would apply (highlighted), and
 * suggestions. Long-press any word for Learn / Never correct / Don't suggest.
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(context: Context, private val renderer: GlassRenderer) : View(context) {

    interface Listener {
        fun onPick(text: String, isLiteral: Boolean)
        fun onLearn(word: String)
        fun onNeverCorrect(word: String)
        fun onRemoveSuggestion(word: String)
        fun onQuickPanel()
        fun onHideKeyboard()
        fun onEmojiShortcut()
        fun onSwitchKeyboard()
        fun onPunctuation(p: String)
    }

    var listener: Listener? = null
    var privateMode = false; set(v) { field = v; invalidate() }
    var quickPanelOpen = false; set(v) { field = v; invalidate() }
    /** Show the keyboard-switch shortcut (Android asks IMEs to offer one on some setups). */
    var showSwitch = false; set(v) { if (field != v) { field = v; rebuild() } }

    private enum class Kind { LITERAL, CORRECTION, SUGGESTION, ACTION_LEARN, ACTION_NEVER, ACTION_BLOCK, ACTION_REVERT, CLOSE_MENU, SHORTCUT_EMOJI, SHORTCUT_SWITCH, SHORTCUT_PUNCT }
    private data class Chip(val kind: Kind, val text: String, val word: String, val emphasis: Boolean = false)

    private var state = StripState()
    private var chips: List<Chip> = emptyList()
    private var menuFor: Chip? = null
    private val chipRects = ArrayList<RectF>()
    private val d = resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16.5f * resources.displayMetrics.density }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val btnRect = RectF(); private val hideRect = RectF(); private val tmp = RectF(); private val tmp2 = RectF()
    private var pressed = -1
    private val longPress = Runnable { onLongPress() }

    init { rebuild() }

    fun setState(s: StripState) {
        if (s == state && menuFor == null) return
        state = s; menuFor = null
        rebuild()
    }

    private fun rebuild() {
        val s = state
        val m = menuFor
        val reverted = s.revertedWord; val last = s.lastCorrection; val pendingFix = s.pendingCorrection
        chips = when {
            m != null && (m.kind == Kind.LITERAL || m.kind == Kind.ACTION_LEARN) -> listOf(
                Chip(Kind.ACTION_LEARN, "Learn “${m.word}”", m.word, true),
                Chip(Kind.ACTION_NEVER, "Never correct", m.word),
                Chip(Kind.CLOSE_MENU, "✕", ""),
            )
            m != null -> listOf(
                Chip(Kind.ACTION_BLOCK, "Don’t suggest “${m.word}”", m.word, true),
                Chip(Kind.CLOSE_MENU, "✕", ""),
            )
            reverted != null -> listOf(
                Chip(Kind.ACTION_LEARN, "Learn “$reverted”", reverted, true),
                Chip(Kind.ACTION_NEVER, "Never correct", reverted),
            )
            last != null -> listOf(
                Chip(Kind.ACTION_REVERT, "↶ “${last.first}”", last.first),
            )
            // Nothing typed, but there is context: next-word predictions.
            s.literal.isEmpty() && s.predictions.isNotEmpty() -> listOf(Chip(Kind.SHORTCUT_EMOJI, "", "")) +
                s.predictions.take(3).map { Chip(Kind.SUGGESTION, it, it) }
            // Nothing typed: shortcuts instead of an empty bar.
            s.literal.isEmpty() -> listOfNotNull(
                Chip(Kind.SHORTCUT_EMOJI, "", ""),
                Chip(Kind.SHORTCUT_PUNCT, ",", ","),
                Chip(Kind.SHORTCUT_PUNCT, "?", "?"),
                Chip(Kind.SHORTCUT_PUNCT, "!", "!"),
                if (showSwitch) Chip(Kind.SHORTCUT_SWITCH, "", "") else null,
            )
            pendingFix != null -> listOfNotNull(
                Chip(Kind.LITERAL, "“${s.literal}”", s.literal),
                Chip(Kind.CORRECTION, pendingFix, pendingFix, true),
                s.suggestions.firstOrNull()?.let { Chip(Kind.SUGGESTION, it, it) },
            )
            else -> listOfNotNull(
                Chip(Kind.LITERAL, if (s.literalKnown) s.literal else "“${s.literal}”", s.literal),
                s.suggestions.getOrNull(0)?.let { Chip(Kind.SUGGESTION, it, it, true) },
                s.suggestions.getOrNull(1)?.let { Chip(Kind.SUGGESTION, it, it) },
            )
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val p = renderer.palette ?: return
        val h = height.toFloat(); val w = width.toFloat()
        val bs = h * 0.72f
        btnRect.set(8 * d, (h - bs) / 2, 8 * d + bs, (h + bs) / 2)
        hideRect.set(w - 8 * d - bs, (h - bs) / 2, w - 8 * d, (h + bs) / 2)
        if (quickPanelOpen) {
            flat.color = p.chipActive
            c.drawRoundRect(btnRect, btnRect.height() / 2, btnRect.height() / 2, flat)
        }
        drawSliders(c, btnRect, p.label)
        drawChevron(c, hideRect, p.labelSecondary)
        if (privateMode) {
            // Private mode: a small accent dot on the controls button.
            flat.color = p.accentTop
            c.drawCircle(btnRect.right - btnRect.width() * 0.18f, btnRect.top + btnRect.height() * 0.2f, 3.5f * d, flat)
        }
        chipRects.clear()
        if (chips.isEmpty()) return
        val left = btnRect.right + 8 * d; val right = hideRect.left - 8 * d
        val cw = (right - left) / chips.size
        divider.color = ColorUtils.setAlphaComponent(p.labelSecondary, 70)
        chips.forEachIndexed { i, chip ->
            val r = RectF(left + i * cw, 0f, left + (i + 1) * cw, h)
            chipRects += r
            if (chip.kind == Kind.SHORTCUT_EMOJI || chip.kind == Kind.SHORTCUT_SWITCH || chip.kind == Kind.SHORTCUT_PUNCT) {
                // Plain glyphs, like a system toolbar; a soft round highlight only while pressed.
                val bh = h * 0.72f
                tmp.set(r.centerX() - bh / 2, (h - bh) / 2, r.centerX() + bh / 2, (h + bh) / 2)
                if (i == pressed) { flat.color = p.chipActive; c.drawOval(tmp, flat) }
                when (chip.kind) {
                    Kind.SHORTCUT_EMOJI -> drawSmiley(c, tmp.centerX(), tmp.centerY(), bh * 0.26f, p.label)
                    Kind.SHORTCUT_SWITCH -> drawGlobe(c, tmp.centerX(), tmp.centerY(), bh * 0.26f, p.label)
                    else -> {
                        text.textSize = 20 * d; text.typeface = Typeface.DEFAULT; text.color = p.label
                        val fm = text.fontMetrics
                        c.drawText(chip.text, tmp.centerX(), h / 2 - (fm.ascent + fm.descent) / 2, text)
                    }
                }
                return@forEachIndexed
            }
            if (chip.emphasis || i == pressed) {
                tmp.set(r.left + 4 * d, r.top + 6 * d, r.right - 4 * d, r.bottom - 6 * d)
                flat.color = if (i == pressed) p.chipActive else p.chip
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, flat)
            } else if (i > 0 && !chips[i - 1].emphasis) {
                c.drawRect(r.left - 0.5f * d, h * 0.3f, r.left + 0.5f * d, h * 0.7f, divider)
            }
            text.textSize = 16.5f * d
            text.typeface = if (chip.emphasis) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            text.color = if (chip.emphasis) p.chipActiveLabel else p.chipLabel
            val label = TextUtils.ellipsize(chip.text, text, cw - 12 * d, TextUtils.TruncateAt.MIDDLE).toString()
            val fm = text.fontMetrics
            c.drawText(label, r.centerX(), h / 2 - (fm.ascent + fm.descent) / 2, text)
        }
    }

    private fun drawSliders(c: Canvas, r: RectF, color: Int) {
        icon.color = color; icon.strokeWidth = 1.6f * d
        val x0 = r.left + r.width() * 0.28f; val x1 = r.right - r.width() * 0.28f
        for ((i, f) in listOf(0.36f, 0.64f).withIndex()) {
            val y = r.top + r.height() * f
            c.drawLine(x0, y, x1, y, icon)
            val kx = if (i == 0) x0 + (x1 - x0) * 0.3f else x0 + (x1 - x0) * 0.7f
            c.drawCircle(kx, y, 2.6f * d, icon)
        }
    }

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        icon.color = color; icon.strokeWidth = 1.5f * d
        c.drawCircle(cx, cy, r, icon)
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.11f, f); c.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.11f, f)
        tmp2.set(cx - r * 0.5f, cy - r * 0.35f, cx + r * 0.5f, cy + r * 0.55f)
        c.drawArc(tmp2, 20f, 140f, false, icon)
    }

    private fun drawGlobe(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        icon.color = color; icon.strokeWidth = 1.4f * d
        c.drawCircle(cx, cy, r, icon)
        c.drawLine(cx - r, cy, cx + r, cy, icon)
        tmp2.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(tmp2, icon)
    }

    private fun drawChevron(c: Canvas, r: RectF, color: Int) {
        icon.color = color; icon.strokeWidth = 1.8f * d
        val cx = r.centerX(); val cy = r.centerY(); val s = r.width() * 0.18f
        c.drawLine(cx - s, cy - s * 0.5f, cx, cy + s * 0.5f, icon); c.drawLine(cx, cy + s * 0.5f, cx + s, cy - s * 0.5f, icon)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = chipRects.indexOfFirst { it.contains(e.x, e.y) }
                if (pressed >= 0) postDelayed(longPress, 450)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val i = pressed; pressed = -1
                when {
                    btnRect.contains(e.x, e.y) -> listener?.onQuickPanel()
                    hideRect.contains(e.x, e.y) -> listener?.onHideKeyboard()
                    i >= 0 && i < chips.size && chipRects[i].contains(e.x, e.y) -> tap(chips[i])
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressed = -1; invalidate() }
        }
        return true
    }

    private fun tap(chip: Chip) {
        val l = listener ?: return
        when (chip.kind) {
            Kind.LITERAL -> l.onPick(chip.word, true)
            Kind.CORRECTION, Kind.SUGGESTION -> l.onPick(chip.word, false)
            Kind.ACTION_LEARN -> { l.onLearn(chip.word); menuFor = null }
            Kind.ACTION_NEVER -> { l.onNeverCorrect(chip.word); menuFor = null }
            Kind.ACTION_BLOCK -> { l.onRemoveSuggestion(chip.word); menuFor = null }
            Kind.ACTION_REVERT -> l.onPick(chip.word, true)
            Kind.CLOSE_MENU -> { menuFor = null; rebuild() }
            Kind.SHORTCUT_EMOJI -> l.onEmojiShortcut()
            Kind.SHORTCUT_SWITCH -> l.onSwitchKeyboard()
            Kind.SHORTCUT_PUNCT -> l.onPunctuation(chip.text)
        }
    }

    private fun onLongPress() {
        val i = pressed
        if (i !in chips.indices) return
        val chip = chips[i]
        if (chip.kind in setOf(Kind.LITERAL, Kind.CORRECTION, Kind.SUGGESTION)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            menuFor = chip
            pressed = -1
            rebuild()
        }
    }
}
