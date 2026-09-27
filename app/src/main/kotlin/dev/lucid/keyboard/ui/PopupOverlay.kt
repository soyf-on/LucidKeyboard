package dev.lucid.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import dev.lucid.keyboard.core.geometry.Key
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent layer above the strip and keys that draws key previews and long-press
 * alternates. Purely visual: it never receives touches, so it cannot affect decoding.
 *
 * Motion: bubbles grow out of the pressed key (the rect springs from the key's shape to
 * the bubble's), and the long-press highlight glides between options on a spring.
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(context: Context, private val renderer: GlassRenderer) : View(context) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val tmp = RectF()
    private val loc = IntArray(2); private val myLoc = IntArray(2)
    private val clock = FrameClock()

    private var previewFrom = RectF(); private var previewTo = RectF(); private var previewLabel = ""
    private val previewSpring = Spring(0f, response = 0.24f, dampingFraction = 0.78f)

    private var alts: List<String>? = null
    private var altFrom = RectF(); private var altRect = RectF()
    private val altOpen = Spring(0f, response = 0.28f, dampingFraction = 0.8f)
    private val altHighlight = Spring(0f, response = 0.18f, dampingFraction = 0.86f)
    private var altSelected = 0
    private var lastAlts: List<String> = emptyList()

    init { isClickable = false; isFocusable = false }

    /** Never make the keyboard taller: take the height the siblings define (FrameLayout re-measures us exactly). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun keyRectInOverlay(v: View, k: Key): RectF {
        v.getLocationInWindow(loc); getLocationInWindow(myLoc)
        val dx = (loc[0] - myLoc[0]).toFloat(); val dy = (loc[1] - myLoc[1]).toFloat()
        return RectF(k.x + dx, k.y + dy, k.x + k.w + dx, k.y + k.h + dy)
    }

    private val motion get() = !renderer.reduceMotion

    fun showPreview(v: View, k: Key, label: String) {
        val kr = keyRectInOverlay(v, k)
        val w = kr.width() * 1.4f; val h = kr.height() * 1.3f
        val rect = RectF(kr.centerX() - w / 2, kr.top - h - 8 * d, kr.centerX() + w / 2, kr.top - 8 * d)
        if (rect.left < 2 * d) rect.offset(2 * d - rect.left, 0f)
        if (rect.right > width - 2 * d) rect.offset(width - 2 * d - rect.right, 0f)
        if (rect.top < 0) rect.offset(0f, -rect.top)
        previewFrom = kr; previewTo = rect; previewLabel = label
        if (motion) { previewSpring.snap(0.15f); previewSpring.target = 1f } else previewSpring.snap(1f)
        invalidate()
    }

    fun hidePreview() {
        if (previewSpring.target == 0f) return
        previewSpring.target = 0f
        if (!motion) previewSpring.snap(0f)
        postInvalidateOnAnimation()
    }

    fun showAlternates(v: View, k: Key, options: List<String>) {
        val kr = keyRectInOverlay(v, k)
        val cell = kr.width() * 0.95f
        val w = cell * options.size; val h = kr.height() * 1.1f
        var left = kr.centerX() - cell / 2
        left = min(left, width - 4 * d - w); left = max(left, 4 * d)
        val top = max(0f, kr.top - h - 8 * d)
        altRect = RectF(left, top, left + w, top + h)
        altFrom = kr
        alts = options; lastAlts = options; altSelected = 0
        altHighlight.snap(0f)
        previewSpring.snap(0f)
        if (motion) { altOpen.snap(0.2f); altOpen.target = 1f } else altOpen.snap(1f)
        invalidate()
    }

    /** Index of the alternate under an x position in overlay coordinates. */
    fun alternateIndexAt(x: Float, count: Int): Int {
        if (alts == null || count == 0) return 0
        val cell = altRect.width() / count
        return ((x - altRect.left) / cell).toInt().coerceIn(0, count - 1)
    }

    fun selectAlternate(i: Int) {
        altSelected = i
        altHighlight.target = i.toFloat()
        if (!motion) altHighlight.snap(i.toFloat())
        postInvalidateOnAnimation()
    }

    fun hideAlternates() {
        if (alts == null) return
        alts = null
        altOpen.target = 0f
        if (!motion) altOpen.snap(0f)
        postInvalidateOnAnimation()
    }

    private fun lerp(a: RectF, b: RectF, t: Float, out: RectF) = out.set(
        a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
    )

    override fun onDraw(canvas: Canvas) {
        val p = renderer.palette ?: return
        val dt = clock.tick()
        var moving = previewSpring.step(dt)
        moving = altOpen.step(dt) or moving
        moving = altHighlight.step(dt) or moving

        val pv = previewSpring.value
        if (pv > 0.01f && previewLabel.isNotEmpty()) {
            lerp(previewFrom, previewTo, pv.coerceAtMost(1.08f), r)
            renderer.drawCap(canvas, r, CapStyle.LETTER, 0f, r.centerX(), r.centerY())
            text.color = ColorUtils.setAlphaComponent(p.label, (255 * pv.coerceIn(0f, 1f)).toInt())
            text.textSize = 30 * d * (0.6f + 0.4f * pv.coerceIn(0f, 1f)); text.typeface = Typeface.DEFAULT
            val fm = text.fontMetrics
            canvas.drawText(previewLabel, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
        }

        val ov = altOpen.value
        val options = alts ?: lastAlts
        if (ov > 0.01f && options.isNotEmpty()) {
            lerp(altFrom, altRect, ov.coerceAtMost(1.05f), tmp)
            renderer.drawCap(canvas, tmp, CapStyle.LETTER, 0f, tmp.centerX(), tmp.centerY())
            val cell = tmp.width() / options.size
            val alpha = (255 * ov.coerceIn(0f, 1f)).toInt()
            // Highlight glides between cells.
            r.set(tmp.left + altHighlight.value * cell, tmp.top, tmp.left + (altHighlight.value + 1) * cell, tmp.bottom)
            r.inset(3 * d, 4 * d)
            if (alts != null) renderer.drawCap(canvas, r, CapStyle.ACCENT, 0f, r.centerX(), r.centerY())
            options.forEachIndexed { i, s ->
                val nearSel = (1f - kotlin.math.abs(altHighlight.value - i)).coerceIn(0f, 1f)
                text.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(p.label, p.labelOnAccent, if (alts != null) nearSel else 0f), alpha)
                text.textSize = 22 * d
                val fm = text.fontMetrics
                canvas.drawText(s, tmp.left + (i + 0.5f) * cell, tmp.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }
        if (moving) postInvalidateOnAnimation() else clock.reset()
    }
}
