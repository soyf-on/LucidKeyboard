package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.eval.RecordedTap
import dev.lucid.keyboard.core.eval.Replay
import dev.lucid.keyboard.core.eval.TapStats
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.TypingSettings
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.TouchPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Compares fixed-geometry and adaptive decoding on identical synthetic touch sequences
 * and writes docs/EVALUATION.md. Synthetic data shows how the decoder behaves under
 * a stated noise model; it is not evidence about real-world typing.
 */
class EvaluationTest {
    private val layout = Fixtures.layout
    private val lm = Fixtures.lm()

    private fun res(name: String) = javaClass.getResource("/eval/$name")!!.readText().lines().filter { it.isNotBlank() }
    private val sentences = res("sentences.txt")

    data class Typist(val name: String, val sx: Double, val sy: Double, val bx: Double = 0.0, val by: Double = 0.0)

    private val careful = Typist("careful", 0.17, 0.15)
    private val everyday = Typist("everyday", 0.24, 0.21, 0.0, 0.04)
    private val fast = Typist("fast", 0.32, 0.28, 0.0, 0.08)
    private val deliberate = Typist("deliberate (rare strings)", 0.14, 0.12)

    private val configs = linkedMapOf(
        "Fixed (visible keys)" to Replay.FIXED,
        "Adaptive 0.4" to DecoderSettings(strength = 0.4),
        "Adaptive 0.7 (default)" to DecoderSettings(strength = 0.7),
        "Adaptive 1.0" to DecoderSettings(strength = 1.0),
    )

    private fun synth(texts: List<String>, t: Typist, seed: Long): List<RecordedTap> {
        val rng = Random(seed)
        val out = ArrayList<RecordedTap>()
        for (s in texts) {
            for (c in "$s ") if (c == ' ' || layout.letter(c) != null) out += RecordedTap(c, Fixtures.touchFor(c, rng, t.sx, t.sy, t.bx, t.by))
        }
        return out
    }

    private fun runAll(texts: List<String>, t: Typist, seeds: Int = 5): Map<String, TapStats> =
        configs.mapValues { (_, cfg) ->
            val total = TapStats()
            for (seed in 1..seeds) total += Replay.run(lm, layout, synth(texts, t, seed * 1000L + t.name.hashCode()), cfg)
            total
        }

    private fun table(title: String, r: Map<String, TapStats>, sb: StringBuilder) {
        sb.appendLine("**$title**").appendLine()
        sb.appendLine("| Decoder | Taps | Char error rate | Unwanted substitutions | Rescues |")
        sb.appendLine("|---|---:|---:|---:|---:|")
        for ((k, v) in r) sb.appendLine("| $k | ${v.taps} | ${"%.2f".format(v.cer * 100)}% | ${v.unwantedSubstitutions} | ${v.rescues} |")
        sb.appendLine()
    }

    /** Places every letter tap a fixed fraction of the way toward a same-row neighbour. */
    private fun boundary(texts: List<String>, frac: Float, seed: Long): List<RecordedTap> {
        val rng = Random(seed)
        val out = ArrayList<RecordedTap>()
        for (s in texts) for (c in "$s ") {
            if (c == ' ') { val k = layout.space!!; out += RecordedTap(' ', TouchPoint(k.cx, k.cy)); continue }
            val k = layout.letter(c) ?: continue
            val row = layout.letterKeys.filter { it.row == k.row }.sortedBy { it.x }
            val i = row.indexOf(k)
            val nb = when { i == 0 -> row[1]; i == row.lastIndex -> row[i - 1]; rng.nextBoolean() -> row[i - 1]; else -> row[i + 1] }
            out += RecordedTap(c, TouchPoint(k.cx + (nb.cx - k.cx) * frac, k.cy))
        }
        return out
    }

    private data class WordStats(var words: Int = 0, var correct: Int = 0, var falseCorrections: Int = 0, var fixes: Int = 0)

    /** Full pipeline (decoder + autocorrect + input logic) word accuracy. */
    private fun pipeline(texts: List<String>, t: Typist, decoder: DecoderSettings, mode: CorrectionMode, seeds: Int = 3, langs: List<String> = listOf("en")): WordStats {
        val st = WordStats()
        for (seed in 1..seeds) {
            val rng = Random(seed * 77L)
            val e = Fixtures.engine(langs = langs)
            e.logic.settings = TypingSettings(decoder = decoder, correction = mode, autoCapitalize = false, learnTouch = false, learnWords = false)
            e.logic.startInput(FieldInfo.DEFAULT)
            for (s in texts) for (w in s.split(' ')) {
                // Apostrophes etc. are typed as literal characters (long-press), letters as noisy taps.
                for (c in w) if (Fixtures.layout.letter(c) == null) e.logic.onText(c.toString()) else e.tapLetter(Fixtures.touchFor(c, rng, t.sx, t.sy, t.bx, t.by))
                val literal = e.logic.composingText
                e.logic.onSeparator(" ")
                val final = e.editor.text.trimEnd().substringAfterLast(' ')
                st.words++
                // Case-insensitive: "i" -> "I" is capitalisation, not a wrong word.
                val ok = final.equals(w, ignoreCase = true)
                if (ok) st.correct++
                if (literal.equals(w, ignoreCase = true) && !ok) st.falseCorrections++
                if (!literal.equals(w, ignoreCase = true) && ok) st.fixes++
            }
        }
        return st
    }

    @Test fun evaluateAndWriteReport() {
        val sb = StringBuilder()
        sb.appendLine("# Evaluation: adaptive vs fixed touch targets").appendLine()
        sb.appendLine("_Generated by `./gradlew :core:test --tests '*EvaluationTest*'` — do not edit by hand._").appendLine()
        sb.appendLine("""
            **What this is.** Every row replays the *identical* synthetic touch sequence through a
            decoder configuration. Touches are sampled from a Gaussian around the intended key's centre
            (σ in key pitches, plus an optional systematic bias). Layout: ${layout.width.toInt()} px wide,
            key pitch ${"%.0f".format(layout.unitW)} × ${"%.0f".format(layout.unitH)} px.

            **What this is not.** Synthetic taps follow the same broad noise model the decoder assumes, so
            these numbers show the mechanism working and its failure modes; they do **not** prove better
            real-world typing. Use the in-app Practice screen to compare modes on your own touches.

            * *Char error rate*: decoded character ≠ intended character (before word autocorrect).
            * *Unwanted substitutions*: the visible key under the finger was the intended one, but the decoder chose another.
            * *Rescues*: the finger landed on the wrong visible key and the decoder recovered the intended one.
        """.trimIndent()).appendLine()

        sb.appendLine("## 1. Everyday sentences (${sentences.size} sentences, 5 seeds)").appendLine()
        val every = runAll(sentences, everyday)
        table("Everyday typist (σx=${everyday.sx}, σy=${everyday.sy})", every, sb)
        val fastR = runAll(sentences, fast)
        table("Fast / sloppy typist (σx=${fast.sx}, σy=${fast.sy}, downward bias ${fast.by})", fastR, sb)
        table("Careful typist (σx=${careful.sx}, σy=${careful.sy})", runAll(sentences, careful), sb)

        sb.appendLine("## 2. Words outside the everyday vocabulary").appendLine()
        sb.appendLine("These are where a language prior can *hurt*. The goal is no increase in errors.").appendLine()
        val cats = listOf("names.txt" to "Names", "slang.txt" to "Slang", "repeated.txt" to "Repeated letters", "oov.txt" to "Invented words (OOV)")
        val catResults = LinkedHashMap<String, Map<String, TapStats>>()
        for ((file, title) in cats) { val r = runAll(res(file), everyday); catResults[title] = r; table("$title — everyday typist", r, sb) }
        val rare = runAll(res("rare.txt"), deliberate)
        table("Deliberate unusual strings (qwerty, xkcd, qajaq, jjjj…) — careful aim", rare, sb)

        sb.appendLine("## 3. Boundary taps").appendLine()
        sb.appendLine("Every letter tap placed exactly a fraction *f* of the way toward a same-row neighbour (space taps centred).").appendLine()
        val bText = sentences.take(50)
        for (f in listOf(0.40f, 0.52f, 0.60f)) {
            val taps = boundary(bText, f, 5)
            val r = configs.mapValues { (_, cfg) -> Replay.run(lm, layout, taps, cfg) }
            val note = when { f < 0.5f -> "finger still on the intended key"; f < 0.56f -> "just across the line"; else -> "clearly on the neighbour" }
            table("f = $f ($note)", r, sb)
        }

        sb.appendLine("## 4. Learning a systematic personal offset").appendLine()
        val thumb = Typist("offset thumb", 0.22, 0.20, bx = 0.16, by = 0.24)
        val train = sentences.take(60); val test = sentences.drop(60)
        val spatial = SpatialModel()
        val eng = Fixtures.Engine(Fixtures.lm(), spatial)
        eng.logic.settings = TypingSettings(correction = CorrectionMode.BALANCED)
        eng.logic.startInput(FieldInfo.DEFAULT)
        val rng = Random(99)
        for (s in train) { for (c in s) if (c == ' ') eng.logic.onSeparator(" ") else eng.tapLetter(Fixtures.touchFor(c, rng, thumb.sx, thumb.sy, thumb.bx, thumb.by)); eng.logic.onSeparator(" ") }
        eng.logic.finishInput()
        val learned = spatial.data
        val g = spatial.gaussian("f")
        val lr = linkedMapOf<String, TapStats>()
        for ((name, cfg, data) in listOf(
            Triple("Fixed (visible keys)", Replay.FIXED, null),
            Triple("Adaptive 0.7, no personal model", DecoderSettings(strength = 0.7, personalOffsets = false), null),
            Triple("Adaptive 0.7 + learned offsets", DecoderSettings(strength = 0.7), learned),
            Triple("Offsets only (strength 0)", DecoderSettings(strength = 0.0), learned),
        )) {
            val tot = TapStats()
            for (seed in 1..5) tot += Replay.run(lm, layout, synth(test, thumb, seed * 31L), cfg, data ?: dev.lucid.keyboard.core.touch.SpatialModelData())
            lr[name] = tot
        }
        sb.appendLine("Typist aims ${thumb.bx} pitch right and ${thumb.by} pitch low (σx=${thumb.sx}, σy=${thumb.sy}). ")
        sb.appendLine("Trained by typing ${train.size} sentences through the full pipeline (Balanced autocorrect, learning only from confirmed words); ")
        sb.appendLine("evaluated on the other ${test.size}. Samples accepted: ${learned.samples}. Learned global offset: " +
            "dx=${"%.3f".format(learned.global.mx)}, dy=${"%.3f".format(learned.global.my)} (true ${thumb.bx}, ${thumb.by}); key 'f' mean after shrinkage: " +
            "(${"%.3f".format(g.mx)}, ${"%.3f".format(g.my)}).").appendLine()
        table("Held-out sentences", lr, sb)

        sb.appendLine("## 5. Full pipeline: decoding + word autocorrect").appendLine()
        sb.appendLine("Word accuracy on 40 everyday sentences, 3 seeds, fast typist. *False corrections* = the typed letters were already the intended word and autocorrect changed them. ")
        sb.appendLine("Comparing the Fixed and Adaptive rows shows whether the two stages amplify each other (\"double correction\").").appendLine()
        sb.appendLine("| Decoder | Autocorrect | Words | Word accuracy | False corrections | Words fixed by autocorrect |")
        sb.appendLine("|---|---|---:|---:|---:|---:|")
        val pipeText = sentences.take(40)
        val pipeResults = LinkedHashMap<String, WordStats>()
        for ((dn, dc) in listOf("Fixed" to Replay.FIXED, "Adaptive 0.7" to DecoderSettings(strength = 0.7)))
            for (m in CorrectionMode.entries) {
                val w = pipeline(pipeText, fast, dc, m)
                pipeResults["$dn/$m"] = w
                sb.appendLine("| $dn | ${m.name.lowercase()} | ${w.words} | ${"%.1f".format(100.0 * w.correct / w.words)}% | ${w.falseCorrections} | ${w.fixes} |")
            }
        sb.appendLine()
        val slangNames = res("slang.txt") + res("names.txt")
        sb.appendLine("Slang and names (the words themselves, 3 seeds, everyday typist):").appendLine()
        sb.appendLine("| Decoder | Autocorrect | Words | Kept exactly | False corrections |")
        sb.appendLine("|---|---|---:|---:|---:|")
        for ((dn, dc) in listOf("Fixed" to Replay.FIXED, "Adaptive 0.7" to DecoderSettings(strength = 0.7)))
            for (m in CorrectionMode.entries) {
                val w = pipeline(slangNames, everyday, dc, m)
                sb.appendLine("| $dn | ${m.name.lowercase()} | ${w.words} | ${"%.1f".format(100.0 * w.correct / w.words)}% | ${w.falseCorrections} |")
            }
        sb.appendLine()

        sb.appendLine("## 6. Dutch and mixed Dutch/English (both languages active)").appendLine()
        sb.appendLine("The same pipeline with English + Nederlands enabled together, no manual switching. ")
        sb.appendLine("Everyday typist, 3 seeds. The key column is *false corrections*: correctly typed words changed by autocorrect.").appendLine()
        sb.appendLine("| Text | Decoder | Autocorrect | Words | Word accuracy | False corrections | Words fixed |")
        sb.appendLine("|---|---|---|---:|---:|---:|---:|")
        for ((name, file) in listOf("Dutch" to "sentences_nl.txt", "Mixed NL/EN" to "sentences_mixed.txt", "English" to "sentences.txt")) {
            val texts = res(file).take(20)
            for ((dn, dc) in listOf("Fixed" to Replay.FIXED, "Adaptive 0.7" to DecoderSettings(strength = 0.7)))
                for (m in listOf(CorrectionMode.OFF, CorrectionMode.BALANCED)) {
                    val w = pipeline(texts, everyday, dc, m, langs = listOf("en", "nl"))
                    sb.appendLine("| $name | $dn | ${m.name.lowercase()} | ${w.words} | ${"%.1f".format(100.0 * w.correct / w.words)}% | ${w.falseCorrections} | ${w.fixes} |")
                }
        }
        sb.appendLine()

        val out = File(System.getProperty("report.dir") ?: "../docs", "EVALUATION.md")
        out.writeText(sb.toString())
        println(sb)

        // Sanity guards so regressions fail the build (not claims about real typing).
        val fixedFast = fastR.getValue("Fixed (visible keys)"); val adaptFast = fastR.getValue("Adaptive 0.7 (default)")
        assertTrue("adaptive should not be worse on fast everyday text", adaptFast.errors <= fixedFast.errors)
        val rareFixed = rare.getValue("Fixed (visible keys)"); val rareAd = rare.getValue("Adaptive 1.0")
        assertTrue("deliberate rare strings must not get worse beyond noise", rareAd.errors <= rareFixed.errors + 2)
    }
}
