package dev.lucid.keyboard.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class KeyKind {
    LETTER, SPACE, SHIFT, BACKSPACE, ENTER, TO_SYMBOLS, TO_LETTERS, TO_MORE_SYMBOLS,
    EMOJI, SWITCH_IME, CHAR, SETTINGS,
}

/**
 * A key in layout coordinates (pixels of the keyboard view, origin top-left).
 * [x],[y],[w],[h] is the *visible* key cap. [hit] is the visible cap grown by half
 * of the gap to each neighbour, so the geometric partition has no dead zones.
 */
class Key(
    val kind: KeyKind,
    /** Text produced (for LETTER/CHAR), lower-case for letters. Empty for function keys. */
    val output: String,
    val label: String,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val row: Int,
    val longPress: List<String> = emptyList(),
) {
    var hitLeft = x; var hitTop = y; var hitRight = x + w; var hitBottom = y + h
        internal set
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val char: Char get() = output.firstOrNull() ?: ' '

    /** True for keys whose effective target may be adapted by the probabilistic decoder. */
    val isDecodable get() = kind == KeyKind.LETTER || kind == KeyKind.SPACE

    fun hitContains(px: Float, py: Float) = px >= hitLeft && px < hitRight && py >= hitTop && py < hitBottom

    fun distanceToHit(px: Float, py: Float): Float {
        val dx = max(0f, max(hitLeft - px, px - hitRight))
        val dy = max(0f, max(hitTop - py, py - hitBottom))
        return sqrt(dx * dx + dy * dy)
    }

    override fun toString() = "Key($label)"
}

class KeyboardLayout(
    val name: String,
    val keys: List<Key>,
    val width: Float,
    val height: Float,
    /** Width of a standard letter key: the unit for the touch model. */
    val unitW: Float,
    /** Height of a standard key row (cap height). */
    val unitH: Float,
) {
    val decodableKeys = keys.filter { it.isDecodable }
    val letterKeys = keys.filter { it.kind == KeyKind.LETTER }
    private val byChar = letterKeys.associateBy { it.char }
    val space: Key? = keys.firstOrNull { it.kind == KeyKind.SPACE }

    fun letter(c: Char): Key? = byChar[c.lowercaseChar()]

    /** Purely geometric hit-test on the visible partition (the fixed-target baseline). */
    fun geometricKeyAt(px: Float, py: Float): Key? {
        keys.firstOrNull { it.hitContains(px, py) }?.let { return it }
        return keys.minByOrNull { it.distanceToHit(px, py) }
    }
}

data class LayoutParams(
    val width: Float,
    val rowHeight: Float,
    val hGap: Float,
    val vGap: Float,
    val sidePadding: Float,
    val topPadding: Float,
    val bottomPadding: Float,
    /** Extra key beside space for URL ("/") or e-mail ("@") fields; null for normal text. */
    val extraKey: String? = null,
)

object Layouts {
    /** Alternate that opens the emoji panel instead of typing a character. */
    const val EMOJI_ALTERNATE = "😊"

    private val TOP = "qwertyuiop"
    private val MID = "asdfghjkl"
    private val BOT = "zxcvbnm"
    private val TOP_DIGITS = "1234567890"

    val ACCENTS: Map<Char, List<String>> = mapOf(
        'a' to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        'c' to listOf("ç", "ć", "č"),
        'e' to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        'i' to listOf("î", "ï", "í", "ī", "į", "ì"),
        'l' to listOf("ł"),
        'n' to listOf("ñ", "ń"),
        'o' to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        's' to listOf("ß", "ś", "š"),
        'u' to listOf("û", "ü", "ù", "ú", "ū"),
        'y' to listOf("ÿ"),
        'z' to listOf("ž", "ź", "ż"),
    )

    fun qwerty(p: LayoutParams): KeyboardLayout {
        val usable = p.width - 2 * p.sidePadding
        val unit = usable / 10f
        val keyW = unit - p.hGap
        val keys = ArrayList<Key>()
        fun rowY(r: Int) = p.topPadding + r * (p.rowHeight + p.vGap) + p.vGap / 2f

        // Row 0: letters with digit long-press.
        TOP.forEachIndexed { i, c ->
            val x = p.sidePadding + i * unit + p.hGap / 2f
            keys += Key(KeyKind.LETTER, c.toString(), c.toString(), x, rowY(0), keyW, p.rowHeight, 0,
                listOf(TOP_DIGITS[i].toString()) + ACCENTS[c].orEmpty())
        }
        // Row 1: offset by half a key.
        MID.forEachIndexed { i, c ->
            val x = p.sidePadding + (i + 0.5f) * unit + p.hGap / 2f
            keys += Key(KeyKind.LETTER, c.toString(), c.toString(), x, rowY(1), keyW, p.rowHeight, 1, ACCENTS[c].orEmpty())
        }
        // Row 2: shift, 7 letters (offset 1.5 units), backspace.
        val fnW = 1.5f * unit - p.hGap - unit * 0.12f
        keys += Key(KeyKind.SHIFT, "", "shift", p.sidePadding + p.hGap / 2f, rowY(2), fnW, p.rowHeight, 2)
        BOT.forEachIndexed { i, c ->
            val x = p.sidePadding + (i + 1.5f) * unit + p.hGap / 2f
            keys += Key(KeyKind.LETTER, c.toString(), c.toString(), x, rowY(2), keyW, p.rowHeight, 2, ACCENTS[c].orEmpty())
        }
        keys += Key(KeyKind.BACKSPACE, "", "delete", p.width - p.sidePadding - p.hGap / 2f - fnW, rowY(2), fnW, p.rowHeight, 2)

        bottomRow(p, unit, rowY(3), keys, KeyKind.TO_SYMBOLS, "123")
        return finish("qwerty", keys, p, unit, keyW)
    }

    /** Long-press on the period key: comma first (hold-and-release types it), then punctuation and emoji. */
    val PERIOD_ALTERNATES = listOf(",", "?", "!", "'", "\"", ":", ";", "-", "…", EMOJI_ALTERNATE)

    /**
     * Clean bottom row, like iOS: mode key, space, period, return. Emoji, comma and the
     * keyboard switcher live on long-presses and in the suggestion bar instead.
     */
    private fun bottomRow(p: LayoutParams, unit: Float, y: Float, keys: MutableList<Key>, modeKind: KeyKind, modeLabel: String) {
        data class Spec(val kind: KeyKind, val out: String, val label: String, val units: Float, val lp: List<String> = emptyList())
        val specs = ArrayList<Spec>()
        specs += Spec(modeKind, "", modeLabel, 1.5f)
        p.extraKey?.let { specs += Spec(KeyKind.CHAR, it, it, 1.0f, if (it == "@") listOf(".", "_", "-") else listOf(".com", ".nl", ":", "-", "_")) }
        val fixed = specs.sumOf { it.units.toDouble() }.toFloat() + 1.0f + 2.0f
        specs += Spec(KeyKind.SPACE, " ", "space", 10f - fixed)
        specs += Spec(KeyKind.CHAR, ".", ".", 1.0f, PERIOD_ALTERNATES)
        specs += Spec(KeyKind.ENTER, "\n", "return", 2.0f)
        var x = p.sidePadding
        for (s in specs) {
            keys += Key(s.kind, s.out, s.label, x + p.hGap / 2f, y, s.units * unit - p.hGap, p.rowHeight, 3, s.lp)
            x += s.units * unit
        }
    }

    fun symbols(p: LayoutParams, page: Int): KeyboardLayout {
        val usable = p.width - 2 * p.sidePadding
        val unit = usable / 10f
        val keyW = unit - p.hGap
        val keys = ArrayList<Key>()
        fun rowY(r: Int) = p.topPadding + r * (p.rowHeight + p.vGap) + p.vGap / 2f
        val rows = if (page == 0) listOf(
            "1234567890", "-/:;()$&@\"", ".,?!'",
        ) else listOf(
            "[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'",
        )
        val alt: Map<String, List<String>> = mapOf(
            "0" to listOf("°"), "$" to listOf("€", "£", "¥", "₹", "¢"), "-" to listOf("–", "—", "·"),
            "\"" to listOf("“", "”", "„"), "'" to listOf("‘", "’"), "?" to listOf("¿"), "!" to listOf("¡"),
            "." to listOf("…"), "&" to listOf("§"), "%" to listOf("‰"),
        )
        for (r in 0..1) rows[r].forEachIndexed { i, c ->
            keys += Key(KeyKind.CHAR, c.toString(), c.toString(), p.sidePadding + i * unit + p.hGap / 2f, rowY(r), keyW, p.rowHeight, r, alt[c.toString()].orEmpty())
        }
        val fnW = 1.5f * unit - p.hGap - unit * 0.12f
        keys += Key(KeyKind.TO_MORE_SYMBOLS, "", if (page == 0) "#+=" else "123", p.sidePadding + p.hGap / 2f, rowY(2), fnW, p.rowHeight, 2)
        val midUnit = (10f - 3f) / rows[2].length * unit
        rows[2].forEachIndexed { i, c ->
            keys += Key(KeyKind.CHAR, c.toString(), c.toString(), p.sidePadding + 1.5f * unit + i * midUnit + p.hGap / 2f, rowY(2), midUnit - p.hGap, p.rowHeight, 2, alt[c.toString()].orEmpty())
        }
        keys += Key(KeyKind.BACKSPACE, "", "delete", p.width - p.sidePadding - p.hGap / 2f - fnW, rowY(2), fnW, p.rowHeight, 2)
        bottomRow(p, unit, rowY(3), keys, KeyKind.TO_LETTERS, "ABC")
        return finish(if (page == 0) "symbols" else "symbols2", keys, p, unit, keyW)
    }

    /** Numeric / phone pad for number, phone and date fields. */
    fun numpad(p: LayoutParams, phone: Boolean): KeyboardLayout {
        val usable = p.width - 2 * p.sidePadding
        val unit = usable / 4f
        val keys = ArrayList<Key>()
        fun rowY(r: Int) = p.topPadding + r * (p.rowHeight + p.vGap) + p.vGap / 2f
        val grid = listOf(
            listOf("1", "2", "3", "-"),
            listOf("4", "5", "6", if (phone) "+" else ","),
            listOf("7", "8", "9", "⌫"),
            listOf(if (phone) "*" else "ABC", "0", if (phone) "#" else ".", "⏎"),
        )
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { i, s ->
                val x = p.sidePadding + i * unit + p.hGap / 2f
                val kind = when (s) {
                    "⌫" -> KeyKind.BACKSPACE
                    "⏎" -> KeyKind.ENTER
                    "ABC" -> KeyKind.TO_LETTERS
                    else -> KeyKind.CHAR
                }
                val out = when (kind) { KeyKind.CHAR -> s; KeyKind.ENTER -> "\n"; else -> "" }
                val lp = if (s == "0" && phone) listOf("+") else if (s == "." ) listOf(",") else emptyList()
                keys += Key(kind, out, s, x, rowY(r), unit - p.hGap, p.rowHeight, r, lp)
            }
        }
        return finish(if (phone) "phone" else "numpad", keys, p, unit, unit - p.hGap)
    }

    fun totalHeight(p: LayoutParams, rows: Int = 4) =
        p.topPadding + rows * (p.rowHeight + p.vGap) + p.bottomPadding

    private fun finish(name: String, keys: List<Key>, p: LayoutParams, unit: Float, keyW: Float): KeyboardLayout {
        val height = totalHeight(p)
        // Grow each cap into its share of the gaps: split halfway to neighbours in
        // the same row and to the row boundaries. Edge keys extend to the view edge.
        val rows = keys.groupBy { it.row }
        val maxRow = rows.keys.max()
        for ((r, rowKeys) in rows) {
            val sorted = rowKeys.sortedBy { it.x }
            val top = if (r == 0) 0f else sorted.first().y - p.vGap / 2f
            val bottom = if (r == maxRow) height else sorted.first().y + p.rowHeight + p.vGap / 2f
            sorted.forEachIndexed { i, k ->
                k.hitTop = top
                k.hitBottom = bottom
                k.hitLeft = if (i == 0) 0f else (sorted[i - 1].x + sorted[i - 1].w + k.x) / 2f
                k.hitRight = if (i == sorted.lastIndex) p.width else (k.x + k.w + sorted[i + 1].x) / 2f
            }
        }
        return KeyboardLayout(name, keys, p.width, height, unit, p.rowHeight + p.vGap)
    }
}

internal fun clamp(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
internal fun absf(v: Float) = abs(v)
