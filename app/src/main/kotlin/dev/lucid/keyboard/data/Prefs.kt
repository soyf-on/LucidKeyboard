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
    val transparency: Float = 0.7f,
    val systemBlur: Boolean = true,
    val heightScale: Float = 1.0f,
    /** Keyboard width as a fraction of the screen (one-handed when < 1). */
    val widthScale: Float = 1.0f,
    /** Where a narrower keyboard sits: 0 = left, 0.5 = centre, 1 = right. */
    val keyboardOffset: Float = 0.5f,
    val spacingScale: Float = 1.0f,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val increaseContrast: Boolean = false,
    val simpleRendering: Boolean = false,
    val keyPopups: Boolean = true,
    val haptics: Haptics = Haptics.LIGHT,
    val sound: Boolean = false,
    val wallpaperTint: Boolean = true,
    /** Small symbol hints on letter keys showing what long-press types. */
    val digitHints: Boolean = false,
    val numberRow: Boolean = true,
    val slideToType: Boolean = true,
    /** Hold delay before long-press alternates appear, in milliseconds. */
    val longPressMs: Int = 380,
    /** Active languages, typed together without switching. */
    val languages: Set<String> = setOf("en", "nl"),
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
        transparency = sp.getFloat(K.TRANSPARENCY, 0.7f),
        systemBlur = sp.getBoolean(K.BLUR, true),
        heightScale = sp.getFloat(K.HEIGHT, 1.0f),
        widthScale = sp.getFloat(K.WIDTH, 1.0f),
        keyboardOffset = sp.getFloat(K.OFFSET, 0.5f),
        spacingScale = sp.getFloat(K.SPACING, 1.0f),
        reduceMotion = sp.getBoolean(K.REDUCE_MOTION, false),
        reduceTransparency = sp.getBoolean(K.REDUCE_TRANSPARENCY, false),
        increaseContrast = sp.getBoolean(K.CONTRAST, false),
        simpleRendering = sp.getBoolean(K.SIMPLE, false),
        keyPopups = sp.getBoolean(K.POPUPS, true),
        haptics = runCatching { Haptics.valueOf(sp.getString(K.HAPTICS, "LIGHT")!!) }.getOrDefault(Haptics.LIGHT),
        sound = sp.getBoolean(K.SOUND, false),
        wallpaperTint = sp.getBoolean(K.WALLPAPER_TINT, true),
        digitHints = sp.getBoolean(K.DIGIT_HINTS, false),
        numberRow = sp.getBoolean(K.NUMBER_ROW, true),
        slideToType = sp.getBoolean(K.SLIDE, true),
        longPressMs = sp.getInt(K.LONG_PRESS, 380).coerceIn(150, 1000),
        languages = (sp.getStringSet(K.LANGUAGES, null) ?: setOf("en", "nl")).ifEmpty { setOf("en") },
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
        const val DIGIT_HINTS = "digit_hints"; const val LANGUAGES = "languages"; const val NUMBER_ROW = "number_row"; const val WIDTH = "width_scale"; const val OFFSET = "keyboard_offset"; const val SLIDE = "slide_to_type"; const val LONG_PRESS = "long_press_ms"
        /** Per-app keyboard background: "backdrop_<package>" = AUTO | CLEAR | TINTED. */
        fun backdrop(pkg: String) = "backdrop_$pkg"
    }
}
