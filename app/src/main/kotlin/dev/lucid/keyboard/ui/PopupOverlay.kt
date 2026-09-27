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
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(context: Context, private val renderer: GlassRenderer) : View(context) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val loc = IntArray(2); private val myLoc = IntArray(2)

    private var preview: Pair<RectF, String>? = null
    private var alts: List<String>? = null
    private var altRect = RectF()
    private var altSelected = 0

    init { isClickable = false; isFocusable = false }

    private fun keyRectInOverlay(v: View, k: Key): RectF {
        v.getLocationInWindow(loc); getLocationInWindow(myLoc)
        val dx = (loc[0] - myLoc[0]).toFloat(); val dy = (loc[1] - myLoc[1]).toFloat()
        return RectF(k.x + dx, k.y + dy, k.x + k.w + dx, k.y + k.h + dy)
    }

    fun showPreview(v: View, k: Key, label: String) {
        val kr = keyRectInOverlay(v, k)
        val w = kr.width() * 1.35f; val h = kr.height() * 1.25f
        val rect = RectF(kr.centerX() - w / 2, kr.top - h - 6 * d, kr.centerX() + w / 2, kr.top - 6 * d)
        if (rect.left < 2 * d) rect.offset(2 * d - rect.left, 0f)
        if (rect.right > width - 2 * d) rect.offset(width - 2 * d - rect.right, 0f)
        if (rect.top < 0) rect.offset(0f, -rect.top)
        preview = rect to label
        invalidate()
    }

    fun hidePreview() { if (preview != null) { preview = null; invalidate() } }

    fun showAlternates(v: View, k: Key, options: List<String>) {
        val kr = keyRectInOverlay(v, k)
        val cell = kr.width() * 0.95f
        val w = cell * options.size; val h = kr.height() * 1.1f
        var left = kr.centerX() - cell / 2
        left = min(left, width - 4 * d - w); left = max(left, 4 * d)
        altRect = RectF(left, max(0f, kr.top - h - 8 * d), left + w, max(0f, kr.top - h - 8 * d) + h)
        alts = options; altSelected = 0
        invalidate()
    }

    /** Index of the alternate under an x position in overlay coordinates. */
    fun alternateIndexAt(x: Float, count: Int): Int {
        if (alts == null || count == 0) return 0
        val cell = altRect.width() / count
        return ((x - altRect.left) / cell).toInt().coerceIn(0, count - 1)
    }

    fun selectAlternate(i: Int) { altSelected = i; invalidate() }
    fun hideAlternates() { if (alts != null) { alts = null; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val p = renderer.palette ?: return
        preview?.let { (rect, label) ->
            renderer.drawCap(canvas, rect, CapStyle.LETTER, 0f, rect.centerX(), rect.centerY())
            text.color = p.label; text.textSize = 30 * d; text.typeface = Typeface.DEFAULT
            val fm = text.fontMetrics
            canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
        alts?.let { options ->
            renderer.drawCap(canvas, altRect, CapStyle.LETTER, 0f, altRect.centerX(), altRect.centerY())
            val cell = altRect.width() / options.size
            options.forEachIndexed { i, s ->
                r.set(altRect.left + i * cell, altRect.top, altRect.left + (i + 1) * cell, altRect.bottom)
                if (i == altSelected) {
                    r.inset(3 * d, 4 * d)
                    renderer.drawCap(canvas, r, CapStyle.ACCENT, 0f, r.centerX(), r.centerY())
                }
                text.color = if (i == altSelected) p.labelOnAccent else p.label
                text.textSize = 22 * d
                val fm = text.fontMetrics
                canvas.drawText(s, altRect.left + (i + 0.5f) * cell, altRect.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }
    }

    @Suppress("unused") private fun dim(c: Int) = ColorUtils.setAlphaComponent(c, 120)
}
