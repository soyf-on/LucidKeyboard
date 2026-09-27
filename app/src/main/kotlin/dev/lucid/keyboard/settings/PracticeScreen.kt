package dev.lucid.keyboard.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lucid.keyboard.LucidApp
import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.core.correct.WordCorrector
import dev.lucid.keyboard.core.eval.RecordedTap
import dev.lucid.keyboard.core.eval.Replay
import dev.lucid.keyboard.core.eval.TapStats
import dev.lucid.keyboard.core.geometry.Key
import dev.lucid.keyboard.core.geometry.KeyKind
import dev.lucid.keyboard.core.geometry.KeyboardLayout
import dev.lucid.keyboard.core.geometry.LayoutParams
import dev.lucid.keyboard.core.geometry.Layouts
import dev.lucid.keyboard.core.input.FieldInfo
import dev.lucid.keyboard.core.input.InputLogic
import dev.lucid.keyboard.core.input.MemoryEditor
import dev.lucid.keyboard.core.touch.DecoderSettings
import dev.lucid.keyboard.core.touch.TapDecision
import dev.lucid.keyboard.core.touch.TouchDecoder
import dev.lucid.keyboard.core.touch.TouchPoint
import dev.lucid.keyboard.data.Prefs
import dev.lucid.keyboard.ui.Dimensions
import dev.lucid.keyboard.ui.GlassRenderer
import dev.lucid.keyboard.ui.GlassTheme
import dev.lucid.keyboard.ui.KeyboardView

private val PRACTICE = listOf(
    "the quick brown fox jumps over the lazy dog", "see you at the station at noon", "can you send me the notes from today",
    "my keyboard finally understands me", "we should grab lunch next week", "please call me back when you can",
    "the package arrived this morning", "i think the meeting went well", "lets try something different",
    "pack my box with five dozen liquor jugs", "how vexingly quick daft zebras jump", "sphinx of black quartz judge my vow",
)
private val CALIBRATION = listOf(
    "the quick brown fox jumps over the lazy dog", "pack my box with five dozen liquor jugs", "sphinx of black quartz judge my vow",
    "how vexingly quick daft zebras jump", "jackdaws love my big sphinx of quartz",
)

/**
 * Local practice / calibration. Nothing here leaves the device or is saved, except
 * calibration samples, which go into the touch model when you finish.
 *
 * Practice compares decoders on *your* taps: every tap is recorded with the letter the
 * prompt asked for, then the identical sequence is replayed through fixed and adaptive
 * decoding. (You see the adaptive output while typing, which can influence how you type.)
 */
@Composable
fun PracticeScreen(calibration: Boolean) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LucidApp
    val eng = app.engine() ?: run { Body("The language model could not be loaded."); return }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settings = remember { app.prefs.load() }
    val prompts = if (calibration) CALIBRATION else PRACTICE.shuffled()
    var promptIndex by remember { mutableIntStateOf(0) }
    val phraseTaps = remember { mutableStateListOf<RecordedTap>() }
    val allTaps = remember { mutableStateListOf<RecordedTap>() }
    var typed by remember { mutableStateOf("") }
    var discarded by remember { mutableIntStateOf(0) }
    var layoutRef by remember { mutableStateOf<KeyboardLayout?>(null) }
    var results by remember { mutableStateOf<List<Pair<String, TapStats>>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }

    val prompt = prompts[promptIndex % prompts.size]
    val editor = remember { MemoryEditor() }
    val logic = remember {
        InputLogic(eng.lm, TouchDecoder(eng.lm, eng.spatial(landscape)), WordCorrector(eng.lm, eng.spatial(landscape)), editor).also {
            it.settings = settings.typing().copy(correction = CorrectionMode.OFF, suggestions = false, autoCapitalize = false, privateMode = true)
            it.startInput(FieldInfo(learningAllowed = false, autoCapAllowed = false))
        }
    }

    fun nextPhrase() {
        if (phraseTaps.size == prompt.length) allTaps += phraseTaps else if (phraseTaps.isNotEmpty()) discarded++
        phraseTaps.clear(); editor.clear(); logic.startInput(FieldInfo(learningAllowed = false, autoCapAllowed = false))
        typed = ""; promptIndex++
    }

    fun computeResults() {
        val lay = layoutRef ?: return
        val taps = allTaps.toList()
        val spatialData = eng.spatial(landscape).data
        results = listOf(
            "Fixed (visible keys)" to Replay.run(eng.lm, lay, taps, Replay.FIXED),
            "Adaptive ${(settings.strength * 100).toInt()}%" to Replay.run(eng.lm, lay, taps, DecoderSettings(strength = settings.strength.toDouble()), spatialData),
            "Adaptive, no personal model" to Replay.run(eng.lm, lay, taps, DecoderSettings(strength = settings.strength.toDouble(), personalOffsets = false)),
        )
    }

    if (finished && calibration) {
        GlassCard { Text("Calibration saved", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Body("${allTaps.size} taps were used to learn your touch offsets for ${if (landscape) "landscape" else "portrait"}. You can recalibrate or reset any time.") }
        return
    }

    GlassCard {
        Text(if (calibration) "Type each phrase at your normal speed" else "Type the phrase, then tap Next", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(6.dp))
        Text(prompt, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(typed.ifEmpty { " " }, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Body("Taps this phrase: ${phraseTaps.size}/${prompt.length} · taps recorded: ${allTaps.size}" + if (discarded > 0) " · $discarded phrase(s) discarded (tap count didn't match)" else "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nextPhrase() }) { Text("Next") }
            if (!calibration) OutlinedButton(onClick = { computeResults() }, enabled = allTaps.isNotEmpty()) { Text("Compare") }
            if (calibration) OutlinedButton(onClick = {
                val lay = layoutRef
                if (lay != null) {
                    if (phraseTaps.size == prompt.length) allTaps += phraseTaps
                    val spatial = eng.spatial(landscape)
                    for (t in allTaps) {
                        val k = lay.letter(t.intended) ?: continue
                        spatial.learn(k.output, ((t.point.x - k.cx) / lay.unitW).toDouble(), ((t.point.y - k.cy) / lay.unitH).toDouble())
                    }
                    app.storage.saveSpatialSoon(spatial, landscape, 0)
                    app.prefs.edit { putBoolean(Prefs.K.CALIBRATED, true) }
                }
                finished = true
            }, enabled = allTaps.isNotEmpty() || phraseTaps.size == prompt.length) { Text("Finish") }
        }
    }
    if (results.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        GlassCard {
            Text("Your taps (${results.first().second.taps})", fontWeight = FontWeight.SemiBold)
            for ((name, st) in results) Text("$name: ${"%.1f".format(st.cer * 100)}% errors · ${st.rescues} rescued · ${st.unwantedSubstitutions} unwanted", fontSize = 14.sp)
            Body("Replays of the same recorded taps. Only phrases where the number of taps matched the prompt are scored. Stays on this phone and is discarded when you leave.")
        }
    }
    Spacer(Modifier.height(10.dp))
    val density = LocalDensity.current.density
    val heightPx = remember { Layouts.totalHeight(params(ctx.resources.displayMetrics.widthPixels.toFloat(), density, landscape)) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height((heightPx / density).dp),
        factory = { c ->
            val renderer = GlassRenderer(density).apply { palette = GlassTheme.palette(c, settings, GlassTheme.wallpaperColor(c)); reduceMotion = settings.reduceMotion; simple = settings.simpleRendering }
            object : KeyboardView(c, renderer) {
                override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                    super.onSizeChanged(w, h, ow, oh)
                    if (w > 0) { layout = Layouts.qwerty(params(w.toFloat(), density, landscape)); layoutRef = layout }
                }
                override fun onDraw(canvas: android.graphics.Canvas) {
                    renderer.drawPlate(canvas, width.toFloat(), height.toFloat(), 18 * density, false)
                    super.onDraw(canvas)
                }
            }.also { kv ->
                kv.keyPopups = settings.keyPopups
                kv.listener = object : KeyboardView.Listener {
                    override fun decide(layout: KeyboardLayout, point: TouchPoint): TapDecision = logic.decide(layout, point)
                    override fun onKeyCommitted(key: Key, point: TouchPoint, decision: TapDecision) {
                        if (key.kind != KeyKind.LETTER && key.kind != KeyKind.SPACE) return
                        if (phraseTaps.size < prompt.length) phraseTaps += RecordedTap(prompt[phraseTaps.size], point)
                        if (key.kind == KeyKind.LETTER) logic.onLetterTap(kv.layout!!, key, point) else logic.onSeparator(" ")
                        typed = editor.text
                    }
                    override fun onText(text: String) {}
                    override fun onBackspace() { if (phraseTaps.isNotEmpty()) phraseTaps.removeAt(phraseTaps.lastIndex); logic.onBackspace(); typed = editor.text }
                    override fun onShift(doubleTap: Boolean) {}
                    override fun onFunctionKey(key: Key) {}
                    override fun onCursorMove(steps: Int) {}
                    override fun onKeyDownFeedback(key: Key) { kv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                }
            }
        },
    )
}

private fun params(width: Float, d: Float, landscape: Boolean): LayoutParams =
    Dimensions.params(width, d, landscape, LucidApp.instance.prefs.load())
