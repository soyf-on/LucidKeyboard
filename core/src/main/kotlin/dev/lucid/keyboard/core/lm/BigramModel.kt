package dev.lucid.keyboard.core.lm

import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Word-pair (bigram) counts over one language's [Lexicon] word ids, used for next-word
 * prediction and to make corrections fit the context ("this rnew" -> "this new", not
 * "this knew"). Probabilities use Witten–Bell interpolation with the unigram model, so
 * unseen pairs keep a sensible, non-zero probability.
 *
 * Layout: prev ids sorted ascending; for each, a contiguous run of (next id, count)
 * sorted by count descending. [START] stands for the beginning of a sentence.
 */
class BigramModel private constructor(
    private val prevIds: IntArray,
    private val offsets: IntArray, // size prevIds.size + 1
    private val nextIds: IntArray,
    private val counts: IntArray,
) {
    private val totals = IntArray(prevIds.size) { i -> var s = 0; for (j in offsets[i] until offsets[i + 1]) s += counts[j]; s }

    val size get() = nextIds.size

    private fun row(prev: Int): Int = java.util.Arrays.binarySearch(prevIds, prev)

    /** P(next | prev) interpolated with [pUnigram] (the unigram probability of next). */
    fun prob(prev: Int, next: Int, pUnigram: Double): Double {
        val r = row(prev)
        if (r < 0) return pUnigram
        val n = totals[r].toDouble()
        val types = (offsets[r + 1] - offsets[r]).toDouble()
        var c = 0
        for (j in offsets[r] until offsets[r + 1]) if (nextIds[j] == next) { c = counts[j]; break }
        return (c + types * pUnigram) / (n + types)
    }

    /** Most frequent continuations of [prev]: (word id, P(next | prev) ignoring smoothing). */
    fun top(prev: Int, k: Int): List<Pair<Int, Double>> {
        val r = row(prev)
        if (r < 0) return emptyList()
        val n = totals[r].toDouble()
        val out = ArrayList<Pair<Int, Double>>(k)
        for (j in offsets[r] until minOf(offsets[r + 1], offsets[r] + k)) out += nextIds[j] to counts[j] / n
        return out
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC); d.writeInt(prevIds.size); d.writeInt(nextIds.size)
        for (v in prevIds) d.writeInt(v)
        for (v in offsets) d.writeInt(v)
        for (v in nextIds) d.writeInt(v)
        for (v in counts) d.writeInt(v)
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x4247_0001
        /** Id used for "start of sentence". */
        const val START = Int.MAX_VALUE

        fun read(input: InputStream): BigramModel {
            val b = ByteBuffer.wrap(input.readBytes())
            require(b.int == MAGIC) { "bad bigram file" }
            val np = b.int; val nn = b.int
            fun ints(n: Int) = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + n * 4) }
            return BigramModel(ints(np), ints(np + 1), ints(nn), ints(nn))
        }

        /** Builds from "prev TAB next TAB count" lines; words must exist in [lex]. */
        fun fromTsv(file: File, lex: Lexicon): BigramModel {
            val rows = java.util.TreeMap<Int, MutableList<Pair<Int, Int>>>()
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size != 3) return@forEachLine
                val prev = if (p[0] == "<s>") START else lex.wordId(p[0])
                val next = lex.wordId(p[1])
                if (prev == -1 || next < 0) return@forEachLine
                rows.getOrPut(prev) { ArrayList() } += next to p[2].toInt()
            }
            val prevIds = IntArray(rows.size); val offsets = IntArray(rows.size + 1)
            val total = rows.values.sumOf { it.size }
            val nextIds = IntArray(total); val counts = IntArray(total)
            var i = 0; var j = 0
            for ((prev, list) in rows) {
                prevIds[i] = prev; offsets[i] = j
                // Merge duplicates (different spellings folding to one id), then sort by count.
                val merged = list.groupBy { it.first }.map { (id, v) -> id to v.sumOf { it.second } }.sortedByDescending { it.second }
                for ((id, c) in merged) { nextIds[j] = id; counts[j] = c; j++ }
                i++
            }
            offsets[i] = j
            return BigramModel(prevIds, offsets, nextIds.copyOf(j), counts.copyOf(j))
        }
    }
}
