package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.gesture.GestureDecoder
import org.junit.Test
import java.util.Random
/**
 * Fits the slide-to-type channel weights (GestureDecoder.FITTED) on realistic simulated
 * swipes, with held-out English and Dutch validation. Slow (minutes); run on demand:
 *   ./gradlew :core:test --tests '*GestureFitTest*' -Pfit
 */
class GestureFitTest {
  class Sample(val truth: String, val cands: List<Pair<String, DoubleArray>>)
  fun build(file: String, langs: List<String>, seed: Long, reps: Int): List<Sample> {
    val lm = Fixtures.lm(langs = langs); val g = GestureDecoder(lm)
    val rng = Random(seed); val out = ArrayList<Sample>()
    for (w in SwipeSim.words(file)) repeat(reps) {
      val sl = 0.7 + rng.nextDouble() * 0.6
      out += Sample(w, g.candidateFeatures(Fixtures.layout, SwipeSim.path(w, rng, sloppiness = sl), null))
    }
    return out
  }
  fun acc(s: List<Sample>, w: DoubleArray): Pair<Double, Double> {
    var t1 = 0; var t3 = 0
    for (x in s) {
      val scored = x.cands.map { (k, f) -> k to f.indices.sumOf { w[it] * f[it] } }.sortedByDescending { it.second }
      val r = scored.indexOfFirst { it.first == x.truth }
      if (r == 0) t1++; if (r in 0..2) t3++
    }
    return 100.0 * t1 / s.size to 100.0 * t3 / s.size
  }
  @Test fun fit() {
    org.junit.Assume.assumeTrue("set -Pfit to run", System.getProperty("fit") != null)
    val train = build("sentences.txt", listOf("en", "nl"), 1, 2)
    val validEn = build("sentences.txt", listOf("en", "nl"), 77, 1)
    val validNl = build("sentences_nl.txt", listOf("en", "nl"), 5, 2)
    val recall = train.count { s -> s.cands.any { it.first == s.truth } } * 100.0 / train.size
    println("DBG candidate recall ${"%.1f".format(recall)}%")
    var w = GestureDecoder.FITTED.copyOf()
    var best = acc(train, w).first
    println("DBG start train=${"%.1f".format(best)} validEn=${acc(validEn, w)} validNl=${acc(validNl, w)}")
    val steps = doubleArrayOf(3.0, 1.8, 1.3, 0.75, 0.5, 0.0)
    repeat(4) { round ->
      for (i in w.indices) {
        if (i == 8) continue // language weight fixed at 1 (scale reference)
        for (m in steps) for (base in listOf(w[i], if (w[i] == 0.0) 1.0 else w[i], if (w[i] == 0.0) 10.0 else w[i])) {
          val trial = w.copyOf(); trial[i] = base * m
          val a = acc(train, trial).first
          if (a > best + 0.05) { best = a; w = trial }
        }
      }
      println("DBG round $round train=${"%.1f".format(best)} w=${w.joinToString { "%.2f".format(it) }}")
    }
    println("DBG FINAL train=${acc(train, w)} validEn=${acc(validEn, w)} validNl=${acc(validNl, w)}")
    println("DBG weights doubleArrayOf(${w.joinToString { "%.3f".format(it) }})")
  }
}
