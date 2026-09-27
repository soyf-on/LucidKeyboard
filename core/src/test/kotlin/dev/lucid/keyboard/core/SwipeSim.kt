package dev.lucid.keyboard.core

import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.touch.TouchPoint
import java.util.Random

/**
 * Human-like swipe paths for testing slide-to-type (after Quinn & Zhai, CHI 2016): the finger
 * aims at each letter with noise (looser for middle letters than the first/last), the path is
 * a smooth curve that does not pass through the aim points (it cuts corners), start/end can
 * over- or undershoot, and sampling is uneven like real touch events.
 */
object SwipeSim {
    fun path(word: String, rng: Random, lay: KeyboardLayout = Fixtures.layout, sloppiness: Double = 1.0): List<TouchPoint> {
        val letters = word.lowercase().filter { lay.letter(it) != null }
        val keys = letters.map { lay.letter(it)!! }
        // Collapse double letters: the finger doesn't stop twice.
        val aims = ArrayList<DoubleArray>()
        keys.forEachIndexed { i, k ->
            if (i > 0 && keys[i - 1] === k) return@forEachIndexed
            val end = i == 0 || i == keys.lastIndex
            val sx = (if (end) 0.22 else 0.38) * sloppiness; val sy = (if (end) 0.20 else 0.32) * sloppiness
            aims += doubleArrayOf(k.cx + rng.nextGaussian() * sx * lay.unitW, k.cy + rng.nextGaussian() * sy * lay.unitH)
        }
        if (aims.size == 1) aims += doubleArrayOf(aims[0][0] + 1.0, aims[0][1])
        // Mild corner cutting: middle aims drift a little toward the line between their neighbours.
        for (i in 1 until aims.size - 1) {
            val f = (0.1 + rng.nextDouble() * 0.2) * sloppiness
            aims[i] = doubleArrayOf(aims[i][0] + f * ((aims[i - 1][0] + aims[i + 1][0]) / 2 - aims[i][0]), aims[i][1] + f * ((aims[i - 1][1] + aims[i + 1][1]) / 2 - aims[i][1]))
        }
        // Catmull-Rom curve through the aims: smooth, passes near each letter.
        val ctrl = ArrayList<DoubleArray>().apply { add(aims.first()); addAll(aims); add(aims.last()) }
        val out = ArrayList<TouchPoint>()
        for (seg in 0 until ctrl.size - 3) {
            val p0 = ctrl[seg]; val p1 = ctrl[seg + 1]; val p2 = ctrl[seg + 2]; val p3 = ctrl[seg + 3]
            val steps = 6 + rng.nextInt(6) // uneven sampling (speed varies)
            for (s in 0 until steps) {
                val t = s.toDouble() / steps; val t2 = t * t; val t3 = t2 * t
                fun cr(a: Double, b: Double, c: Double, d: Double) = 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                out += TouchPoint(cr(p0[0], p1[0], p2[0], p3[0]).toFloat(), cr(p0[1], p1[1], p2[1], p3[1]).toFloat())
            }
        }
        out += TouchPoint(aims.last()[0].toFloat(), aims.last()[1].toFloat())
        return out
    }

    /** Common words for measuring accuracy (from the evaluation sentences). */
    fun words(file: String): List<String> =
        javaClass.getResource("/eval/$file")!!.readText().lines().flatMap { it.split(' ') }
            .map { it.lowercase() }.filter { w -> w.length >= 2 && w.all { it in 'a'..'z' } }.distinct()
}
