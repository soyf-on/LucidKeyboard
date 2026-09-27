package dev.lucid.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.input.TypingSettings
import dev.lucid.keyboard.core.touch.DecoderSettings

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Haptics { OFF, LIGHT, MEDIUM, STRONG }

/** All user settings, read from SharedPreferences. Immutable snapshot. */
data class Settings(
    // Typing accuracy
    val adaptive: Boolean = true,
    val strength: Float = 0.7f,
    val personalOffsets: Boolean = true,
    val learnTouch: Boolean = true,
    val devOverlay: Boolean = false,
    // Correction
    val correction: CorrectionMode = CorrectionMode.BALANCED,
    val suggestions: Boolean = true,
    val autoCap: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val learnWords: Boolean = true,
    // Privacy
    val privateMode: Boolean = false,
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val transparency: Float = 0.5f,
    val systemBlur: Boolean = true,
    val heightScale: Float = 1.0f,
    val spacingScale: Float = 1.0f,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val increaseContrast: Boolean = false,
    val simpleRendering: Boolean = false,
    val keyPopups: Boolean = true,
    val haptics: Haptics = Haptics.LIGHT,
    val sound: Boolean = false,
    val wallpaperTint: Boolean = true,
) {
    fun typing() = TypingSettings(
        decoder = DecoderSettings(adaptive = adaptive, strength = strength.toDouble(), personalOffsets = personalOffsets),
        correction = correction,
        suggestions = suggestions,
        autoCapitalize = autoCap,
        doubleSpacePeriod = doubleSpacePeriod,
        privateMode = privateMode,
        learnTouch = learnTouch,
        learnWords = learnWords,
    )
}

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("lucid", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        adaptive = sp.getBoolean(K.ADAPTIVE, true),
        strength = sp.getFloat(K.STRENGTH, 0.7f),
        personalOffsets = sp.getBoolean(K.PERSONAL, true),
        learnTouch = sp.getBoolean(K.LEARN_TOUCH, true),
        devOverlay = sp.getBoolean(K.DEV, false),
        correction = runCatching { CorrectionMode.valueOf(sp.getString(K.CORRECTION, "BALANCED")!!) }.getOrDefault(CorrectionMode.BALANCED),
        suggestions = sp.getBoolean(K.SUGGESTIONS, true),
        autoCap = sp.getBoolean(K.AUTOCAP, true),
        doubleSpacePeriod = sp.getBoolean(K.DOUBLE_SPACE, true),
        learnWords = sp.getBoolean(K.LEARN_WORDS, true),
        privateMode = sp.getBoolean(K.PRIVATE, false),
        theme = runCatching { ThemeMode.valueOf(sp.getString(K.THEME, "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        transparency = sp.getFloat(K.TRANSPARENCY, 0.5f),
        systemBlur = sp.getBoolean(K.BLUR, true),
        heightScale = sp.getFloat(K.HEIGHT, 1.0f),
        spacingScale = sp.getFloat(K.SPACING, 1.0f),
        reduceMotion = sp.getBoolean(K.REDUCE_MOTION, false),
        reduceTransparency = sp.getBoolean(K.REDUCE_TRANSPARENCY, false),
        increaseContrast = sp.getBoolean(K.CONTRAST, false),
        simpleRendering = sp.getBoolean(K.SIMPLE, false),
        keyPopups = sp.getBoolean(K.POPUPS, true),
        haptics = runCatching { Haptics.valueOf(sp.getString(K.HAPTICS, "LIGHT")!!) }.getOrDefault(Haptics.LIGHT),
        sound = sp.getBoolean(K.SOUND, false),
        wallpaperTint = sp.getBoolean(K.WALLPAPER_TINT, true),
    )

    fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    object K {
        const val ADAPTIVE = "adaptive"; const val STRENGTH = "strength"; const val PERSONAL = "personal_offsets"
        const val LEARN_TOUCH = "learn_touch"; const val DEV = "dev_overlay"; const val CORRECTION = "correction"
        const val SUGGESTIONS = "suggestions"; const val AUTOCAP = "autocap"; const val DOUBLE_SPACE = "double_space"
        const val LEARN_WORDS = "learn_words"; const val PRIVATE = "private_mode"; const val THEME = "theme"
        const val TRANSPARENCY = "transparency"; const val BLUR = "system_blur"; const val HEIGHT = "height"
        const val SPACING = "spacing"; const val REDUCE_MOTION = "reduce_motion"; const val REDUCE_TRANSPARENCY = "reduce_transparency"
        const val CONTRAST = "contrast"; const val SIMPLE = "simple_rendering"; const val POPUPS = "key_popups"
        const val HAPTICS = "haptics"; const val SOUND = "sound"; const val WALLPAPER_TINT = "wallpaper_tint"
        const val RECENT_EMOJI = "recent_emoji"; const val CALIBRATED = "calibrated"
    }
}
