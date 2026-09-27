package dev.lucid.keyboard.ui

import android.os.SystemClock
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A damped spring, integrated per frame — the motion model Apple uses for UI
 * (a "response" time and a damping fraction). Values glide to their target and settle
 * softly instead of snapping or moving linearly. Call [step] from onDraw with the
 * frame time; it returns true while still moving.
 */
class Spring(initial: Float = 0f, response: Float = 0.30f, dampingFraction: Float = 0.82f) {
    var value = initial; private set
    var target = initial
    private var velocity = 0f
    private val stiffness: Float
    private val damping: Float

    init {
        // Same parameterisation as SwiftUI's spring(response:dampingFraction:), unit mass.
        val omega = (2 * Math.PI / response).toFloat()
        stiffness = omega * omega
        damping = 2 * dampingFraction * sqrt(stiffness)
    }

    /** Jumps to [v] with no animation (e.g. reduce motion, or an instant highlight). */
    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    val isMoving get() = abs(target - value) > 0.001f || abs(velocity) > 0.001f

    fun step(dtSeconds: Float): Boolean {
        if (!isMoving) { value = target; velocity = 0f; return false }
        var t = min(dtSeconds, 0.064f)
        // Sub-steps keep the integration stable on long frames.
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -stiffness * (value - target) - damping * velocity
            velocity += a * h
            value += velocity * h
            t -= h
        }
        return isMoving
    }
}

/** Frame clock for views that step springs in onDraw. */
class FrameClock {
    private var last = 0L
    /** Seconds since the previous call (first call: one 120 Hz frame). */
    fun tick(): Float {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 1f / 120f else (now - last) / 1000f
        last = now
        return dt
    }
    fun reset() { last = 0L }
}

/** iOS-like ease-out curve for view transitions (fast start, long gentle settle). */
val SmoothEase = PathInterpolator(0.2f, 0f, 0f, 1f)
