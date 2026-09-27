package dev.lucid.keyboard.ui

import dev.lucid.keyboard.core.geometry.LayoutParams
import dev.lucid.keyboard.data.Settings

/** Key geometry in pixels, shared by the keyboard service and the practice screen. */
object Dimensions {
    fun params(width: Float, density: Float, landscape: Boolean, s: Settings, extraKey: String? = null) = LayoutParams(
        width = width,
        rowHeight = (if (landscape) 38f else 50f) * density * s.heightScale,
        hGap = 6f * density * s.spacingScale,
        vGap = (if (landscape) 7f else 11f) * density * s.spacingScale,
        sidePadding = 3f * density, topPadding = 4f * density, bottomPadding = 4f * density,
        extraKey = extraKey,
        numberRow = s.numberRow,
    )
}
