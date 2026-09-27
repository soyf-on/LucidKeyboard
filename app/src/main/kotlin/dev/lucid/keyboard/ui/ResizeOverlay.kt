package dev.lucid.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/** Keyboard size and position: height scale, width as a fraction of the screen, and where the free space goes (0 = left, 1 = right). */
data class KeyboardSize(val height: Float, val width: Float, val offset: Float) {
    companion object { val DEFAULT = KeyboardSize(1f, 1f, 0.5f) }
}

/**
 * Resize mode, drawn over the keys: drag the top handle to change the height, the side
 * handles to change the width, and the middle to slide a narrower keyboard left or right
 * (one-handed use). Changes apply live through [onChange]; [onDone] saves them.
 */
@SuppressLint("ViewConstructor")
class ResizeOverlay(
    context: Context,
    private val renderer: GlassRenderer,
    initial: KeyboardSize,
    private val onChange: (KeyboardSize) -> Unit,
    private val onDone: (KeyboardSize) -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    var size = initial; private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val kb = RectF(); private val r = RectF()
    private val resetBtn = RectF(); private val doneBtn = RectF()

    private enum class Drag { NONE, HEIGHT, LEFT, RIGHT, MOVE }
    private var drag = Drag.NONE
    private var downX = 0f; private var downY = 0f
    private var start = initial; private var startHeightPx = 0f; private var startLeft = 0f; private var startRight = 0f
    private var snappedFull = false

    /** Cover exactly the keys: never make the keyboard taller (FrameLayout re-measures us exactly). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun bounds() {
        val w = width.toFloat()
        val kw = w * size.width
        val left = (w - kw) * size.offset
        kb.set(left, 0f, left + kw, height.toFloat())
    }

    override fun onDraw(c: Canvas) {
        val p = renderer.palette ?: return
        bounds()
        // Dim the keys so the handles read clearly.
        fill.color = ColorUtils.setAlphaComponent(if (p.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 205)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        stroke.color = p.accentTop; stroke.strokeWidth = 2 * d
        r.set(kb); r.inset(d, d)
        c.drawRoundRect(r, 18 * d, 18 * d, stroke)
        // Handles: top (height), left and right (width).
        fill.color = p.accentTop
        r.set(kb.centerX() - 28 * d, 4 * d, kb.centerX() + 28 * d, 10 * d); c.drawRoundRect(r, 3 * d, 3 * d, fill)
        r.set(kb.left + 3 * d, kb.centerY() - 26 * d, kb.left + 9 * d, kb.centerY() + 26 * d); c.drawRoundRect(r, 3 * d, 3 * d, fill)
        r.set(kb.right - 9 * d, kb.centerY() - 26 * d, kb.right - 3 * d, kb.centerY() + 26 * d); c.drawRoundRect(r, 3 * d, 3 * d, fill)

        text.color = p.label; text.typeface = Typeface.DEFAULT
        val hint = "Drag the edges to resize · drag the middle to move"
        text.textSize = fitTextSize(text, hint, kb.width() - 24 * d, 14 * d, 9 * d)
        c.drawText(hint, kb.centerX(), kb.centerY() - 34 * d, text)
        text.color = p.labelSecondary; text.textSize = 12 * d
        c.drawText("Height ${(size.height * 100).toInt()}%  ·  Width ${(size.width * 100).toInt()}%", kb.centerX(), kb.centerY() - 12 * d, text)

        val bw = 96 * d; val bh = 40 * d; val gap = 12 * d
        resetBtn.set(kb.centerX() - gap / 2 - bw, kb.centerY() + 8 * d, kb.centerX() - gap / 2, kb.centerY() + 8 * d + bh)
        doneBtn.set(kb.centerX() + gap / 2, kb.centerY() + 8 * d, kb.centerX() + gap / 2 + bw, kb.centerY() + 8 * d + bh)
        renderer.drawCap(c, resetBtn, CapStyle.FUNCTION, 0f, resetBtn.centerX(), resetBtn.centerY())
        renderer.drawCap(c, doneBtn, CapStyle.ACCENT, 0f, doneBtn.centerX(), doneBtn.centerY())
        text.textSize = 15 * d; text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fm = text.fontMetrics
        text.color = p.label; c.drawText("Reset", resetBtn.centerX(), resetBtn.centerY() - (fm.ascent + fm.descent) / 2, text)
        text.color = p.labelOnAccent; c.drawText("Done", doneBtn.centerX(), doneBtn.centerY() - (fm.ascent + fm.descent) / 2, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        bounds()
        val grab = 28 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; start = size
                startHeightPx = height.toFloat(); startLeft = kb.left; startRight = kb.right
                drag = when {
                    resetBtn.contains(e.x, e.y) || doneBtn.contains(e.x, e.y) -> Drag.NONE
                    e.y < grab -> Drag.HEIGHT
                    abs(e.x - kb.left) < grab -> Drag.LEFT
                    abs(e.x - kb.right) < grab -> Drag.RIGHT
                    kb.contains(e.x, e.y) && size.width < 0.999f -> Drag.MOVE
                    else -> Drag.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val w = width.toFloat()
                val dx = e.x - downX; val dy = e.y - downY
                val minW = w * MIN_WIDTH
                val next = when (drag) {
                    Drag.HEIGHT -> start.copy(height = (start.height * (startHeightPx - dy) / startHeightPx).coerceIn(MIN_HEIGHT, MAX_HEIGHT))
                    Drag.LEFT -> place(w, (startLeft + dx).coerceIn(0f, startRight - minW), startRight)
                    Drag.RIGHT -> place(w, startLeft, (startRight + dx).coerceIn(startLeft + minW, w))
                    Drag.MOVE -> { val kw = startRight - startLeft; val l = (startLeft + dx).coerceIn(0f, w - kw); place(w, l, l + kw) }
                    Drag.NONE -> null
                }
                if (next != null && next != size) {
                    val full = next.width >= 0.999f
                    if (full != snappedFull) { snappedFull = full; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    size = next; onChange(next); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drag == Drag.NONE) {
                    if (resetBtn.contains(e.x, e.y)) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); size = KeyboardSize.DEFAULT; onChange(size); invalidate() }
                    else if (doneBtn.contains(e.x, e.y)) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onDone(size) }
                }
                drag = Drag.NONE
            }
            MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    /** Converts edge positions to a size; nearly full width snaps to full. */
    private fun place(w: Float, left: Float, right: Float): KeyboardSize {
        var width = ((right - left) / w).coerceIn(MIN_WIDTH, 1f)
        if (width > 0.97f) width = 1f
        val free = w - width * w
        val offset = if (free < 1f) 0.5f else (left / free).coerceIn(0f, 1f)
        return size.copy(width = width, offset = offset)
    }

    companion object {
        const val MIN_HEIGHT = 0.7f
        const val MAX_HEIGHT = 1.4f
        const val MIN_WIDTH = 0.6f
    }
}
