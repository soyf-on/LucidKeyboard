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
    /** Specular rim: bright on the upper-left, faint on the sides, lit again along the bottom. */
    val rimBright: Int, val rimFaint: Int, val rimLow: Int,
    /** Soft glow just inside the rim — reads as the thickness of the glass. */
    val innerRim: Int,
    /** Hairline outside the cap so key boundaries stay clear on any background. */
    val outline: Int,
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

    /**
     * [t] = transparency 0..1. Keys are translucent glass over the (really blurred)
     * backdrop; labels stay fully opaque, and a hairline outline keeps edges readable.
     */
    /**
     * [appTint]: when set, the app behind the keyboard probably leaves the area black (see
     * LucidInputMethodService.backdropTint); the plate becomes an opaque frosted surface
     * tinted with the app's own colour so the keyboard still looks integrated.
     */
    fun palette(context: Context, s: Settings, tint: Int?, appTint: Int? = null): GlassPalette {
        val base = paletteBase(context, s, tint)
        if (appTint == null) return base
        fun mix(c: Int, amt: Float, alpha: Float) = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c, appTint, amt), (alpha * 255).roundToInt())
        return base.copy(
            plateTop = mix(ColorUtils.setAlphaComponent(base.plateTop, 255), if (base.dark) 0.22f else 0.30f, 0.98f),
            plateBottom = mix(ColorUtils.setAlphaComponent(base.plateBottom, 255), if (base.dark) 0.10f else 0.14f, 0.99f),
        )
    }

    private fun paletteBase(context: Context, s: Settings, tint: Int?): GlassPalette {
        val dark = isDark(context, s)
        val t = if (s.reduceTransparency) 0f else s.transparency.coerceIn(0f, 1f)
        val contrast = s.increaseContrast
        fun a(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).roundToInt())
        fun tinted(base: Int, amount: Float) = if (tint == null || !s.wallpaperTint) base else ColorUtils.blendARGB(base, tint, amount)
        val W = Color.WHITE; val B = Color.BLACK

        return if (!dark) {
            val plateBase = tinted(Color.rgb(222, 226, 234), 0.12f)
            GlassPalette(
                dark = false,
                plateTop = a(ColorUtils.blendARGB(plateBase, W, 0.3f), 1f - 0.18f * t),
                plateBottom = a(plateBase, 1f - 0.12f * t),
                blurTint = a(tinted(Color.rgb(242, 244, 248), 0.08f), 0.62f - 0.40f * t),
                keyTop = a(W, if (contrast) 1f else 0.86f - 0.42f * t),
                keyBottom = a(W, if (contrast) 1f else 0.72f - 0.40f * t),
                fnTop = a(tinted(Color.rgb(200, 205, 216), 0.08f), if (contrast) 1f else 0.72f - 0.36f * t),
                fnBottom = a(tinted(Color.rgb(186, 192, 205), 0.08f), if (contrast) 1f else 0.66f - 0.34f * t),
                accentTop = Color.rgb(38, 132, 255), accentBottom = Color.rgb(8, 106, 245),
                rimBright = a(W, 0.85f), rimFaint = a(W, 0.10f), rimLow = a(W, 0.30f),
                innerRim = a(W, 0f), outline = a(Color.rgb(40, 50, 70), 0.07f),
                sheen = a(W, 0.14f), shadow = a(Color.rgb(40, 50, 80), 0.10f), glow = a(W, 0.9f),
                label = if (contrast) B else Color.rgb(12, 14, 20),
                labelSecondary = if (contrast) Color.rgb(20, 20, 20) else Color.rgb(84, 90, 104),
                labelOnAccent = W,
                edgeHighlight = a(W, 0.95f),
                chip = a(W, 0.45f - 0.2f * t), chipActive = a(W, 0.9f),
                chipLabel = Color.rgb(28, 32, 42), chipActiveLabel = Color.rgb(8, 10, 16),
                contrastBorder = if (contrast) B else null,
            )
        } else {
            val plateBase = tinted(Color.rgb(24, 26, 32), 0.08f)
            GlassPalette(
                dark = true,
                plateTop = a(ColorUtils.blendARGB(plateBase, Color.rgb(58, 62, 74), 0.3f), 1f - 0.18f * t),
                plateBottom = a(plateBase, 1f - 0.12f * t),
                blurTint = a(tinted(Color.rgb(16, 18, 24), 0.06f), 0.62f - 0.36f * t),
                keyTop = a(W, if (contrast) 0.35f else 0.26f - 0.12f * t),
                keyBottom = a(W, if (contrast) 0.30f else 0.17f - 0.08f * t),
                fnTop = a(W, if (contrast) 0.22f else 0.14f - 0.06f * t),
                fnBottom = a(W, if (contrast) 0.18f else 0.09f - 0.04f * t),
                accentTop = Color.rgb(44, 140, 255), accentBottom = Color.rgb(14, 110, 245),
                rimBright = a(W, 0.34f), rimFaint = a(W, 0.04f), rimLow = a(W, 0.10f),
                innerRim = a(W, 0f), outline = a(B, 0.18f),
                sheen = a(W, 0.04f), shadow = a(B, 0.18f), glow = a(W, 0.34f),
                label = W,
                labelSecondary = if (contrast) W else Color.rgb(176, 182, 196),
                labelOnAccent = W,
                edgeHighlight = a(W, 0.38f),
                chip = a(W, 0.08f), chipActive = a(W, 0.22f),
                chipLabel = Color.rgb(222, 226, 234), chipActiveLabel = W,
                contrastBorder = if (contrast) W else null,
            )
        }
    }
}

enum class CapStyle { LETTER, FUNCTION, ACCENT }

/**
 * Draws glass key caps, built from the cues Apple describes for Liquid Glass that can be
 * rendered inside our own window:
 *  - a see-through body over the compositor blur (real blur where the device allows it);
 *  - a specular rim lit on the upper-left and again along the bottom, as light travels
 *    through a curved slab, plus a soft inner edge glow that reads as thickness;
 *  - a faint hairline outline so boundaries stay readable on any background;
 *  - on press, light that starts under the fingertip and spreads, and a small lift.
 * What cannot be done: bending (refracting) other apps' pixels — Android never gives a
 * keyboard those pixels (see docs/RESEARCH.md).
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
    private val tmp2 = RectF()
    private val shadowCache = HashMap<Long, Bitmap>()

    /** Gradients depend only on the cap rectangle and style; cache them so onDraw doesn't allocate. */
    private class Shaders(val body: Shader, val sheen: Shader, val rim: Shader, val inner: Shader)
    private val shaderCache = HashMap<Long, Shaders>()

    private fun key(r: RectF, style: CapStyle): Long =
        (r.left.toLong() shl 48) xor (r.top.toLong() shl 32) xor (r.right.toLong() shl 16) xor r.bottom.toLong() xor (style.ordinal.toLong() shl 60)

    private fun shaders(r: RectF, style: CapStyle, top: Int, bottom: Int, p: GlassPalette): Shaders =
        shaderCache.getOrPut(key(r, style)) {
            val accent = style == CapStyle.ACCENT
            Shaders(
                LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP),
                LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f, if (accent) ColorUtils.setAlphaComponent(Color.WHITE, 60) else p.sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP),
                // Diagonal: bright upper-left, faint across the middle, lit again at the lower edge.
                LinearGradient(r.left, r.top, r.left + r.width() * 0.35f, r.bottom,
                    intArrayOf(p.rimBright, p.rimFaint, p.rimFaint, p.rimLow), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP),
                LinearGradient(0f, r.top, 0f, r.bottom,
                    intArrayOf(p.innerRim, Color.TRANSPARENT, Color.TRANSPARENT, ColorUtils.setAlphaComponent(p.innerRim, Color.alpha(p.innerRim) / 2)),
                    floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP),
            )
        }

    fun radiusFor(r: RectF) = min(r.width(), r.height()) * 0.27f

    private fun shadowBitmap(w: Int, h: Int, radius: Float): Bitmap {
        val key = (w.toLong() shl 32) or h.toLong()
        return shadowCache.getOrPut(key) {
            val pad = (6 * density).toInt()
            val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL) }
            c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius, p)
            bmp
        }
    }

    /** [press] 0..1 animates the lift and glow; ([glowX],[glowY]) is where the finger landed. */
    fun drawCap(c: Canvas, r: RectF, style: CapStyle, press: Float, glowX: Float, glowY: Float) {
        val p = palette ?: return
        val (top, bottom) = when (style) {
            CapStyle.LETTER -> p.keyTop to p.keyBottom
            CapStyle.FUNCTION -> p.fnTop to p.fnBottom
            CapStyle.ACCENT -> p.accentTop to p.accentBottom
        }
        if (simple) {
            val radius = radiusFor(r)
            body.shader = null
            body.color = if (press > 0.01f) ColorUtils.blendARGB(top, p.glow, 0.35f * press) else top
            c.drawRoundRect(r, radius, radius, body)
            rim.shader = null; rim.strokeWidth = density * 0.8f; rim.color = p.contrastBorder ?: p.outline
            c.drawRoundRect(r, radius, radius, rim)
            return
        }
        // Lift: a pressed key swells a little (the "flex" of the material); off with reduce motion.
        val lift = if (reduceMotion) 0f else press
        val rr = tmp.apply { set(r) }
        if (lift > 0f) { val g = density * 2.2f * lift; rr.inset(-g, -g * 0.8f) }
        val radius = radiusFor(rr)
        val sh = shaders(r, style, top, bottom, p)

        // 1. soft contact shadow, deepening while lifted
        if (Color.alpha(p.shadow) > 0) {
            val sb = shadowBitmap(r.width().roundToInt(), r.height().roundToInt(), radiusFor(r))
            val pad = 6 * density
            bmpPaint.color = p.shadow
            bmpPaint.alpha = (Color.alpha(p.shadow) * (1f + 2.5f * lift)).coerceAtMost(255f).toInt()
            c.drawBitmap(sb, r.left - pad, r.top - pad + density * (1f + 2f * lift), bmpPaint)
        }

        // 2. outline hairline (boundary legibility)
        rim.shader = null
        rim.strokeWidth = density * 0.7f
        rim.color = p.contrastBorder ?: p.outline
        tmp2.set(rr); tmp2.inset(-density * 0.35f, -density * 0.35f)
        c.drawRoundRect(tmp2, radius + density * 0.35f, radius + density * 0.35f, rim)

        // 3. body
        body.shader = sh.body
        c.drawRoundRect(rr, radius, radius, body)

        c.save()
        path.reset(); path.addRoundRect(rr, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        // 4. sheen concentrated in the upper half by the curved top
        sheenPaint.shader = sh.sheen
        c.drawRect(rr.left, rr.top, rr.right, rr.top + rr.height() * 0.5f, sheenPaint)
        // 5. touch light: starts at the fingertip and spreads with the press
        if (press > 0.01f) {
            val gr = max(rr.width(), rr.height()) * (0.45f + 0.8f * press)
            glowPaint.shader = RadialGradient(glowX, glowY, gr, ColorUtils.setAlphaComponent(p.glow, (Color.alpha(p.glow) * 0.8f * press).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(rr, glowPaint)
        }
        c.restore()

        // 6. inner edge glow (thickness) and 7. specular rim
        if (p.contrastBorder == null && Color.alpha(p.innerRim) > 0) {
            rim.shader = sh.inner
            rim.strokeWidth = density * 2.2f
            tmp2.set(rr); tmp2.inset(density * 1.6f, density * 1.6f)
            c.drawRoundRect(tmp2, max(0f, radius - density * 1.6f), max(0f, radius - density * 1.6f), rim)
            rim.shader = sh.rim
        } else if (p.contrastBorder != null) {
            rim.shader = null; rim.color = p.contrastBorder
        } else rim.shader = sh.rim
        rim.strokeWidth = density * 0.8f
        tmp2.set(rr); tmp2.inset(density * 0.4f, density * 0.4f)
        c.drawRoundRect(tmp2, radius - density * 0.5f, radius - density * 0.5f, rim)
    }

    /** Keyboard base plate. Over a real compositor blur it only adds the lit top edge. */
    fun drawPlate(c: Canvas, w: Float, h: Float, cornerRadius: Float, overBlur: Boolean) {
        val p = palette ?: return
        if (!overBlur) {
            body.shader = LinearGradient(0f, 0f, 0f, h, p.plateTop, p.plateBottom, Shader.TileMode.CLAMP)
            tmp.set(0f, 0f, w, h + cornerRadius)
            c.drawRoundRect(tmp, cornerRadius, cornerRadius, body)
        }
        rim.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.TRANSPARENT, p.edgeHighlight, p.edgeHighlight, Color.TRANSPARENT), floatArrayOf(0f, 0.15f, 0.85f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = density
        tmp.set(density / 2, density / 2, w - density / 2, h + cornerRadius)
        c.drawRoundRect(tmp, cornerRadius, cornerRadius, rim)
    }
}

/** Largest text size ≤ [maxPx] at which [text] fits in [maxWidth] px (labels never touch the key edge). */
fun fitTextSize(paint: Paint, text: String, maxWidth: Float, maxPx: Float, minPx: Float = maxPx * 0.55f): Float {
    paint.textSize = maxPx
    val w = paint.measureText(text)
    if (w <= maxWidth || w == 0f) return maxPx
    return max(minPx, maxPx * maxWidth / w)
}
