package dev.lucid.keyboard.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.lucid.keyboard.LucidApp
import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.correct.WordCorrector
import dev.lucid.keyboard.core.geometry.Key
import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.geometry.LayoutParams
import dev.lucid.keyboard.core.geometry.Layouts
import dev.lucid.keyboard.core.input.InputLogic
import dev.lucid.keyboard.core.input.ShiftState
import dev.lucid.keyboard.core.touch.TapDecision
import dev.lucid.keyboard.core.touch.TouchDecoder
import dev.lucid.keyboard.core.touch.TouchPoint
import dev.lucid.keyboard.data.Haptics
import dev.lucid.keyboard.data.Prefs
import dev.lucid.keyboard.data.Settings
import dev.lucid.keyboard.settings.SettingsActivity
import dev.lucid.keyboard.ui.Dimensions
import dev.lucid.keyboard.ui.ClipboardPanel
import dev.lucid.keyboard.ui.EmojiData
import dev.lucid.keyboard.ui.EmojiPanel
import dev.lucid.keyboard.ui.GlassRenderer
import dev.lucid.keyboard.ui.GlassTheme
import dev.lucid.keyboard.ui.KeyboardView
import dev.lucid.keyboard.ui.PopupOverlay
import dev.lucid.keyboard.ui.QuickPanel
import dev.lucid.keyboard.ui.SuggestionStrip
import java.util.concurrent.Executor

class LucidInputMethodService : InputMethodService(), KeyboardView.Listener, SuggestionStrip.Listener,
    QuickPanel.Actions, EmojiPanel.Actions, ClipboardPanel.Actions, SharedPreferences.OnSharedPreferenceChangeListener {

    private val app get() = application as LucidApp
    private lateinit var settings: Settings
    private val editor = EditorBridge(this)
    private var logic: InputLogic? = null
    private var gestures: dev.lucid.keyboard.core.gesture.GestureDecoder? = null
    private var landscape = false

    private lateinit var renderer: GlassRenderer
    private var root: RootView? = null
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStrip? = null
    private var content: FrameLayout? = null
    private var overlay: PopupOverlay? = null

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD, PHONE, EMOJI, CLIPBOARD }

    // ---- clipboard (memory only; see ClipboardHistory) ----
    private val clips = dev.lucid.keyboard.data.ClipboardHistory()
    private val clipboard by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        clips.capture(clipboard, settings.privateMode, System.currentTimeMillis())
        scheduleStrip()
    }
    private var mode = Mode.LETTERS
    private var quickOpen = false
    private var blurActive = false
    private var tintedBackdrop = false
    private val main = Handler(Looper.getMainLooper())
    private val stripUpdate = Runnable { updateStrip() }
    private val regionUpdate = Runnable { keyboard?.refreshRegionMap() }

    /** Measured timings (µs) of recent decisions and strip updates — shown in the dev overlay. */
    private var lastStripMicros = 0L

    override fun onCreate() {
        super.onCreate()
        settings = app.prefs.load()
        renderer = GlassRenderer(resources.displayMetrics.density)
        app.prefs.sp.registerOnSharedPreferenceChangeListener(this)
        runCatching { clipboard?.addPrimaryClipChangedListener(clipListener) }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val eng = app.engine()
        if (eng != null) {
            val spatial = eng.spatial(landscape)
            logic = InputLogic(eng.lm, TouchDecoder(eng.lm, spatial), WordCorrector(eng.lm, spatial), editor).also {
                it.settings = settings.typing()
                it.onLearned = { persist() }
            }
            gestures = dev.lucid.keyboard.core.gesture.GestureDecoder(eng.lm).also { g -> Thread({ g.warmUp() }, "lucid-gesture").start() }
        } else Log.e(LucidApp.TAG, "models unavailable; keyboard runs with visible-key decoding only")
    }

    override fun onDestroy() {
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        app.prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        app.storage.flush()
        super.onDestroy()
    }

    private fun persist() {
        val eng = app.engine(0) ?: return
        app.storage.saveVocabularySoon(eng.vocabulary)
        app.storage.saveSpatialSoon(eng.spatial(landscape), landscape)
    }

    // ---- views ---------------------------------------------------------------------------

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val ctx = this
        val r = RootView(ctx, renderer)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStrip(ctx, renderer).also { it.listener = this }
        val kv = KeyboardView(ctx, renderer).also { it.listener = this }
        kv.onWidthChanged = { w -> if (mode != Mode.EMOJI && !quickOpen) kv.layout = buildLayout(w.toFloat()) }
        val c = FrameLayout(ctx)
        val ov = PopupOverlay(ctx, renderer)
        kv.overlay = ov
        column.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        c.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        r.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        root = r; keyboard = kv; strip = s; content = c; overlay = ov
        kv.layoutParams.height = keyboardHeight()
        applyAppearance()
        return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** "/" in URL fields, "@" in e-mail fields, beside the space bar. */
    private var extraKey: String? = null

    private fun layoutParams(width: Float): LayoutParams =
        Dimensions.params(width, resources.displayMetrics.density, landscape, settings, extraKey)

    private fun keyboardHeight() = Layouts.totalHeight(layoutParams(resources.displayMetrics.widthPixels.toFloat())).toInt()

    private fun buildLayout(width: Float = keyboard?.width?.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()): KeyboardLayout {
        val p = layoutParams(width)
        return when (mode) {
            Mode.SYMBOLS -> Layouts.symbols(p, 0)
            Mode.SYMBOLS2 -> Layouts.symbols(p, 1)
            Mode.NUMPAD -> Layouts.numpad(p, phone = false)
            Mode.PHONE -> Layouts.numpad(p, phone = true)
            else -> Layouts.qwerty(p)
        }
    }

    /** Pages cross-fade with a slight settle instead of cutting (skipped with reduce motion). */
    private fun animateIn(v: View) {
        if (settings.reduceMotion) return
        v.alpha = 0f; v.scaleX = 0.985f; v.scaleY = 0.985f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(dev.lucid.keyboard.ui.SmoothEase).start()
    }

    private fun showMode(m: Mode) {
        val changed = m != mode
        mode = m
        val c = content ?: return
        val kv = keyboard ?: return
        c.removeAllViews()
        quickOpen = false
        strip?.quickPanelOpen = false
        if (m == Mode.CLIPBOARD) {
            val panel = ClipboardPanel(this, renderer, clips.items(), this, keyboardHeight())
            c.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()))
            animateIn(panel)
            return
        }
        if (m == Mode.EMOJI) {
            val h = keyboardHeight()
            val recent = app.prefs.sp.getString(Prefs.K.RECENT_EMOJI, "")!!.split(' ').filter { it.isNotEmpty() }
            val panel = EmojiPanel(this, renderer, emojiData(), recent, this, h) { l ->
                KeyboardView(this, renderer).also { kv ->
                    kv.listener = l
                    kv.keyPopups = settings.keyPopups
                    val w = content?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val p = Dimensions.params(w.toFloat(), resources.displayMetrics.density, landscape, settings.copy(numberRow = false))
                    kv.layout = Layouts.qwerty(p.copy(rowHeight = p.rowHeight * 0.82f))
                    kv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Layouts.totalHeight(p.copy(rowHeight = p.rowHeight * 0.82f)).toInt())
                }
            }
            c.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            animateIn(panel)
            return
        }
        kv.layout = buildLayout()
        kv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight())
        c.addView(kv)
        if (changed) animateIn(kv)
        updateKeyState()
    }

    private var emojiCache: EmojiData? = null
    private fun emojiData(): EmojiData = emojiCache ?: run {
        val groups = LinkedHashMap<String, List<String>>(); val kw = HashMap<String, String>()
        assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            for (l in lines) {
                val t = l.split('\t')
                if (t.size < 2) continue
                (groups.getOrPut(t[0]) { ArrayList() } as ArrayList).add(t[1])
                if (t.size > 2) kw[t[1]] = t[2]
            }
        }
        EmojiData(groups, kw).also { emojiCache = it }
    }

    /** Language estimate per app for this session only (never written to disk; not in private mode). */
    private val languageByApp = HashMap<String, DoubleArray>()

    private fun rememberLanguage() {
        val pkg = clientPackage ?: return
        val lm = app.engine(0)?.lm ?: return
        if (settings.privateMode || editor.editorInfo?.let { EditorBridge.fieldFor(it).literal } == true) return
        languageByApp[pkg] = lm.languageWeights.copyOf()
    }

    /** Package of the app being typed into (for the per-app background choice). */
    private var clientPackage: String? = null
    private val appColorCache = HashMap<String, Int?>()

    enum class Backdrop { AUTO, CLEAR, TINTED }

    fun backdropMode(pkg: String?): Backdrop =
        pkg?.let { runCatching { Backdrop.valueOf(app.prefs.sp.getString(Prefs.K.backdrop(it), "AUTO")!!) }.getOrNull() } ?: Backdrop.AUTO

    /**
     * Whether (and with which colour) to replace the see-through glass with a tinted
     * surface. The keyboard cannot see the pixels behind it, so AUTO uses how the app is
     * built: apps targeting Android 15+ are drawn edge-to-edge and put their own content
     * or background behind the keyboard (keep clear glass); older apps are usually
     * resized above it, leaving black behind (tint). The user's per-app choice wins.
     */
    private fun backdropTint(): Int? {
        val pkg = clientPackage ?: return null
        val mode = backdropMode(pkg)
        if (mode == Backdrop.CLEAR) return null
        val target = runCatching { packageManager.getApplicationInfo(pkg, 0).targetSdkVersion }.getOrNull()
        Log.d(LucidApp.TAG, "backdrop: $pkg mode=$mode targetSdk=$target")
        if (mode == Backdrop.AUTO && (target == null || target >= 35)) return null
        // If the app's icon isn't visible to us, a neutral frost still hides a black gap.
        return appColorCache.getOrPut(pkg) { appColor(pkg) } ?: if (GlassTheme.isDark(this, settings)) Color.rgb(40, 42, 50) else Color.rgb(214, 218, 228)
    }

    /** A representative colour of the app: the most saturated average of its icon. */
    private fun appColor(pkg: String): Int? = runCatching {
        val icon = packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 24, 24); icon.draw(Canvas(bmp))
        var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            Color.colorToHSV(c, hsv)
            val w = 0.15 + hsv[1] * hsv[2] // favour colourful pixels over white/black
            r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; wsum += w
        }
        if (wsum == 0.0) null else Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt())
    }.getOrNull()

    private fun applyAppearance() {
        val tint = GlassTheme.wallpaperColor(this)
        val appTint = backdropTint()
        renderer.palette = GlassTheme.palette(this, settings, tint, appTint)
        tintedBackdrop = appTint != null
        renderer.simple = settings.simpleRendering
        renderer.reduceMotion = settings.reduceMotion
        keyboard?.apply {
            keyPopups = settings.keyPopups && !settings.reduceMotion
            overlayEnabled = settings.devOverlay
            showDigitHints = settings.digitHints
            gestureTyping = settings.slideToType && logic != null
        }
        applyWindowBlur()
        root?.invalidate(); keyboard?.invalidate(); strip?.invalidate()
    }

    private val blurListener = java.util.function.Consumer<Boolean> { main.post { applyWindowBlur() } }
    private var blurListenerRegistered = false

    /**
     * Uses the compositor's cross-window blur when the device offers it (Android 12+,
     * enabled, not in battery saver). The IME cannot read the blurred pixels; it only
     * asks SurfaceFlinger to blur what is behind its window. Otherwise a self-drawn
     * frosted plate is used.
     */
    private fun applyWindowBlur() {
        val w = window?.window ?: return
        var active = false
        if (Build.VERSION.SDK_INT >= 31) {
            val wm = getSystemService(WindowManager::class.java)
            if (!blurListenerRegistered) {
                wm.addCrossWindowBlurEnabledListener(Executor { it.run() }, blurListener); blurListenerRegistered = true
            }
            val want = settings.systemBlur && !settings.reduceTransparency && !settings.simpleRendering && !tintedBackdrop
            if (want && wm.isCrossWindowBlurEnabled) {
                val p = renderer.palette!!
                w.setBackgroundDrawable(GradientDrawable().apply {
                    setColor(p.blurTint)
                    val rad = 18f * resources.displayMetrics.density
                    cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
                })
                w.setBackgroundBlurRadius((44 * resources.displayMetrics.density).toInt())
                active = true
            } else {
                w.setBackgroundBlurRadius(0)
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
        }
        blurActive = active
        root?.blurActive = active
        root?.invalidate()
    }

    val isSystemBlurActive get() = blurActive

    // ---- input lifecycle ------------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        settings = app.prefs.load()
        logic?.settings = settings.typing()
        editor.reset(info)
        clientPackage = info.packageName
        applyLanguages()
        val variation = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        extraKey = when (variation) {
            android.text.InputType.TYPE_TEXT_VARIATION_URI -> "/"
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
            else -> null
        }
        strip?.showSwitch = shouldOfferSwitchingToNextInputMethod()
        // Pick up anything copied while the keyboard wasn't running (freshness uses the copy time).
        clipboard?.let { clips.capture(it, settings.privateMode, System.currentTimeMillis()) }
        val field = EditorBridge.fieldFor(info)
        // Language: start from what was last written in this app (this session, memory only),
        // then startInput() refines it from any text already in the field.
        app.engine(0)?.lm?.let { lm ->
            lm.resetLanguageWeights()
            info.packageName?.let { languageByApp[it] }?.let { lm.setLanguageWeights(it) }
        }
        logic?.startInput(field)
        applyAppearance()
        showMode(when {
            EditorBridge.isPhone(info) -> Mode.PHONE
            EditorBridge.isNumeric(info) -> Mode.NUMPAD
            else -> Mode.LETTERS
        })
        configureEnter(info)
        strip?.privateMode = settings.privateMode
        scheduleStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        logic?.finishInput()
        rememberLanguage()
        keyboard?.cancelAll()
        app.storage.flush()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        logic?.finishInput()
        super.onFinishInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val l = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (l != landscape) {
            landscape = l
            app.engine(0)?.let { e -> logic?.decoder?.spatial = e.spatial(l); logic?.corrector?.spatial = e.spatial(l) }
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!editor.isOwnUpdate(newSelStart, newSelEnd)) {
            logic?.onExternalCursorMove()
            updateKeyState(); scheduleStrip()
        }
    }

    private var enterAction = EditorInfo.IME_ACTION_NONE
    private fun configureEnter(info: EditorInfo) {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        enterAction = if (noAction || multiLine) EditorInfo.IME_ACTION_NONE else action
        keyboard?.enterIcon = when (enterAction) {
            EditorInfo.IME_ACTION_GO -> KeyboardView.EnterIcon.GO; EditorInfo.IME_ACTION_SEARCH -> KeyboardView.EnterIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyboardView.EnterIcon.SEND; EditorInfo.IME_ACTION_NEXT -> KeyboardView.EnterIcon.NEXT
            EditorInfo.IME_ACTION_DONE -> KeyboardView.EnterIcon.DONE; EditorInfo.IME_ACTION_PREVIOUS -> KeyboardView.EnterIcon.PREVIOUS
            else -> KeyboardView.EnterIcon.RETURN
        }
        keyboard?.enterIsAction = enterAction in setOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND)
    }

    // ---- KeyboardView.Listener ---------------------------------------------------------------

    override fun decide(layout: KeyboardLayout, point: TouchPoint): TapDecision {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val l = logic
        val d = if (l != null && layout.name == "qwerty") l.decide(layout, point)
        else TapDecision(layout.geometricKeyAt(point.x, point.y) ?: layout.keys.first(), layout.geometricKeyAt(point.x, point.y), 1.0, false, 0.0, emptyList())
        keyboard?.lastDecideMicros = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return d
    }

    override fun onKeyCommitted(key: Key, point: TouchPoint, decision: TapDecision) {
        val l = logic
        val lay = keyboard?.layout ?: return
        when {
            l == null -> currentInputConnection?.commitText(key.output, 1)
            key.kind == KeyKind.LETTER -> l.onLetterTap(lay, key, point)
            key.kind == KeyKind.SPACE -> l.onSeparator(" ")
            key.kind == KeyKind.CHAR -> {
                l.onText(key.output)
                // On the symbols page, a separator-like tap returns to letters (like iOS after "'")
                if (mode == Mode.SYMBOLS && key.output == "'") showMode(Mode.LETTERS)
            }
        }
        afterEdit()
    }

    override fun onText(text: String) {
        if (text == Layouts.EMOJI_ALTERNATE) { showMode(Mode.EMOJI); return }
        logic?.onText(text) ?: currentInputConnection?.commitText(text, 1)
        afterEdit()
    }

    override fun onBackspace() {
        val l = logic
        if (l == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) else l.onBackspace()
        afterEdit()
    }

    override fun onShift(doubleTap: Boolean) {
        logic?.onShiftTap(doubleTap)
        updateKeyState()
    }

    override fun onFunctionKey(key: Key) {
        when (key.kind) {
            KeyKind.TO_SYMBOLS -> showMode(Mode.SYMBOLS)
            KeyKind.TO_MORE_SYMBOLS -> showMode(if (mode == Mode.SYMBOLS2) Mode.SYMBOLS else Mode.SYMBOLS2)
            KeyKind.TO_LETTERS -> showMode(Mode.LETTERS)
            KeyKind.EMOJI -> showMode(Mode.EMOJI)
            KeyKind.SWITCH_IME -> if (Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else showImePicker()
            KeyKind.ENTER -> onEnter()
            else -> {}
        }
    }

    override fun onLongPressFunction(key: Key): Boolean = when (key.kind) {
        KeyKind.SWITCH_IME -> { showImePicker(); true }
        // Hold 123 (or ABC) for emoji.
        KeyKind.TO_SYMBOLS, KeyKind.TO_LETTERS -> { showMode(Mode.EMOJI); true }
        else -> false
    }

    private fun showImePicker() { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }

    private fun onEnter() {
        logic?.onEnter()
        val ic = currentInputConnection ?: return
        if (enterAction != EditorInfo.IME_ACTION_NONE && enterAction != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(enterAction)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        afterEdit()
    }

    override fun onCursorMove(steps: Int) {
        logic?.let { if (it.isComposing) it.onExternalCursorMove() }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
        feedback(tick = true)
    }

    override fun onKeyDownFeedback(key: Key) = feedback(tick = false, key = key)

    override fun onGesture(path: List<TouchPoint>) {
        val lay = keyboard?.layout ?: return
        val l = logic ?: return
        val prev = if (l.isComposing) l.composingText.lowercase() else l.previousWord
        val results = gestures?.decode(lay, path, prev) ?: return
        if (results.isEmpty()) return
        l.onGesture(results.map { it.word })
        feedback(tick = true)
        afterEdit()
    }

    private fun afterEdit() {
        updateKeyState()
        scheduleStrip()
        if (settings.devOverlay) { main.removeCallbacks(regionUpdate); main.post(regionUpdate) }
    }

    private fun updateKeyState() {
        val l = logic ?: return
        keyboard?.shiftOn = l.shift == ShiftState.AUTO || l.shift == ShiftState.ONESHOT
        keyboard?.capsLock = l.shift == ShiftState.LOCKED
    }

    /** Suggestions are computed after the key's text is already committed, never on the touch path. */
    private fun scheduleStrip() { main.removeCallbacks(stripUpdate); main.post(stripUpdate) }

    private fun updateStrip() {
        val l = logic ?: return
        val age = System.currentTimeMillis() - clips.newestAt
        val fresh = clips.newestAt > 0 && age in 0 until dev.lucid.keyboard.data.ClipboardHistory.FRESH_MS
        strip?.freshClip = if (fresh && !settings.privateMode) clips.items().firstOrNull() else null
        val t0 = SystemClock.elapsedRealtimeNanos()
        strip?.setState(l.stripState())
        lastStripMicros = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
    }

    // ---- feedback -------------------------------------------------------------------------------

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private fun feedback(tick: Boolean, key: Key? = null) {
        val h = settings.haptics
        if (h != Haptics.OFF) {
            val v = vibrator
            if (v != null && Build.VERSION.SDK_INT >= 29) {
                val effect = when {
                    tick || h == Haptics.LIGHT -> VibrationEffect.EFFECT_TICK
                    h == Haptics.MEDIUM -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
                v.vibrate(VibrationEffect.createPredefined(effect))
            } else keyboard?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (settings.sound && !tick) {
            val fx = when (key?.kind) {
                KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    // ---- strip / quick panel ------------------------------------------------------------------

    override fun onPick(text: String, isLiteral: Boolean) { logic?.onSuggestionPicked(text, isLiteral); afterEdit() }
    override fun onLearn(word: String) { logic?.learnWord(word); afterEdit() }
    override fun onNeverCorrect(word: String) { logic?.neverCorrect(word); afterEdit() }
    override fun onRemoveSuggestion(word: String) { logic?.removeSuggestion(word); afterEdit() }
    override fun onHideKeyboard() { requestHideSelf(0) }
    override fun onEmojiShortcut() = showMode(Mode.EMOJI)
    override fun onClipboard() {
        clipboard?.let { clips.capture(it, settings.privateMode, System.currentTimeMillis()) }
        showMode(if (mode == Mode.CLIPBOARD) Mode.LETTERS else Mode.CLIPBOARD)
    }
    override fun onPasteClip(text: String) { paste(text) }
    override fun onPaste(text: String) { paste(text); showMode(Mode.LETTERS) }
    override fun onRemoveClip(text: String) = clips.remove(text)
    override fun onClearClips() = clips.clear()

    private fun paste(text: String) {
        logic?.onText(text) ?: currentInputConnection?.commitText(text, 1)
        clips.markUsed()
        afterEdit()
    }
    override fun onSwitchKeyboard() { if (Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else showImePicker() }
    override fun onPunctuation(p: String) { logic?.onPunctuationShortcut(p) ?: currentInputConnection?.commitText("$p ", 1); afterEdit() }

    override fun onQuickPanel() {
        val c = content ?: return
        if (quickOpen) { closePanel(); return }
        quickOpen = true
        strip?.quickPanelOpen = true
        val qp = QuickPanel(this, renderer, this)
        bindQuick(qp)
        c.removeAllViews()
        c.addView(qp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()))
        animateIn(qp)
    }

    private fun bindQuick(qp: QuickPanel? = content?.getChildAt(0) as? QuickPanel) {
        qp?.bind(settings.correction, settings.adaptive, settings.suggestions, settings.autoCap, settings.privateMode, settings.languages,
            backdropMode(clientPackage).name, renderer.palette!!.label)
    }

    override fun setCorrection(m: CorrectionMode) = app.prefs.edit { putString(Prefs.K.CORRECTION, m.name) }
    override fun toggleAdaptive() = app.prefs.edit { putBoolean(Prefs.K.ADAPTIVE, !settings.adaptive) }
    override fun toggleSuggestions() = app.prefs.edit { putBoolean(Prefs.K.SUGGESTIONS, !settings.suggestions) }
    override fun toggleAutoCap() = app.prefs.edit { putBoolean(Prefs.K.AUTOCAP, !settings.autoCap) }
    override fun togglePrivate() = app.prefs.edit { putBoolean(Prefs.K.PRIVATE, !settings.privateMode) }
    // ---- resize mode ----
    private var resizing: dev.lucid.keyboard.ui.ResizeOverlay? = null

    override fun startResize() {
        closePanel()
        val c = content ?: return
        val overlay = dev.lucid.keyboard.ui.ResizeOverlay(this, renderer,
            dev.lucid.keyboard.ui.KeyboardSize(settings.heightScale, settings.widthScale, settings.keyboardOffset),
            onChange = { sz -> applySizeLive(sz) },
            onDone = { sz -> finishResize(sz) })
        resizing = overlay
        c.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        animateIn(overlay)
    }

    /** Rebuilds the keys at the new size while dragging (not saved until Done). */
    private fun applySizeLive(sz: dev.lucid.keyboard.ui.KeyboardSize) {
        settings = settings.copy(heightScale = sz.height, widthScale = sz.width, keyboardOffset = sz.offset)
        val kv = keyboard ?: return
        kv.layout = buildLayout()
        kv.layoutParams = kv.layoutParams.apply { height = keyboardHeight() }
        kv.requestLayout()
    }

    private fun finishResize(sz: dev.lucid.keyboard.ui.KeyboardSize) {
        resizing?.let { content?.removeView(it) }
        resizing = null
        app.prefs.edit {
            putFloat(Prefs.K.HEIGHT, sz.height); putFloat(Prefs.K.WIDTH, sz.width); putFloat(Prefs.K.OFFSET, sz.offset)
        }
    }

    override fun setBackdrop(mode: String) {
        val pkg = clientPackage ?: return
        app.prefs.edit { putString(Prefs.K.backdrop(pkg), mode) }
    }

    override fun toggleLanguage(code: String) {
        val next = if (code in settings.languages) settings.languages - code else settings.languages + code
        if (next.isEmpty()) return // at least one language stays on
        app.prefs.edit { putStringSet(Prefs.K.LANGUAGES, next) }
    }

    /** Activates the chosen languages; typing continues in all of them without switching. */
    private fun applyLanguages() {
        val eng = app.engine(0) ?: return
        if (eng.setLanguages(settings.languages)) logic?.decoder?.beginWord()
        val codes = eng.lm.packs.map { it.code.uppercase() }
        keyboard?.spaceLabel = if (codes.size > 1) codes.joinToString(" · ") else ""
    }
    override fun closePanel() {
        quickOpen = false; strip?.quickPanelOpen = false
        val target = if (mode == Mode.EMOJI) Mode.LETTERS else mode
        mode = Mode.EMOJI // force a transition back to the keys
        showMode(target)
    }

    override fun openSettings(page: String) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page", page))
        requestHideSelf(0)
    }

    // ---- emoji -------------------------------------------------------------------------------------

    override fun onEmoji(e: String) {
        logic?.onText(e) ?: currentInputConnection?.commitText(e, 1)
        if (!settings.privateMode) {
            val recent = (listOf(e) + app.prefs.sp.getString(Prefs.K.RECENT_EMOJI, "")!!.split(' ').filter { it.isNotEmpty() && it != e }).take(32)
            app.prefs.edit { putString(Prefs.K.RECENT_EMOJI, recent.joinToString(" ")) }
        }
        feedback(tick = false)
    }

    override fun onAbc() = showMode(Mode.LETTERS)

    // ---- settings changes ----------------------------------------------------------------------------

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == Prefs.K.RECENT_EMOJI) return
        val old = settings
        settings = app.prefs.load()
        logic?.settings = settings.typing()
        strip?.privateMode = settings.privateMode
        applyLanguages()
        applyAppearance()
        if (old.heightScale != settings.heightScale || old.spacingScale != settings.spacingScale || old.numberRow != settings.numberRow ||
            old.widthScale != settings.widthScale || old.keyboardOffset != settings.keyboardOffset) {
            if (!quickOpen && mode != Mode.EMOJI) showMode(mode)
        }
        if (quickOpen) bindQuick()
        scheduleStrip()
    }

    /** Root container: draws the glass plate behind the strip and keys. */
    class RootView(context: Context, private val renderer: GlassRenderer) : FrameLayout(context) {
        var blurActive = false
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            renderer.drawPlate(canvas, width.toFloat(), height.toFloat(), 18f * resources.displayMetrics.density, blurActive)
        }
    }
}
