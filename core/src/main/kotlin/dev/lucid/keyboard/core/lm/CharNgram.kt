package dev.lucid.keyboard.core.lm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Character 4-gram model over [Alphabet] with Witten–Bell interpolation, trained on
 * word *types* (each dictionary word counts once) so it captures English spelling
 * patterns rather than the frequency of "the". Used only as the fallback prior
 * when the letters typed so far are not a prefix of any known word.
 */
class CharNgram private constructor(private val rows: HashMap<String, ByteArray>) {

    /** Fills [out] (size [Alphabet.SIZE]) with P(symbol | history) where history is the in-word prefix. */
    fun distribution(prefix: CharSequence, out: DoubleArray) {
        val row = rows[contextKey(prefix)] ?: rows[contextKey(prefix, 2)] ?: rows[contextKey(prefix, 1)] ?: rows[""]!!
        var sum = 0.0
        for (i in 0 until Alphabet.SIZE) { out[i] = exp(-(row[i].toInt() and 0xFF) / SCALE); sum += out[i] }
        for (i in 0 until Alphabet.SIZE) out[i] /= sum
    }

    fun logProbWord(word: CharSequence): Double {
        val tmp = DoubleArray(Alphabet.SIZE)
        var lp = 0.0
        for (i in 0..word.length) {
            distribution(word.subSequence(0, i), tmp)
            val s = if (i == word.length) Alphabet.END else Alphabet.index(word[i]).takeIf { it >= 0 } ?: return -30.0
            lp += ln(tmp[s])
        }
        return lp
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(rows.size)
        for ((k, v) in rows) { d.writeUTF(k); d.write(v) }
        d.flush()
    }

    companion object {
        private const val ORDER = 3 // history length
        private const val SCALE = 12.0

        private fun contextKey(prefix: CharSequence, len: Int = ORDER): String {
            val sb = StringBuilder()
            val padded = "^" + prefix.toString().map { Alphabet.fold(it) ?: it.lowercaseChar() }.joinToString("")
            val start = maxOf(0, padded.length - len)
            sb.append(padded, start, padded.length)
            return sb.toString()
        }

        fun read(input: InputStream): CharNgram {
            val d = DataInputStream(input.buffered())
            val n = d.readInt()
            val rows = HashMap<String, ByteArray>(n * 2)
            repeat(n) { val k = d.readUTF(); val v = ByteArray(Alphabet.SIZE); d.readFully(v); rows[k] = v }
            return CharNgram(rows)
        }

        fun train(words: Sequence<String>): CharNgram {
            // counts[context][symbol] for context lengths 0..ORDER
            val counts = HashMap<String, IntArray>()
            for (w0 in words) {
                val w = w0.lowercase()
                if (w.any { Alphabet.index(it) < 0 }) continue
                val padded = "^" + w.map { Alphabet.fold(it)!! }.joinToString("")
                for (i in 1..padded.length) {
                    val sym = if (i == padded.length) Alphabet.END else Alphabet.index(padded[i])
                    for (len in 0..ORDER) {
                        if (i - len < 0) break
                        val ctx = padded.substring(i - len, i)
                        counts.getOrPut(ctx) { IntArray(Alphabet.SIZE) }[sym]++
                    }
                }
            }
            val probs = HashMap<String, DoubleArray>()
            fun smoothed(ctx: String): DoubleArray = probs.getOrPut(ctx) {
                val c = counts[ctx]!!
                val lower = if (ctx.isEmpty()) DoubleArray(Alphabet.SIZE) { 1.0 / Alphabet.SIZE }
                else smoothed(ctx.substring(1).let { if (counts.containsKey(it)) it else "" })
                val total = c.sum().toDouble()
                val types = c.count { it > 0 }.toDouble()
                DoubleArray(Alphabet.SIZE) { (c[it] + types * lower[it]) / (total + types) }
            }
            val rows = HashMap<String, ByteArray>()
            for (ctx in counts.keys) {
                if (counts[ctx]!!.sum() < 3 && ctx.isNotEmpty()) continue // too sparse; back off instead
                val p = smoothed(ctx)
                rows[ctx] = ByteArray(Alphabet.SIZE) { (-ln(p[it]) * SCALE).roundToInt().coerceIn(0, 255).toByte() }
            }
            return CharNgram(rows)
        }
    }
}
