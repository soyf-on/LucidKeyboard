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
        /** Background for the current app: AUTO, CLEAR or TINTED. */
        fun setBackdrop(mode: String)
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
    private val backdropButtons = listOf("AUTO" to "Auto bg", "CLEAR" to "Clear bg", "TINTED" to "Tinted bg").associate { (m, label) ->
        m to GlassButton(context, renderer, label) { actions.setBackdrop(m) }
    }

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
        addView(row(langButtons.values.toList() + backdropButtons.values.toList()))
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

    fun bind(mode: CorrectionMode, adaptiveOn: Boolean, suggestionsOn: Boolean, autoCapOn: Boolean, privateOn: Boolean, languages: Set<String>, backdrop: String, labelColor: Int) {
        backdropButtons.forEach { (m, b) -> b.selected2 = m == backdrop }
        langButtons.forEach { (c, b) -> b.selected2 = c in languages }
        modeButtons.forEach { (m, b) -> b.selected2 = m == mode }
        adaptive.selected2 = adaptiveOn; suggestions.selected2 = suggestionsOn
        autoCap.selected2 = autoCapOn; privateMode.selected2 = privateOn
        title.setTextColor(labelColor)
    }
}

/** Emoji with their group and search keywords (English + Dutch). */
class EmojiData(val groups: LinkedHashMap<String, List<String>>, val keywords: Map<String, String>) {
    /** Keyword search: whole-word prefix matches, best (name-first) matches first. */
    fun search(query: String, limit: Int = 48): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        // Rank by match quality (exact keyword, keyword prefix, word prefix), then by the
        // standard Unicode order, which lists the most familiar emoji first.
        val scored = ArrayList<Pair<String, Int>>()
        var order = 0
        for (list in groups.values) for (e in list) {
            order++
            val kw = keywords[e] ?: continue
            var tier = Int.MAX_VALUE
            for (part in kw.split(" | ")) {
                val t = when {
                    part == q -> 0
                    part.startsWith(q) -> 1
                    part.split(' ').any { it.startsWith(q) } -> 2
                    else -> Int.MAX_VALUE
                }
                tier = minOf(tier, t)
            }
            if (tier != Int.MAX_VALUE) scored += e to (tier * 100_000 + order)
        }
        return scored.sortedBy { it.second }.map { it.first }.distinct().take(limit)
    }
}

/**
 * Emoji panel: search field, Recent first, category tabs. Tapping the search field swaps
 * the grid for a results row and a compact letter keyboard that types into the query.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val renderer: GlassRenderer,
    private val data: EmojiData,
    recent: List<String>,
    private val actions: Actions,
    private val panelHeight: Int,
    private val searchKeyboard: (KeyboardView.Listener) -> KeyboardView,
) : LinearLayout(context) {
    interface Actions { fun onEmoji(e: String); fun onBackspace(); fun onAbc() }

    private val d = resources.displayMetrics.density
    private val grid = GridView(context)
    private val tabs = LinearLayout(context)
    private val all = LinkedHashMap<String, List<String>>()
    private var current: List<String> = emptyList()
    private val paint = Paint()
    private var query = ""
    private val searchField = SearchField(context)
    private val results = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val resultsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(results) }
    private val browse = LinearLayout(context).apply { orientation = VERTICAL }
    private var searchKb: KeyboardView? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = current.size
        override fun getItem(p: Int) = current[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: emojiCell()
            tv.text = current[p]
            return tv
        }
    }

    private fun emojiCell() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt())
    }

    /** Glass pill that shows the query; tap to start searching. */
    private inner class SearchField(ctx: Context) : View(ctx) {
        private val r = RectF()
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d }
        private val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        var active = false; set(v) { field = v; invalidate() }
        override fun onDraw(c: Canvas) {
            val p = renderer.palette ?: return
            r.set(8 * d, 5 * d, width - 8 * d, height - 5 * d)
            renderer.drawCap(c, r, CapStyle.FUNCTION, 0f, r.centerX(), r.centerY())
            ip.color = p.labelSecondary; ip.strokeWidth = 1.6f * d
            val cx = r.left + 20 * d; val cy = r.centerY()
            c.drawCircle(cx - 1.5f * d, cy - 1.5f * d, 5.5f * d, ip); c.drawLine(cx + 2.5f * d, cy + 2.5f * d, cx + 6.5f * d, cy + 6.5f * d, ip)
            val empty = query.isEmpty()
            tp.color = if (empty) p.labelSecondary else p.label
            val text = if (empty) "Search emoji" else query
            val fm = tp.fontMetrics
            c.drawText(TextUtils.ellipsize(text, tp, r.width() - 70 * d, TextUtils.TruncateAt.START).toString(), r.left + 36 * d, cy - (fm.ascent + fm.descent) / 2, tp)
            if (active) { // caret / clear
                ip.color = p.label
                c.drawLine(r.right - 22 * d, cy - 5 * d, r.right - 12 * d, cy + 5 * d, ip); c.drawLine(r.right - 22 * d, cy + 5 * d, r.right - 12 * d, cy - 5 * d, ip)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (active && e.x > width - 44 * d) { if (query.isEmpty()) endSearch() else setQuery("") } else if (!active) startSearch()
            }
            return true
        }
    }

    init {
        orientation = VERTICAL
        if (recent.isNotEmpty()) all["Recent"] = recent.filter { paint.hasGlyph(it) }
        for ((g, list) in data.groups) all[g] = list.filter { paint.hasGlyph(it) }
        addView(searchField, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt()))

        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = (46 * d).toInt()
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setOnItemClickListener { _, v, pos, _ -> v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); actions.onEmoji(current[pos]) }
        grid.isVerticalScrollBarEnabled = false
        browse.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(GlassButton(context, renderer, "ABC") { actions.onAbc() }, LayoutParams((64 * d).toInt(), (44 * d).toInt()))
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        bar.addView(scroller, LayoutParams(0, (44 * d).toInt(), 1f))
        bar.addView(GlassButton(context, renderer, "⌫") { actions.onBackspace() }, LayoutParams((64 * d).toInt(), (44 * d).toInt()))
        browse.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(browse, LayoutParams(LayoutParams.MATCH_PARENT, panelHeight - (44 * d).toInt()))

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
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).animate().alpha(if (tabs.getChildAt(i).contentDescription == g) 1f else 0.4f).setDuration(180).start()
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun startSearch() {
        searchField.active = true
        removeView(browse)
        addView(resultsScroll, 1, LayoutParams(LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        val kb = searchKeyboard(object : KeyboardView.Listener {
            override fun decide(layout: dev.lucid.keyboard.core.geometry.KeyboardLayout, point: dev.lucid.keyboard.core.touch.TouchPoint) =
                dev.lucid.keyboard.core.touch.TapDecision(layout.geometricKeyAt(point.x, point.y)!!, null, 1.0, false, 0.0, emptyList())
            override fun onKeyCommitted(key: dev.lucid.keyboard.core.geometry.Key, point: dev.lucid.keyboard.core.touch.TouchPoint, decision: dev.lucid.keyboard.core.touch.TapDecision) {
                if (key.kind == dev.lucid.keyboard.core.geometry.KeyKind.SPACE) setQuery("$query ") else setQuery(query + key.output)
            }
            override fun onText(text: String) = setQuery(query + text)
            override fun onBackspace() { if (query.isNotEmpty()) setQuery(query.dropLast(1)) }
            override fun onShift(doubleTap: Boolean) {}
            override fun onFunctionKey(key: dev.lucid.keyboard.core.geometry.Key) {
                when (key.kind) { dev.lucid.keyboard.core.geometry.KeyKind.ENTER -> endSearch(); else -> {} }
            }
            override fun onCursorMove(steps: Int) {}
            override fun onKeyDownFeedback(key: dev.lucid.keyboard.core.geometry.Key) {}
        })
        searchKb = kb
        addView(kb)
        setQuery("")
    }

    private fun endSearch() {
        searchField.active = false
        query = ""
        searchKb?.let { removeView(it) }; searchKb = null
        removeView(resultsScroll)
        addView(browse, LayoutParams(LayoutParams.MATCH_PARENT, panelHeight - (44 * d).toInt()))
        searchField.invalidate()
    }

    private fun setQuery(q: String) {
        query = q
        searchField.invalidate()
        results.removeAllViews()
        val found = if (q.isBlank()) all["Recent"].orEmpty().take(24) else data.search(q).filter { paint.hasGlyph(it) }
        for (e in found) results.addView(emojiCell().apply {
            text = e
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); actions.onEmoji(e) }
        }, LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        if (found.isEmpty() && q.isNotBlank()) results.addView(TextView(context).apply {
            text = "No emoji for “${q.trim()}”"; setTextColor(renderer.palette?.labelSecondary ?: 0xFF888888.toInt()); setPadding((16 * d).toInt(), 0, 0, 0)
        })
        resultsScroll.scrollTo(0, 0)
    }
}

/**
 * Clipboard panel: recently copied text (memory only, see ClipboardHistory). Tap an item
 * to paste it, long-press to remove it.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val renderer: GlassRenderer,
    items: List<String>,
    private val actions: Actions,
    panelHeight: Int,
) : LinearLayout(context) {
    interface Actions { fun onPaste(text: String); fun onRemoveClip(text: String); fun onClearClips(); fun onAbc() }

    private val d = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding((10 * d).toInt(), 0, (10 * d).toInt(), (6 * d).toInt()) }
    private var current = items

    init {
        orientation = VERTICAL
        val p = renderer.palette
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((16 * d).toInt(), 0, (6 * d).toInt(), 0) }
        header.addView(TextView(context).apply {
            text = "Clipboard"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p?.let { setTextColor(it.label) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(GlassButton(context, renderer, "Clear") { actions.onClearClips(); show(emptyList()) }, LayoutParams((84 * d).toInt(), (40 * d).toInt()))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt()))
        val scroll = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, panelHeight - (44 * d).toInt() - (48 * d).toInt()))
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(GlassButton(context, renderer, "ABC") { actions.onAbc() }, LayoutParams((64 * d).toInt(), (44 * d).toInt()))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (48 * d).toInt()))
        show(items)
    }

    private fun show(items: List<String>) {
        current = items
        list.removeAllViews()
        val p = renderer.palette ?: return
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Text you copy appears here.\nKept in memory only — never saved, and passwords marked sensitive are skipped."
                setTextColor(p.labelSecondary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), 0)
            })
            return
        }
        for (t in items) {
            val row = TextView(context).apply {
                text = t
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(p.label)
                setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(p.chip) }
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); actions.onPaste(t) }
                setOnLongClickListener { actions.onRemoveClip(t); show(current - t); true }
            }
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        }
    }
}
