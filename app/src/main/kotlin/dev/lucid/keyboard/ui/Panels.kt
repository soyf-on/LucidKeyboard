package dev.lucid.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.lucid.keyboard.core.correct.CorrectionMode

/** A small glass button that draws itself with the keyboard's renderer. */
@SuppressLint("ViewConstructor")
class GlassButton(context: Context, private val renderer: GlassRenderer, var label: String, private val onClick: () -> Unit) : View(context) {
    var selected2 = false; set(v) { field = v; invalidate() }
    var textSizeSp = 14f
    private val d = resources.displayMetrics.density
    private val r = RectF()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var down = false

    override fun onDraw(c: Canvas) {
        val p = renderer.palette ?: return
        r.set(2 * d, 3 * d, width - 2 * d, height - 3 * d)
        renderer.drawCap(c, r, if (selected2) CapStyle.ACCENT else CapStyle.LETTER, if (down) 1f else 0f, r.centerX(), r.centerY())
        paint.color = if (selected2) p.labelOnAccent else p.label
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = fitTextSize(paint, label, width - 20 * d, textSizeSp * d)
        val t = TextUtils.ellipsize(label, paint, width - 16 * d, TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        c.drawText(t, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() } }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}

/**
 * In-keyboard quick controls: the settings people change mid-conversation, without
 * leaving the app they're typing in.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context, renderer: GlassRenderer, private val actions: Actions) : LinearLayout(context) {
    interface Actions {
        fun setCorrection(m: CorrectionMode)
        fun toggleAdaptive(); fun toggleSuggestions(); fun toggleAutoCap(); fun togglePrivate()
        fun toggleLanguage(code: String)
        fun openSettings(page: String)
        fun closePanel()
    }

    private val d = resources.displayMetrics.density
    private val modeButtons = CorrectionMode.entries.associateWith { m ->
        GlassButton(context, renderer, m.name.lowercase().replaceFirstChar { it.uppercase() }) { actions.setCorrection(m) }
    }
    private val adaptive = GlassButton(context, renderer, "Adaptive targets") { actions.toggleAdaptive() }
    private val suggestions = GlassButton(context, renderer, "Suggestions") { actions.toggleSuggestions() }
    private val autoCap = GlassButton(context, renderer, "Auto-caps") { actions.toggleAutoCap() }
    private val privateMode = GlassButton(context, renderer, "Private mode") { actions.togglePrivate() }
    private val langButtons = dev.lucid.keyboard.core.lm.ModelBundle.LANGUAGES.associateWith { code ->
        GlassButton(context, renderer, dev.lucid.keyboard.core.lm.ModelBundle.NAMES[code] ?: code) { actions.toggleLanguage(code) }
    }
    private val title = TextView(context)

    init {
        orientation = VERTICAL
        setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        title.text = "Autocorrect"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        title.setPadding((6 * d).toInt(), 0, 0, (2 * d).toInt())
        addView(title)
        addView(row(modeButtons.values.toList()))
        addView(row(listOf(adaptive, suggestions)))
        addView(row(listOf(autoCap, privateMode)))
        addView(row(langButtons.values.toList()))
        addView(row(listOf(
            GlassButton(context, renderer, "Dictionary") { actions.openSettings("dictionary") },
            GlassButton(context, renderer, "Settings") { actions.openSettings("home") },
            GlassButton(context, renderer, "Done") { actions.closePanel() },
        )))
    }

    private fun row(views: List<View>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        for (v in views) addView(v, LayoutParams(0, (42 * d).toInt(), 1f))
    }

    fun bind(mode: CorrectionMode, adaptiveOn: Boolean, suggestionsOn: Boolean, autoCapOn: Boolean, privateOn: Boolean, languages: Set<String>, labelColor: Int) {
        langButtons.forEach { (c, b) -> b.selected2 = c in languages }
        modeButtons.forEach { (m, b) -> b.selected2 = m == mode }
        adaptive.selected2 = adaptiveOn; suggestions.selected2 = suggestionsOn
        autoCap.selected2 = autoCapOn; privateMode.selected2 = privateOn
        title.setTextColor(labelColor)
    }
}

/** Emoji grid with category tabs. Emoji the device font can't draw are hidden. */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val renderer: GlassRenderer, groups: LinkedHashMap<String, List<String>>, recent: List<String>, private val actions: Actions) : LinearLayout(context) {
    interface Actions { fun onEmoji(e: String); fun onBackspace(); fun onAbc() }

    private val d = resources.displayMetrics.density
    private val grid = GridView(context)
    private val tabs = LinearLayout(context)
    private val all = LinkedHashMap<String, List<String>>()
    private var current: List<String> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = current.size
        override fun getItem(p: Int) = current[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt())
            }
            tv.text = current[p]
            return tv
        }
    }

    init {
        orientation = VERTICAL
        val paint = Paint()
        if (recent.isNotEmpty()) all["Recent"] = recent
        for ((g, list) in groups) all[g] = list.filter { paint.hasGlyph(it) }
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = (46 * d).toInt()
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setOnItemClickListener { _, v, pos, _ -> v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); actions.onEmoji(current[pos]) }
        grid.isVerticalScrollBarEnabled = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(GlassButton(context, renderer, "ABC") { actions.onAbc() }, LayoutParams((64 * d).toInt(), (44 * d).toInt()))
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        bar.addView(scroller, LayoutParams(0, (44 * d).toInt(), 1f))
        bar.addView(GlassButton(context, renderer, "⌫") { actions.onBackspace() }, LayoutParams((64 * d).toInt(), (44 * d).toInt()))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val icons = mapOf("Recent" to "🕘", "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
            "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽", "Objects" to "💡", "Symbols" to "❤️", "Flags" to "🏁")
        for (g in all.keys) {
            val tv = TextView(context).apply {
                text = icons[g] ?: g.take(1); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                contentDescription = g
                setOnClickListener { select(g) }
            }
            tabs.addView(tv, LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        }
        all.keys.firstOrNull { it != "Recent" || all["Recent"]!!.isNotEmpty() }?.let { select(it) }
    }

    private fun select(g: String) {
        current = all[g].orEmpty()
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).alpha = if (tabs.getChildAt(i).contentDescription == g) 1f else 0.45f
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }
}
