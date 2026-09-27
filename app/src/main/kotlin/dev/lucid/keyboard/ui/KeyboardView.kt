package dev.lucid.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import dev.lucid.keyboard.core.geometry.Key
import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.touch.TapDecision
import dev.lucid.keyboard.core.touch.TouchPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [KeyboardLayout] and turns touches into key events.
 *
 * Touch contract:
 *  - The key for a touch is decided **once, at touch-down** (via [Listener.decide]) and
 *    never re-interpreted during the gesture. That decision is highlighted immediately.
 *  - Characters are emitted on release, or earlier if another finger goes down first
 *    (rollover), so fast two-thumb typing keeps its order.
 *  - Backspace acts on touch-down and repeats; shift toggles on touch-down.
 */
@SuppressLint("ViewConstructor")
open class KeyboardView(context: Context, private val renderer: GlassRenderer) : View(context) {

    interface Listener {
        fun decide(layout: KeyboardLayout, point: TouchPoint): TapDecision
        /** A letter, space or character key was released (or rolled over). */
        fun onKeyCommitted(key: Key, point: TouchPoint, decision: TapDecision)
        fun onText(text: String)
        fun onBackspace()
        fun onShift(doubleTap: Boolean)
        fun onFunctionKey(key: Key)
        fun onCursorMove(steps: Int)
        fun onLongPressFunction(key: Key): Boolean = false
        fun onKeyDownFeedback(key: Key)
        /** A slide-to-type path (layout coordinates), started on a letter key. */
        fun onGesture(path: List<TouchPoint>) {}
    }

    var gestureTyping = true
    /** How long a key must be held for its alternates (user setting). */
    var longPressMs = 380L

    var listener: Listener? = null
    /** Called when the view's width changes, so the owner can rebuild the layout for the real width
     *  (the IME window can be narrower than the display, e.g. inset by a cutout in landscape). */
    var onWidthChanged: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) onWidthChanged?.invoke(w)
    }
    var layout: KeyboardLayout? = null
        set(v) { field = v; cancelAll(); regionMap = null; invalidate() }

    // Visual state supplied by the service.
    var shiftOn = false; set(v) { if (field != v) { field = v; invalidate() } }
    var capsLock = false; set(v) { if (field != v) { field = v; invalidate() } }
    enum class EnterIcon { RETURN, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }
    var enterIcon = EnterIcon.RETURN; set(v) { field = v; invalidate() }
    var enterIsAction = false; set(v) { field = v; invalidate() }
    /** Subtle label on the space bar, e.g. "EN · NL" when typing in two languages. */
    var spaceLabel = ""; set(v) { field = v; invalidate() }
    var keyPopups = true
    var showDigitHints = false
    var overlay: PopupOverlay? = null

    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capRect = RectF()
    private val path = Path()

    // ---- press animation ---------------------------------------------------------------
    // Each pressed key has a spring: it lights up instantly under the finger (feedback must
    // never lag), then lifts smoothly; on release it glides back instead of snapping.
    private val pressSprings = HashMap<Key, Spring>()
    private val glowAt = HashMap<Key, TouchPoint>()
    private val clock = FrameClock()
    /** 1 while the space bar is being used as a trackpad: labels fade out, like iOS. */
    private val trackpad = Spring(0f, response = 0.3f, dampingFraction = 0.9f)

    private fun press(k: Key, at: TouchPoint) {
        val sp = pressSprings.getOrPut(k) { Spring(0f, response = 0.22f, dampingFraction = 0.72f) }
        if (renderer.reduceMotion) sp.snap(1f) else { if (sp.value < 0.55f) sp.snap(0.55f); sp.target = 1f }
        glowAt[k] = at
        postInvalidateOnAnimation()
    }

    private fun release(k: Key) {
        val sp = pressSprings[k] ?: return
        sp.target = 0f
        if (renderer.reduceMotion) sp.snap(0f)
        postInvalidateOnAnimation()
    }

    // ---- pointers ----------------------------------------------------------------------
    private class Ptr(val id: Int, val key: Key, val down: TouchPoint, val decision: TapDecision, val downTime: Long) {
        var committed = false
        var cursorMode = false
        var cursorAnchorX = down.x
        var alternates: List<String>? = null
        var altIndex = 0
        var longPressFired = false
        var gesture: ArrayList<TouchPoint>? = null
    }

    private val ptrs = ArrayList<Ptr>()
    private var lastShiftDown = 0L

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        var count = 0
        override fun run() {
            if (ptrs.none { it.key.kind == KeyKind.BACKSPACE }) return
            listener?.onBackspace()
            count++
            postDelayed(this, max(30L, 75L - count * 3L)) // accelerates from 75 ms to 30 ms
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lay = layout ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val pt = TouchPoint(e.getX(i), e.getY(i))
                // Rollover: a new finger commits every earlier pending character, in order.
                for (p in ptrs) if (!p.committed && isCharKey(p.key) && !p.cursorMode && p.alternates == null) commit(p)
                val decision = listener?.decide(lay, pt) ?: return true
                val key = decision.key
                val ptr = Ptr(e.getPointerId(i), key, pt, decision, e.eventTime)
                ptrs += ptr
                press(key, pt)
                listener?.onKeyDownFeedback(key)
                if (overlayEnabled) recordTap(pt, decision)
                when (key.kind) {
                    KeyKind.BACKSPACE -> {
                        listener?.onBackspace(); ptr.committed = true
                        repeatRunnable.count = 0
                        removeCallbacks(repeatRunnable); postDelayed(repeatRunnable, 420)
                    }
                    KeyKind.SHIFT -> {
                        val now = e.eventTime
                        listener?.onShift(now - lastShiftDown < 320)
                        lastShiftDown = now
                        ptr.committed = true
                    }
                    else -> {}
                }
                if (keyPopups && isCharKey(key) && key.kind != KeyKind.SPACE) overlay?.showPreview(this, key, displayLabel(key))
                removeCallbacks(longPressRunnable)
                if (key.longPress.isNotEmpty() || key.kind == KeyKind.SPACE || key.kind == KeyKind.SWITCH_IME || key.kind == KeyKind.TO_SYMBOLS || key.kind == KeyKind.TO_LETTERS || key.kind == KeyKind.ENTER)
                    postDelayed(longPressRunnable, if (key.kind == KeyKind.SPACE) longPressMs + 170L else longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in ptrs) {
                    val idx = e.findPointerIndex(p.id); if (idx < 0) continue
                    val x = e.getX(idx)
                    // Slide to type: a single finger that leaves its letter key becomes a gesture.
                    if (gestureTyping && ptrs.size == 1 && p.key.kind == KeyKind.LETTER && !p.committed && p.alternates == null) {
                        val g = p.gesture
                        if (g == null && abs(x - p.down.x) + abs(e.getY(idx) - p.down.y) > lay.unitW * 0.6f) {
                            p.gesture = arrayListOf(p.down)
                            removeCallbacks(longPressRunnable)
                            overlay?.hidePreview()
                            release(p.key)
                            trailFade.snap(1f)
                        }
                        p.gesture?.let { gp ->
                            for (h in 0 until e.historySize) gp += TouchPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                            gp += TouchPoint(x, e.getY(idx))
                            trail = gp
                            postInvalidateOnAnimation()
                        }
                        if (p.gesture != null) continue
                    }
                    if (p.key.kind == KeyKind.SPACE && !p.committed) {
                        val step = lay.unitW * 0.45f
                        if (!p.cursorMode && abs(x - p.down.x) > 14 * density) {
                            p.cursorMode = true; p.cursorAnchorX = x; postInvalidateOnAnimation()
                            removeCallbacks(longPressRunnable)
                            listener?.onKeyDownFeedback(p.key)
                        }
                        if (p.cursorMode) {
                            val steps = ((x - p.cursorAnchorX) / step).toInt()
                            if (steps != 0) { listener?.onCursorMove(steps); p.cursorAnchorX += steps * step }
                        }
                    }
                    p.alternates?.let { alts ->
                        val ni = overlay?.alternateIndexAt(e.getX(idx) + left, alts.size) ?: 0
                        if (ni != p.altIndex) { p.altIndex = ni; overlay?.selectAlternate(ni) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val p = ptrs.firstOrNull { it.id == id }
                if (p != null) { finish(p); ptrs.remove(p) }
                if (ptrs.isEmpty()) { removeCallbacks(longPressRunnable); removeCallbacks(repeatRunnable) }
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun isCharKey(k: Key) = k.kind == KeyKind.LETTER || k.kind == KeyKind.SPACE || k.kind == KeyKind.CHAR

    private fun commit(p: Ptr) {
        if (p.committed) return
        p.committed = true
        listener?.onKeyCommitted(p.key, p.down, p.decision)
        overlay?.hidePreview()
    }

    private fun finish(p: Ptr) {
        release(p.key)
        if (p.key.kind == KeyKind.BACKSPACE) removeCallbacks(repeatRunnable)
        val alts = p.alternates
        p.gesture?.let { g ->
            p.committed = true
            trailFade.target = 0f
            postInvalidateOnAnimation()
            if (g.size >= 3) listener?.onGesture(g.toList())
            overlay?.hidePreview()
            return
        }
        when {
            alts != null -> { overlay?.hideAlternates(); p.committed = true; if (p.altIndex in alts.indices) listener?.onText(applyShift(alts[p.altIndex])) }
            p.cursorMode || p.longPressFired -> p.committed = true
            p.committed -> {}
            isCharKey(p.key) -> commit(p)
            p.key.kind != KeyKind.SHIFT && p.key.kind != KeyKind.BACKSPACE -> { p.committed = true; listener?.onFunctionKey(p.key) }
        }
        overlay?.hidePreview()
    }

    private fun onLongPress() {
        val p = ptrs.lastOrNull() ?: return
        if (p.committed || p.cursorMode) return
        val k = p.key
        when {
            k.kind == KeyKind.SPACE -> { p.cursorMode = true; p.cursorAnchorX = p.down.x; listener?.onKeyDownFeedback(k) }
            k.kind == KeyKind.SWITCH_IME || k.kind == KeyKind.TO_SYMBOLS || k.kind == KeyKind.TO_LETTERS || k.kind == KeyKind.ENTER ->
                if (listener?.onLongPressFunction(k) == true) { p.longPressFired = true; listener?.onKeyDownFeedback(k) }
            k.longPress.isNotEmpty() -> {
                overlay?.hidePreview()
                p.alternates = k.longPress
                p.altIndex = 0
                overlay?.showAlternates(this, k, k.longPress.map { applyShift(it) })
                listener?.onKeyDownFeedback(k)
            }
        }
    }

    private fun applyShift(s: String) = if (shiftOn || capsLock) s.uppercase() else s

    fun cancelAll() {
        for (p in ptrs) release(p.key)
        ptrs.clear()
        removeCallbacks(longPressRunnable); removeCallbacks(repeatRunnable)
        overlay?.hidePreview(); overlay?.hideAlternates()
    }

    override fun onDetachedFromWindow() { cancelAll(); super.onDetachedFromWindow() }

    // ---- drawing -----------------------------------------------------------------------

    fun displayLabel(k: Key): String = when (k.kind) {
        KeyKind.LETTER -> if (shiftOn || capsLock) k.label.uppercase() else k.label
        KeyKind.SPACE -> ""
        else -> k.label
    }

    override fun onDraw(canvas: Canvas) {
        val lay = layout ?: return
        val p = renderer.palette ?: return
        val dt = clock.tick()
        var animating = false
        trackpad.target = if (ptrs.any { it.cursorMode }) 1f else 0f
        if (renderer.reduceMotion) trackpad.snap(trackpad.target)
        if (trackpad.step(dt)) animating = true
        regionMap?.let { drawRegionMap(canvas, it) }
        for (k in lay.keys) {
            val sp = pressSprings[k]
            if (sp != null && sp.step(dt)) animating = true
            val prog = (sp?.value ?: 0f).coerceIn(0f, 1.15f)
            if (sp != null && !sp.isMoving && sp.target == 0f) { pressSprings.remove(k); glowAt.remove(k) }
            capRect.set(k.x, k.y, k.x + k.w, k.y + k.h)
            val style = when {
                k.kind == KeyKind.ENTER && enterIsAction -> CapStyle.ACCENT
                k.kind == KeyKind.SHIFT && (shiftOn || capsLock) -> CapStyle.LETTER
                k.kind == KeyKind.LETTER || k.kind == KeyKind.SPACE || k.kind == KeyKind.CHAR -> CapStyle.LETTER
                else -> CapStyle.FUNCTION
            }
            val g = glowAt[k]
            renderer.drawCap(canvas, capRect, style, prog, g?.x ?: k.cx, g?.y ?: k.cy)
            if (trackpad.value < 0.98f || k.kind == KeyKind.SPACE) {
                if (trackpad.value > 0.01f && k.kind != KeyKind.SPACE) canvas.saveLayerAlpha(k.x, k.y, k.x + k.w, k.y + k.h, (255 * (1f - trackpad.value)).toInt().coerceIn(0, 255))
                drawLabel(canvas, k, style, p)
                if (trackpad.value > 0.01f && k.kind != KeyKind.SPACE) canvas.restore()
            }
        }
        if (trailFade.step(dt)) animating = true
        trail?.let { drawTrail(canvas, it, p) }
        if (trailFade.value <= 0.01f && ptrs.none { it.gesture != null }) trail = null
        if (overlayEnabled) drawTapOverlay(canvas)
        if (animating) postInvalidateOnAnimation() else clock.reset()
    }

    private fun drawLabel(c: Canvas, k: Key, style: CapStyle, p: GlassPalette) {
        val color = if (style == CapStyle.ACCENT) p.labelOnAccent else p.label
        val cx = k.cx; val cy = k.cy
        val s = min(k.w, k.h)
        val icon = s * 0.34f
        when (k.kind) {
            KeyKind.SHIFT -> { drawShift(c, cx, cy, icon, color, filled = shiftOn || capsLock, underline = capsLock); return }
            KeyKind.BACKSPACE -> { drawBackspace(c, cx, cy, icon, color); return }
            KeyKind.EMOJI -> { drawSmiley(c, cx, cy, s * 0.28f, color); return }
            KeyKind.SWITCH_IME -> { drawGlobe(c, cx, cy, s * 0.28f, color); return }
            KeyKind.ENTER -> { drawEnter(c, cx, cy, icon, color); return }
            KeyKind.SPACE -> {
                if (spaceLabel.isEmpty()) return
                hintPaint.color = p.labelSecondary
                hintPaint.textSize = fitTextSize(hintPaint, spaceLabel, k.w - 24 * density, 13 * density)
                c.drawText(spaceLabel, cx, cy + hintPaint.textSize * 0.35f, hintPaint); return
            }
            else -> {}
        }
        val text = displayLabel(k)
        labelPaint.color = color
        labelPaint.typeface = if (k.kind == KeyKind.LETTER || (k.kind == KeyKind.CHAR && text.length == 1)) Typeface.DEFAULT else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val maxSize = when {
            k.kind == KeyKind.LETTER -> letterSize(k)
            k.kind == KeyKind.CHAR && text.length == 1 -> min(23 * density, k.h * 0.48f)
            else -> min(16 * density, k.h * 0.36f)
        }
        // Auto-fit: every label keeps a clear margin from the key edge.
        labelPaint.textSize = if (k.kind == KeyKind.LETTER) maxSize else fitTextSize(labelPaint, text, k.w - 14 * density, maxSize)
        val fm = labelPaint.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, labelPaint)
        if (showDigitHints && k.kind == KeyKind.LETTER && k.longPress.isNotEmpty()) {
            // What long-press types, small and inset so it never touches the key edge.
            hintPaint.color = ColorUtils.setAlphaComponent(p.labelSecondary, 170)
            hintPaint.textSize = min(10.5f * density, k.h * 0.2f)
            val fm = hintPaint.fontMetrics
            c.drawText(k.longPress[0], k.x + k.w - 9 * density, k.y + 6 * density - fm.ascent, hintPaint)
        }
    }

    /**
     * One size for every letter on the layout, fitted to the widest letter, so a narrow
     * (resized) keyboard shrinks all letters evenly instead of each one differently.
     */
    private var letterSizeFor: KeyboardLayout? = null
    private var letterSizeCache = 0f
    private fun letterSize(k: Key): Float {
        val lay = layout
        if (lay !== letterSizeFor) {
            letterSizeFor = lay
            labelPaint.typeface = Typeface.DEFAULT
            val maxPx = min(25 * density, k.h * 0.52f)
            letterSizeCache = fitTextSize(labelPaint, "W", k.w - 8 * density, maxPx, minPx = 9 * density)
        }
        return letterSizeCache
    }

    /** Return / action glyphs, drawn as strokes so they scale cleanly with the key. */
    private fun drawEnter(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke(color, 1.8f * density)
        path.reset()
        when (enterIcon) {
            EnterIcon.RETURN -> { // ↵
                path.moveTo(cx + r * 0.75f, cy - r * 0.55f); path.lineTo(cx + r * 0.75f, cy + r * 0.15f); path.lineTo(cx - r * 0.7f, cy + r * 0.15f)
                c.drawPath(path, iconPaint)
                path.reset(); path.moveTo(cx - r * 0.35f, cy - r * 0.2f); path.lineTo(cx - r * 0.75f, cy + r * 0.15f); path.lineTo(cx - r * 0.35f, cy + r * 0.5f)
            }
            EnterIcon.GO, EnterIcon.NEXT -> { // →
                path.moveTo(cx - r * 0.7f, cy); path.lineTo(cx + r * 0.7f, cy)
                path.moveTo(cx + r * 0.25f, cy - r * 0.45f); path.lineTo(cx + r * 0.7f, cy); path.lineTo(cx + r * 0.25f, cy + r * 0.45f)
            }
            EnterIcon.PREVIOUS -> {
                path.moveTo(cx + r * 0.7f, cy); path.lineTo(cx - r * 0.7f, cy)
                path.moveTo(cx - r * 0.25f, cy - r * 0.45f); path.lineTo(cx - r * 0.7f, cy); path.lineTo(cx - r * 0.25f, cy + r * 0.45f)
            }
            EnterIcon.SEND -> { // ↑
                path.moveTo(cx, cy + r * 0.7f); path.lineTo(cx, cy - r * 0.7f)
                path.moveTo(cx - r * 0.45f, cy - r * 0.25f); path.lineTo(cx, cy - r * 0.7f); path.lineTo(cx + r * 0.45f, cy - r * 0.25f)
            }
            EnterIcon.SEARCH -> { // magnifier
                c.drawCircle(cx - r * 0.12f, cy - r * 0.12f, r * 0.42f, iconPaint)
                path.moveTo(cx + r * 0.2f, cy + r * 0.2f); path.lineTo(cx + r * 0.62f, cy + r * 0.62f)
            }
            EnterIcon.DONE -> { // ✓
                path.moveTo(cx - r * 0.6f, cy + r * 0.02f); path.lineTo(cx - r * 0.15f, cy + r * 0.45f); path.lineTo(cx + r * 0.65f, cy - r * 0.45f)
            }
        }
        c.drawPath(path, iconPaint)
    }

    private fun stroke(color: Int, w: Float) { iconPaint.color = color; iconPaint.strokeWidth = w }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean, underline: Boolean) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r, cy + r * 0.05f); path.lineTo(cx + r * 0.45f, cy + r * 0.05f)
        path.lineTo(cx + r * 0.45f, cy + r * 0.62f); path.lineTo(cx - r * 0.45f, cy + r * 0.62f)
        path.lineTo(cx - r * 0.45f, cy + r * 0.05f); path.lineTo(cx - r, cy + r * 0.05f); path.close()
        if (filled) { iconFill.color = color; c.drawPath(path, iconFill) } else { stroke(color, 1.6f * density); c.drawPath(path, iconPaint) }
        if (underline) { stroke(color, 1.8f * density); c.drawLine(cx - r * 0.5f, cy + r * 0.95f, cx + r * 0.5f, cy + r * 0.95f, iconPaint) }
    }

    private fun drawBackspace(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke(color, 1.6f * density)
        path.reset()
        val l = cx - r * 1.05f; val rt = cx + r; val t = cy - r * 0.62f; val b = cy + r * 0.62f
        path.moveTo(l + r * 0.5f, t); path.lineTo(rt, t); path.lineTo(rt, b); path.lineTo(l + r * 0.5f, b); path.lineTo(l, cy); path.close()
        c.drawPath(path, iconPaint)
        val xc = cx + r * 0.2f; val d = r * 0.26f
        c.drawLine(xc - d, cy - d, xc + d, cy + d, iconPaint); c.drawLine(xc - d, cy + d, xc + d, cy - d, iconPaint)
    }

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke(color, 1.6f * density)
        c.drawCircle(cx, cy, r, iconPaint)
        iconFill.color = color
        c.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.11f, iconFill); c.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.11f, iconFill)
        capRect.set(cx - r * 0.5f, cy - r * 0.35f, cx + r * 0.5f, cy + r * 0.55f)
        c.drawArc(capRect, 20f, 140f, false, iconPaint)
    }

    private fun drawGlobe(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke(color, 1.5f * density)
        c.drawCircle(cx, cy, r, iconPaint)
        c.drawLine(cx - r, cy, cx + r, cy, iconPaint)
        capRect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(capRect, iconPaint)
    }

    // ---- slide-to-type trail ---------------------------------------------------------------

    private var trail: List<TouchPoint>? = null
    private val trailFade = Spring(0f, response = 0.35f, dampingFraction = 1f)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val trailPath = Path()

    /** A soft line that tapers toward the start, like a light brush stroke. */
    private fun drawTrail(c: Canvas, pts: List<TouchPoint>, p: GlassPalette) {
        if (pts.size < 2) return
        val from = maxOf(0, pts.size - 90)
        val segs = 6
        val per = maxOf(1, (pts.size - from) / segs)
        for (sgi in 0 until segs) {
            val a = from + sgi * per
            val b = if (sgi == segs - 1) pts.size - 1 else minOf(pts.size - 1, a + per)
            if (b <= a) continue
            trailPath.reset(); trailPath.moveTo(pts[a].x, pts[a].y)
            for (i in a + 1..b) {
                val m = pts[i - 1]; val q = pts[i]
                trailPath.quadTo(m.x, m.y, (m.x + q.x) / 2, (m.y + q.y) / 2)
            }
            val f = (sgi + 1f) / segs
            trailPaint.strokeWidth = density * (2f + 4f * f)
            trailPaint.color = ColorUtils.setAlphaComponent(if (p.dark) Color.WHITE else Color.rgb(20, 110, 255), (150 * f * trailFade.value).toInt().coerceIn(0, 255))
            c.drawPath(trailPath, trailPaint)
        }
    }

    // ---- developer overlay ------------------------------------------------------------------

    var overlayEnabled = false
        set(v) { field = v; if (!v) { regionMap = null; taps.clear() }; invalidate() }
    private class TapMark(val p: TouchPoint, val d: TapDecision)
    private val taps = ArrayDeque<TapMark>()
    var lastDecideMicros = 0L
    private var regionMap: RegionMap? = null
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * resources.displayMetrics.density; typeface = Typeface.MONOSPACE }

    class RegionMap(val bitmap: Bitmap, val step: Float)

    private fun recordTap(p: TouchPoint, d: TapDecision) {
        taps.addLast(TapMark(p, d)); while (taps.size > 40) taps.removeFirst()
    }

    /**
     * Recomputes the effective-region map under the *current* prior. Called between taps
     * (never during one) and only while the overlay is on. Cost is logged on screen.
     */
    fun refreshRegionMap() {
        val lay = layout ?: return
        val l = listener ?: return
        if (!overlayEnabled || width == 0) return
        val step = 5 * density
        val cols = (lay.width / step).toInt() + 1; val rows = (lay.height / step).toInt() + 1
        val px = IntArray(cols * rows)
        val idx = HashMap<Key, Int>()
        lay.keys.forEachIndexed { i, k -> idx[k] = i }
        for (r in 0 until rows) for (col in 0 until cols) {
            val d = l.decide(lay, TouchPoint(col * step + step / 2, r * step + step / 2))
            val i = idx[d.key] ?: 0
            val hue = (i * 47) % 360
            val a = if (d.overrodeGeometry) 150 else 60
            px[r * cols + col] = ColorUtils.setAlphaComponent(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.75f, 1f)), a)
        }
        regionMap = RegionMap(Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888), step)
        invalidate()
    }

    private val srcRect = Rect(); private val dstRect = RectF()
    private val mapPaint = Paint().apply { isFilterBitmap = false }
    private fun drawRegionMap(c: Canvas, m: RegionMap) {
        srcRect.set(0, 0, m.bitmap.width, m.bitmap.height)
        dstRect.set(0f, 0f, m.bitmap.width * m.step, m.bitmap.height * m.step)
        c.drawBitmap(m.bitmap, srcRect, dstRect, mapPaint)
    }

    private fun drawTapOverlay(c: Canvas) {
        for ((i, t) in taps.withIndex()) {
            val age = (taps.size - i).toFloat() / taps.size
            dotPaint.color = ColorUtils.setAlphaComponent(if (t.d.overrodeGeometry) Color.rgb(255, 140, 0) else if (t.d.anchored) Color.rgb(0, 170, 90) else Color.rgb(40, 120, 255), (255 * (1.1f - age)).toInt().coerceIn(40, 255))
            c.drawCircle(t.p.x, t.p.y, 4.5f * density, dotPaint)
        }
        val last = taps.lastOrNull() ?: return
        val d = last.d
        val text = "${d.key.label} p=${"%.2f".format(d.posterior)} prior=${"%.2f".format(d.priorConfidence)}" +
            (if (d.anchored) " anchored" else "") + (if (d.overrodeGeometry) " (visible: ${d.geometricKey?.label})" else "") +
            "  decide=${lastDecideMicros}µs"
        infoPaint.color = if (renderer.palette?.dark == true) Color.WHITE else Color.BLACK
        c.drawText(text, 8 * density, 12 * density, infoPaint)
    }
}
