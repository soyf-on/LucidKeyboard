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
import dev.lucid.keyboard.ui.EmojiPanel
import dev.lucid.keyboard.ui.GlassRenderer
import dev.lucid.keyboard.ui.GlassTheme
import dev.lucid.keyboard.ui.KeyboardView
import dev.lucid.keyboard.ui.PopupOverlay
import dev.lucid.keyboard.ui.QuickPanel
import dev.lucid.keyboard.ui.SuggestionStrip
import java.util.concurrent.Executor

class LucidInputMethodService : InputMethodService(), KeyboardView.Listener, SuggestionStrip.Listener,
    QuickPanel.Actions, EmojiPanel.Actions, SharedPreferences.OnSharedPreferenceChangeListener {

    private val app get() = application as LucidApp
    private lateinit var settings: Settings
    private val editor = EditorBridge(this)
    private var logic: InputLogic? = null
    private var landscape = false

    private lateinit var renderer: GlassRenderer
    private var root: RootView? = null
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStrip? = null
    private var content: FrameLayout? = null
    private var overlay: PopupOverlay? = null

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD, PHONE, EMOJI }
    private var mode = Mode.LETTERS
    private var quickOpen = false
    private var blurActive = false
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
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val eng = app.engine()
        if (eng != null) {
            val spatial = eng.spatial(landscape)
            logic = InputLogic(eng.lm, TouchDecoder(eng.lm, spatial), WordCorrector(eng.lm, spatial), editor).also {
                it.settings = settings.typing()
                it.onLearned = { persist() }
            }
        } else Log.e(LucidApp.TAG, "models unavailable; keyboard runs with visible-key decoding only")
    }

    override fun onDestroy() {
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

    private fun layoutParams(width: Float): LayoutParams =
        Dimensions.params(width, resources.displayMetrics.density, landscape, settings, shouldOfferSwitchingToNextInputMethod())

    private fun keyboardHeight() = Layouts.totalHeight(layoutParams(resources.displayMetrics.widthPixels.toFloat())).toInt()

    private fun buildLayout(): KeyboardLayout {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val p = layoutParams(w)
        return when (mode) {
            Mode.SYMBOLS -> Layouts.symbols(p, 0)
            Mode.SYMBOLS2 -> Layouts.symbols(p, 1)
            Mode.NUMPAD -> Layouts.numpad(p, phone = false)
            Mode.PHONE -> Layouts.numpad(p, phone = true)
            else -> Layouts.qwerty(p)
        }
    }

    private fun showMode(m: Mode) {
        mode = m
        val c = content ?: return
        val kv = keyboard ?: return
        c.removeAllViews()
        quickOpen = false
        strip?.quickPanelOpen = false
        if (m == Mode.EMOJI) {
            val h = keyboardHeight()
            val recent = app.prefs.sp.getString(Prefs.K.RECENT_EMOJI, "")!!.split(' ').filter { it.isNotEmpty() }
            c.addView(EmojiPanel(this, renderer, emojiGroups(), recent, this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            return
        }
        kv.layout = buildLayout()
        kv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight())
        c.addView(kv)
        updateKeyState()
    }

    private var emojiCache: LinkedHashMap<String, List<String>>? = null
    private fun emojiGroups(): LinkedHashMap<String, List<String>> = emojiCache ?: LinkedHashMap<String, List<String>>().also { m ->
        assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            for (l in lines) { val t = l.split('\t'); if (t.size == 2) m.getOrPut(t[0]) { ArrayList() }.let { (it as ArrayList).add(t[1]) } }
        }
        emojiCache = m
    }

    private fun applyAppearance() {
        val tint = GlassTheme.wallpaperColor(this)
        renderer.palette = GlassTheme.palette(this, settings, tint)
        renderer.simple = settings.simpleRendering
        renderer.reduceMotion = settings.reduceMotion
        keyboard?.apply {
            keyPopups = settings.keyPopups && !settings.reduceMotion
            overlayEnabled = settings.devOverlay
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
            val want = settings.systemBlur && !settings.reduceTransparency && !settings.simpleRendering
            if (want && wm.isCrossWindowBlurEnabled) {
                val p = renderer.palette!!
                w.setBackgroundDrawable(GradientDrawable().apply {
                    setColor(p.blurTint)
                    val rad = 18f * resources.displayMetrics.density
                    cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
                })
                w.setBackgroundBlurRadius((34 * resources.displayMetrics.density).toInt())
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
        val field = EditorBridge.fieldFor(info)
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
        val label = when (enterAction) {
            EditorInfo.IME_ACTION_GO -> "go"; EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_SEND -> "send"; EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"; EditorInfo.IME_ACTION_PREVIOUS -> "prev"
            else -> "return"
        }
        keyboard?.enterLabel = label
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

    override fun onText(text: String) { logic?.onText(text) ?: currentInputConnection?.commitText(text, 1); afterEdit() }

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
        KeyKind.TO_SYMBOLS -> { openSettings("home"); true }
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

    override fun onQuickPanel() {
        val c = content ?: return
        if (quickOpen) { closePanel(); return }
        quickOpen = true
        strip?.quickPanelOpen = true
        val qp = QuickPanel(this, renderer, this)
        bindQuick(qp)
        c.removeAllViews()
        c.addView(qp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()))
    }

    private fun bindQuick(qp: QuickPanel? = content?.getChildAt(0) as? QuickPanel) {
        qp?.bind(settings.correction, settings.adaptive, settings.suggestions, settings.autoCap, settings.privateMode, renderer.palette!!.label)
    }

    override fun setCorrection(m: CorrectionMode) = app.prefs.edit { putString(Prefs.K.CORRECTION, m.name) }
    override fun toggleAdaptive() = app.prefs.edit { putBoolean(Prefs.K.ADAPTIVE, !settings.adaptive) }
    override fun toggleSuggestions() = app.prefs.edit { putBoolean(Prefs.K.SUGGESTIONS, !settings.suggestions) }
    override fun toggleAutoCap() = app.prefs.edit { putBoolean(Prefs.K.AUTOCAP, !settings.autoCap) }
    override fun togglePrivate() = app.prefs.edit { putBoolean(Prefs.K.PRIVATE, !settings.privateMode) }
    override fun closePanel() { quickOpen = false; strip?.quickPanelOpen = false; showMode(if (mode == Mode.EMOJI) Mode.LETTERS else mode) }

    override fun openSettings(page: String) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page", page))
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
        applyAppearance()
        if (old.heightScale != settings.heightScale || old.spacingScale != settings.spacingScale) { if (!quickOpen && mode != Mode.EMOJI) showMode(mode) }
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
