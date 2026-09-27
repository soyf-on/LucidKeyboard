package dev.lucid.keyboard.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.core.graphics.ColorUtils
import dev.lucid.keyboard.data.Settings
import dev.lucid.keyboard.data.ThemeMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour tokens for one appearance. All alphas are baked in. */
data class GlassPalette(
    val dark: Boolean,
    val plateTop: Int, val plateBottom: Int,
    /** Tint over a compositor blur (used only when real window blur is active). */
    val blurTint: Int,
    val keyTop: Int, val keyBottom: Int,
    val fnTop: Int, val fnBottom: Int,
    val accentTop: Int, val accentBottom: Int,
    val rimBright: Int, val rimFaint: Int, val rimShade: Int,
    val sheen: Int, val shadow: Int, val glow: Int,
    val label: Int, val labelSecondary: Int, val labelOnAccent: Int,
    val edgeHighlight: Int,
    val chip: Int, val chipActive: Int, val chipLabel: Int, val chipActiveLabel: Int,
    val contrastBorder: Int?,
)

object GlassTheme {
    fun isDark(context: Context, s: Settings) = when (s.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Primary wallpaper colour, if the system exposes it (no permission needed, API 27+). */
    fun wallpaperColor(context: Context): Int? = if (Build.VERSION.SDK_INT >= 27) runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull() else null

    fun palette(context: Context, s: Settings, tint: Int?): GlassPalette {
        val dark = isDark(context, s)
        // Transparency 0..1; reduce-transparency pins it to (almost) opaque.
        val t = if (s.reduceTransparency) 0f else s.transparency.coerceIn(0f, 1f)
        val contrast = s.increaseContrast
        fun a(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).roundToInt())
        fun tinted(base: Int, amount: Float) = if (tint == null || !s.wallpaperTint) base else ColorUtils.blendARGB(base, tint, amount)

        return if (!dark) {
            val plateBase = tinted(Color.rgb(222, 226, 234), 0.14f)
            GlassPalette(
                dark = false,
                plateTop = a(ColorUtils.blendARGB(plateBase, Color.WHITE, 0.25f), 1f - 0.30f * t),
                plateBottom = a(plateBase, 1f - 0.22f * t),
                blurTint = a(tinted(Color.rgb(236, 238, 244), 0.10f), 0.78f - 0.40f * t),
                keyTop = a(Color.WHITE, if (contrast) 1f else 0.94f - 0.30f * t),
                keyBottom = a(Color.rgb(248, 249, 252), if (contrast) 1f else 0.80f - 0.30f * t),
                fnTop = a(tinted(Color.rgb(196, 201, 212), 0.10f), if (contrast) 1f else 0.85f - 0.25f * t),
                fnBottom = a(tinted(Color.rgb(182, 188, 200), 0.10f), if (contrast) 1f else 0.78f - 0.25f * t),
                accentTop = Color.rgb(40, 132, 255), accentBottom = Color.rgb(10, 104, 240),
                rimBright = a(Color.WHITE, 0.95f), rimFaint = a(Color.WHITE, 0.20f), rimShade = a(Color.rgb(60, 70, 90), 0.16f),
                sheen = a(Color.WHITE, 0.55f), shadow = a(Color.rgb(40, 50, 80), 0.22f), glow = a(Color.WHITE, 0.85f),
                label = if (contrast) Color.BLACK else Color.rgb(16, 18, 24),
                labelSecondary = if (contrast) Color.rgb(20, 20, 20) else Color.rgb(90, 96, 110),
                labelOnAccent = Color.WHITE,
                edgeHighlight = a(Color.WHITE, 0.9f),
                chip = a(Color.WHITE, 0.55f - 0.2f * t), chipActive = a(Color.WHITE, 0.95f),
                chipLabel = Color.rgb(30, 34, 44), chipActiveLabel = Color.rgb(8, 10, 16),
                contrastBorder = if (contrast) Color.BLACK else null,
            )
        } else {
            val plateBase = tinted(Color.rgb(22, 24, 30), 0.10f)
            GlassPalette(
                dark = true,
                plateTop = a(ColorUtils.blendARGB(plateBase, Color.rgb(60, 64, 76), 0.35f), 1f - 0.30f * t),
                plateBottom = a(plateBase, 1f - 0.20f * t),
                blurTint = a(tinted(Color.rgb(18, 20, 26), 0.08f), 0.70f - 0.35f * t),
                keyTop = a(Color.rgb(112, 116, 128), if (contrast) 1f else 0.62f - 0.18f * t),
                keyBottom = a(Color.rgb(84, 88, 100), if (contrast) 1f else 0.55f - 0.18f * t),
                fnTop = a(Color.rgb(70, 74, 86), if (contrast) 1f else 0.60f - 0.16f * t),
                fnBottom = a(Color.rgb(52, 56, 66), if (contrast) 1f else 0.55f - 0.16f * t),
                accentTop = Color.rgb(48, 140, 255), accentBottom = Color.rgb(18, 108, 240),
                rimBright = a(Color.WHITE, 0.42f), rimFaint = a(Color.WHITE, 0.06f), rimShade = a(Color.BLACK, 0.35f),
                sheen = a(Color.WHITE, 0.16f), shadow = a(Color.BLACK, 0.45f), glow = a(Color.WHITE, 0.42f),
                label = if (contrast) Color.WHITE else Color.rgb(246, 247, 250),
                labelSecondary = if (contrast) Color.WHITE else Color.rgb(170, 176, 190),
                labelOnAccent = Color.WHITE,
                edgeHighlight = a(Color.WHITE, 0.30f),
                chip = a(Color.WHITE, 0.10f), chipActive = a(Color.WHITE, 0.24f),
                chipLabel = Color.rgb(220, 224, 232), chipActiveLabel = Color.WHITE,
                contrastBorder = if (contrast) Color.WHITE else null,
            )
        }
    }
}

enum class CapStyle { LETTER, FUNCTION, ACCENT }

/**
 * Draws glass key caps. The look is built from cues Apple describes for Liquid Glass
 * that we can render inside our own window: a translucent body, a bright specular rim
 * that is strongest on the upper-left edge, a darker lower rim (thickness), a soft
 * inner sheen, a contact shadow, and — on press — light that starts under the
 * fingertip and spreads. We cannot refract app content behind the keyboard: Android
 * never gives an IME those pixels (see docs/RESEARCH.md).
 */
class GlassRenderer(private val density: Float) {
    var palette: GlassPalette? = null
        set(v) { field = v; shadowCache.clear(); shaderCache.clear() }
    var simple = false
    var reduceMotion = false

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val tmp = RectF()
    private val shadowCache = HashMap<Long, Bitmap>()

    /** Gradients depend only on the cap rectangle and style; cache them so onDraw doesn't allocate. */
    private class Shaders(val body: Shader, val sheen: Shader, val rim: Shader?)
    private val shaderCache = HashMap<String, Shaders>()

    private fun shaders(rr: RectF, style: CapStyle, top: Int, bottom: Int, p: GlassPalette, sw: Float): Shaders {
        val key = "${style.ordinal}:${rr.left.toInt()}:${rr.top.toInt()}:${rr.right.toInt()}:${rr.bottom.toInt()}"
        return shaderCache.getOrPut(key) {
            val half = sw / 2
            Shaders(
                LinearGradient(0f, rr.top, 0f, rr.bottom, top, bottom, Shader.TileMode.CLAMP),
                LinearGradient(0f, rr.top, 0f, rr.top + rr.height() * 0.55f, p.sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP),
                if (p.contrastBorder != null) null else LinearGradient(rr.left + half, rr.top + half, rr.right - half, rr.bottom - half,
                    intArrayOf(p.rimBright, p.rimFaint, p.rimShade), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP),
            )
        }
    }

    fun radiusFor(r: RectF) = min(r.width(), r.height()) * 0.27f

    private fun shadowBitmap(w: Int, h: Int, radius: Float): Bitmap {
        val key = (w.toLong() shl 32) or h.toLong()
        return shadowCache.getOrPut(key) {
            val pad = (6 * density).toInt()
            val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(3.2f * density, BlurMaskFilter.Blur.NORMAL) }
            c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius, p)
            bmp
        }
    }

    /**
     * [press] 0..1 animates the lift and glow; ([glowX],[glowY]) is where the finger landed.
     */
    fun drawCap(c: Canvas, r: RectF, style: CapStyle, press: Float, glowX: Float, glowY: Float) {
        val p = palette ?: return
        val radius = radiusFor(r)
        val (top, bottom) = when (style) {
            CapStyle.LETTER -> p.keyTop to p.keyBottom
            CapStyle.FUNCTION -> p.fnTop to p.fnBottom
            CapStyle.ACCENT -> p.accentTop to p.accentBottom
        }
        if (simple) {
            body.shader = null
            body.color = if (press > 0.01f) ColorUtils.blendARGB(top, p.glow, 0.35f * press) else top
            c.drawRoundRect(r, radius, radius, body)
            rim.shader = null; rim.strokeWidth = density * 0.8f; rim.color = p.contrastBorder ?: p.rimShade
            c.drawRoundRect(r, radius, radius, rim)
            return
        }
        // Lift: a pressed key grows very slightly and its shadow deepens (off with reduce motion).
        val lift = if (reduceMotion) 0f else press
        val rr = tmp.apply { set(r) }
        if (lift > 0f) { val g = density * 1.4f * lift; rr.inset(-g, -g) }

        // 1. contact shadow
        val sb = shadowBitmap(r.width().roundToInt(), r.height().roundToInt(), radius)
        val pad = 6 * density
        bmpPaint.color = p.shadow
        bmpPaint.alpha = (Color.alpha(p.shadow) * (1f + 0.6f * lift)).coerceAtMost(255f).toInt()
        c.drawBitmap(sb, r.left - pad, r.top - pad + density * (1.2f + 1.5f * lift), bmpPaint)

        val sw = density * (if (p.contrastBorder != null) 1.4f else 0.9f)
        val sh = shaders(r, style, top, bottom, p, sw) // keyed on the static cap, not the animated one
        // 2. body
        body.shader = sh.body
        c.drawRoundRect(rr, radius, radius, body)

        // 3. inner sheen on the upper half (light concentrated by the curved top)
        c.save()
        path.reset(); path.addRoundRect(rr, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        sheenPaint.shader = sh.sheen
        c.drawRect(rr.left, rr.top, rr.right, rr.top + rr.height() * 0.55f, sheenPaint)

        // 4. touch glow: starts at the fingertip and spreads
        if (press > 0.01f) {
            val gr = max(rr.width(), rr.height()) * (0.55f + 0.65f * press)
            glowPaint.shader = RadialGradient(glowX, glowY, gr, ColorUtils.setAlphaComponent(p.glow, (Color.alpha(p.glow) * press).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(rr, glowPaint)
        }
        c.restore()

        // 5. specular rim: bright upper-left, fading toward lower-right; a faint shade below
        rim.strokeWidth = sw
        val half = sw / 2
        tmp.set(rr.left + half, rr.top + half, rr.right - half, rr.bottom - half)
        if (p.contrastBorder != null) {
            rim.shader = null; rim.color = p.contrastBorder
        } else {
            rim.shader = sh.rim
        }
        c.drawRoundRect(tmp, radius - half, radius - half, rim)
    }

    /** Keyboard base plate (used when the system blur is unavailable or disabled). */
    fun drawPlate(c: Canvas, w: Float, h: Float, cornerRadius: Float, overBlur: Boolean) {
        val p = palette ?: return
        // Over a real compositor blur the window background already carries the tint.
        if (!overBlur) {
            body.shader = LinearGradient(0f, 0f, 0f, h, p.plateTop, p.plateBottom, Shader.TileMode.CLAMP)
            tmp.set(0f, 0f, w, h + cornerRadius)
            c.drawRoundRect(tmp, cornerRadius, cornerRadius, body)
        }
        // Specular top edge.
        rim.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.TRANSPARENT, p.edgeHighlight, p.edgeHighlight, Color.TRANSPARENT), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = density
        tmp.set(density / 2, density / 2, w - density / 2, h + cornerRadius)
        c.drawRoundRect(tmp, cornerRadius, cornerRadius, rim)
    }
}
