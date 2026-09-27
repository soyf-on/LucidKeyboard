package dev.lucid.keyboard.core.lm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.pow

/** Symbols the character model predicts: a–z, apostrophe, and END (word boundary). */
object Alphabet {
    const val APOS = 26
    const val END = 27
    const val SIZE = 28
    fun index(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        '\'', '’' -> APOS
        else -> -1
    }
    fun char(i: Int): Char = if (i < 26) 'a' + i else if (i == APOS) '\'' else ' '
}

/**
 * Frequency-weighted word trie stored in flat arrays (≈100k words, ≈250k nodes).
 * Node 0 is the root. Children of node n are the contiguous range
 * [firstChild[n], firstChild[n] + childCount[n]) sorted by symbol.
 */
class Lexicon private constructor(
    private val firstChild: IntArray,
    private val childCount: ByteArray,
    private val symbol: ByteArray,
    /** Word id if this node ends a word, else -1. */
    private val wordAt: IntArray,
    /** Sum of word weights in the subtree (incl. this node's word). */
    private val mass: FloatArray,
    /** Largest single word weight in the subtree (for pruning searches). */
    private val maxWeight: FloatArray,
    private val weights: FloatArray,
    private val flags: ByteArray,
    private val casedText: String,
    private val casedOffsets: IntArray,
) {
    val nodeCount get() = firstChild.size
    val wordCount get() = weights.size
    val totalMass: Float get() = mass[0]

    fun child(node: Int, sym: Int): Int {
        val start = firstChild[node]
        val n = childCount[node].toInt() and 0xFF
        // Children are few (≤ 28); linear scan beats binary search here.
        for (i in start until start + n) if (symbol[i].toInt() == sym) return i
        return -1
    }

    inline fun forEachChild(node: Int, block: (sym: Int, child: Int) -> Unit) {
        val start = firstChildOf(node)
        val n = childCountOf(node)
        for (i in start until start + n) block(symbolOf(i), i)
    }

    fun firstChildOf(node: Int) = firstChild[node]
    fun childCountOf(node: Int) = childCount[node].toInt() and 0xFF
    fun symbolOf(node: Int) = symbol[node].toInt()
    fun massOf(node: Int) = mass[node]
    fun maxWeightOf(node: Int) = maxWeight[node]
    fun wordIdAt(node: Int) = wordAt[node]
    fun endWeight(node: Int): Float = wordAt[node].let { if (it < 0) 0f else weights[it] }

    fun weight(wordId: Int) = weights[wordId]
    fun isSpellChecked(wordId: Int) = flags[wordId].toInt() == 1
    fun cased(wordId: Int): String = casedText.substring(casedOffsets[wordId], casedOffsets[wordId + 1])

    /** Node reached by [prefix] (case-insensitive), or -1. */
    fun nodeFor(prefix: CharSequence): Int {
        var n = 0
        for (c in prefix) {
            val s = Alphabet.index(c)
            if (s < 0) return -1
            n = child(n, s)
            if (n < 0) return -1
        }
        return n
    }

    fun wordId(word: CharSequence): Int {
        val n = nodeFor(word)
        return if (n < 0) -1 else wordAt[n]
    }

    fun contains(word: CharSequence) = wordId(word) >= 0

    /** log P(word) under the unigram model, or null if unknown. */
    fun logProb(word: CharSequence): Double? {
        val id = wordId(word)
        return if (id < 0) null else kotlin.math.ln(weights[id].toDouble() / totalMass)
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(nodeCount); d.writeInt(wordCount)
        for (v in firstChild) d.writeInt(v)
        d.write(childCount); d.write(symbol)
        for (v in wordAt) d.writeInt(v)
        for (v in mass) d.writeFloat(v)
        for (v in maxWeight) d.writeFloat(v)
        for (v in weights) d.writeFloat(v)
        d.write(flags)
        for (v in casedOffsets) d.writeInt(v)
        val bytes = casedText.toByteArray(Charsets.UTF_8)
        d.writeInt(bytes.size); d.write(bytes)
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x4C58_0001

        /** Word weight from a Zipf value (log10 frequency per billion words). */
        fun weightFromZipf(zipf: Double): Float = 10.0.pow(zipf - 3.0).toFloat()

        fun read(input: InputStream): Lexicon {
            val all = input.readBytes()
            val b = ByteBuffer.wrap(all)
            require(b.int == MAGIC) { "bad lexicon file" }
            val nodes = b.int; val words = b.int
            val firstChild = IntArray(nodes).also { b.asIntBuffer().get(it); b.position(b.position() + nodes * 4) }
            val childCount = ByteArray(nodes).also { b.get(it) }
            val symbol = ByteArray(nodes).also { b.get(it) }
            val wordAt = IntArray(nodes).also { b.asIntBuffer().get(it); b.position(b.position() + nodes * 4) }
            val mass = FloatArray(nodes).also { b.asFloatBuffer().get(it); b.position(b.position() + nodes * 4) }
            val maxW = FloatArray(nodes).also { b.asFloatBuffer().get(it); b.position(b.position() + nodes * 4) }
            val weights = FloatArray(words).also { b.asFloatBuffer().get(it); b.position(b.position() + words * 4) }
            val flags = ByteArray(words).also { b.get(it) }
            val offs = IntArray(words + 1).also { b.asIntBuffer().get(it); b.position(b.position() + (words + 1) * 4) }
            val len = b.int
            val text = String(all, b.position(), len, Charsets.UTF_8)
            return Lexicon(firstChild, childCount, symbol, wordAt, mass, maxW, weights, flags, text, offs)
        }

        fun readTsv(file: File): Lexicon = file.bufferedReader().useLines { lines ->
            build(lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null else Entry(p[0], weightFromZipf(p[1].toInt() / 100.0), p.getOrNull(2) == "V")
            }.toList())
        }

        data class Entry(val cased: String, val weight: Float, val spellChecked: Boolean)

        /** Builds the flat trie. Words that contain symbols outside [Alphabet] are skipped. */
        fun build(entries: List<Entry>): Lexicon {
            class N { val kids = java.util.TreeMap<Int, N>(); var word = -1 }
            val root = N()
            val kept = ArrayList<Entry>()
            for (e in entries) {
                if (e.cased.isEmpty() || e.cased.any { Alphabet.index(it) < 0 }) continue
                var n = root
                for (c in e.cased) n = n.kids.getOrPut(Alphabet.index(c)) { N() }
                if (n.word >= 0) {
                    // Same word with different casing: keep the more frequent form, sum weights.
                    val old = kept[n.word]
                    val keepNew = e.weight > old.weight
                    kept[n.word] = Entry(if (keepNew) e.cased else old.cased, old.weight + e.weight, old.spellChecked || e.spellChecked)
                    continue
                }
                n.word = kept.size
                kept += e
            }
            // Breadth-first layout so each node's children are contiguous.
            val order = ArrayList<N>(); val syms = ArrayList<Int>()
            order += root; syms += 0
            val first = ArrayList<Int>(); val count = ArrayList<Int>()
            var i = 0
            while (i < order.size) {
                val n = order[i]
                first += order.size; count += n.kids.size
                for ((s, k) in n.kids) { order += k; syms += s }
                i++
            }
            val size = order.size
            val wordAt = IntArray(size) { order[it].word }
            val weights = FloatArray(kept.size) { kept[it].weight }
            val mass = FloatArray(size); val maxW = FloatArray(size)
            for (j in size - 1 downTo 0) {
                val w = wordAt[j].let { if (it < 0) 0f else weights[it] }
                var m = w; var mx = w
                for (c in first[j] until first[j] + count[j]) { m += mass[c]; if (maxW[c] > mx) mx = maxW[c] }
                mass[j] = m; maxW[j] = mx
            }
            val sb = StringBuilder(); val offs = IntArray(kept.size + 1)
            kept.forEachIndexed { idx, e -> offs[idx] = sb.length; sb.append(e.cased) }
            offs[kept.size] = sb.length
            return Lexicon(
                first.toIntArray(), ByteArray(size) { count[it].toByte() }, ByteArray(size) { syms[it].toByte() },
                wordAt, mass, maxW, weights, ByteArray(kept.size) { if (kept[it].spellChecked) 1 else 0 },
                sb.toString(), offs,
            )
        }
    }
}
