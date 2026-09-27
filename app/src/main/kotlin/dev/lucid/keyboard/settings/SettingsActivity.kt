package dev.lucid.keyboard.settings

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucid.keyboard.LucidApp
import dev.lucid.keyboard.core.correct.CorrectionMode
import dev.lucid.keyboard.data.Haptics
import dev.lucid.keyboard.data.Prefs
import dev.lucid.keyboard.data.Settings
import dev.lucid.keyboard.data.ThemeMode
import dev.lucid.keyboard.ime.LucidInputMethodService
import kotlinx.coroutines.delay

class SettingsActivity : ComponentActivity() {
    private val page = mutableStateOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        page.value = intent.getStringExtra("page") ?: "home"
        setContent { LucidTheme { GlassBackdrop { App(page.value) { page.value = it } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("page")?.let { page.value = it }
    }
}

/** Observes SharedPreferences so every screen reflects changes made from the keyboard too. */
@Composable
fun rememberSettings(): Settings {
    val app = LocalContext.current.applicationContext as LucidApp
    var s by remember { mutableStateOf(app.prefs.load()) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> s = app.prefs.load() }
        app.prefs.sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { app.prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return s
}

@Composable
private fun App(page: String, go: (String) -> Unit) {
    BackHandler(enabled = page != "home") { go("home") }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (page) {
            "dictionary" -> Page("Dictionary", go) { DictionaryScreen() }
            "practice" -> Page("Practice", go) { PracticeScreen(calibration = false) }
            "calibrate" -> Page("Calibration", go) { PracticeScreen(calibration = true) }
            "typing" -> Page("Typing accuracy", go) { TypingScreen(go) }
            "correction" -> Page("Autocorrect", go) { CorrectionScreen() }
            "appearance" -> Page("Appearance", go) { AppearanceScreen() }
            "privacy" -> Page("Privacy", go) { PrivacyScreen() }
            "about" -> Page("About & licences", go) { AboutScreen() }
            "languages" -> Page("Languages", go) { LanguagesScreen() }
            else -> Page("Lucid Keyboard", null) { HomeScreen(go) }
        }
    }
}

@Composable
private fun Page(title: String, go: ((String) -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (go != null) TextButton(onClick = { go("home") }) { Text("‹ Back", fontSize = 16.sp) }
            Text(title, fontSize = if (go == null) 30.sp else 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = if (go == null) 8.dp else 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) { content() }
    }
}

private fun edit(ctx: android.content.Context, block: SharedPreferences.Editor.() -> Unit) =
    (ctx.applicationContext as LucidApp).prefs.edit(block)

// ---- Home ------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    val id = ComponentName(ctx, LucidInputMethodService::class.java).flattenToShortString()
    var enabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            enabled = imm.enabledInputMethodList.any { it.id == id || it.packageName == ctx.packageName }
            val cur = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            selected = cur.startsWith(ctx.packageName + "/")
            delay(800)
        }
    }
    val s = rememberSettings()
    if (!enabled || !selected) {
        SectionTitle("SET UP")
        GlassCard {
            Text("Two steps to start typing", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            SetupStep(1, "Enable Lucid in system keyboard settings", done = enabled) { ctx.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
            SetupStep(2, "Choose Lucid as your keyboard", done = selected, enabled = enabled) { imm.showInputMethodPicker() }
            Body("Android shows a standard warning that any keyboard can collect what you type. Lucid has no internet permission, so nothing you type can leave the phone.")
        }
    }
    SectionTitle("TRY IT")
    GlassCard {
        var t by remember { mutableStateOf("") }
        OutlinedTextField(value = t, onValueChange = { t = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Type here to test") })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { go("practice") }) { Text("Practice & compare") }
            OutlinedButton(onClick = { go("calibrate") }) { Text("Calibrate") }
        }
    }
    SectionTitle("KEYBOARD")
    GlassCard {
        NavRow("Typing accuracy", if (s.adaptive) "Adaptive targets on · strength ${(s.strength * 100).toInt()}%" else "Adaptive targets off") { go("typing") }
        NavRow("Languages", s.languages.sortedBy { dev.lucid.keyboard.core.lm.ModelBundle.LANGUAGES.indexOf(it) }.joinToString(" + ") { dev.lucid.keyboard.core.lm.ModelBundle.NAMES[it] ?: it }) { go("languages") }
        NavRow("Autocorrect", s.correction.name.lowercase().replaceFirstChar { it.uppercase() }) { go("correction") }
        NavRow("Dictionary", "Your words, replacements, blocked suggestions") { go("dictionary") }
        NavRow("Appearance", "Glass, height, spacing, haptics") { go("appearance") }
        NavRow("Privacy", if (s.privateMode) "Private mode ON" else "On-device only") { go("privacy") }
        NavRow("About & licences") { go("about") }
    }
}

@Composable
private fun SetupStep(n: Int, text: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "$n", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (done) Accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 12.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 15.sp)
        if (!done) Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Open") }
    }
}

// ---- Typing accuracy -----------------------------------------------------------------------------

@Composable
private fun TypingScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LucidApp
    val s = rememberSettings()
    var confirmReset by remember { mutableStateOf(false) }
    SectionTitle("ADAPTIVE TOUCH TARGETS")
    GlassCard {
        ToggleRow("Adaptive targets", "Invisible key areas adapt to the likely next letter and your touch pattern. Keys never move.", s.adaptive) { v -> edit(ctx) { putBoolean(Prefs.K.ADAPTIVE, v) } }
        SliderRow("Adaptation strength", s.strength, 0f..1f, "${(s.strength * 100).toInt()}%", enabled = s.adaptive) { v -> edit(ctx) { putFloat(Prefs.K.STRENGTH, (v * 20).toInt() / 20f) } }
        Body("Taps near the centre of a key always produce that key, and no key's area can grow more than a quarter key into its neighbour, at any strength.")
        ToggleRow("Use my touch pattern", "Corrects for where you personally tend to tap.", s.personalOffsets, enabled = s.adaptive) { v -> edit(ctx) { putBoolean(Prefs.K.PERSONAL, v) } }
        ToggleRow("Keep learning my touch pattern", "Learns only from words you keep; never from corrections you undo.", s.learnTouch, enabled = s.adaptive) { v -> edit(ctx) { putBoolean(Prefs.K.LEARN_TOUCH, v) } }
        val samples = app.engine(0)?.let { it.portrait.data.samples + it.landscape.data.samples } ?: 0
        Body("Learned touches so far: $samples")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { go("calibrate") }) { Text("Calibrate (optional)") }
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset touch model") }
        }
    }
    SectionTitle("DEVELOPER")
    GlassCard {
        ToggleRow("Show effective touch regions", "Colours each point by the key it would produce right now, marks your taps and shows prediction confidence. Costs extra CPU.", s.devOverlay) { v -> edit(ctx) { putBoolean(Prefs.K.DEV, v) } }
        Body("Model load: ${app.loadMillis} ms (measured on this device).")
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        confirmButton = { TextButton(onClick = { app.resetTouchModels(); edit(ctx) { putBoolean(Prefs.K.CALIBRATED, false) }; confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        title = { Text("Reset touch model?") },
        text = { Text("Forgets how you personally tap (portrait and landscape). Your vocabulary is not affected.") },
    )
}

// ---- Languages -----------------------------------------------------------------------------------

@Composable
private fun LanguagesScreen() {
    val ctx = LocalContext.current
    val s = rememberSettings()
    SectionTitle("TYPE IN")
    GlassCard {
        for (code in dev.lucid.keyboard.core.lm.ModelBundle.LANGUAGES) {
            val on = code in s.languages
            ToggleRow(dev.lucid.keyboard.core.lm.ModelBundle.NAMES[code] ?: code, null, on, enabled = !(on && s.languages.size == 1)) { v ->
                edit(ctx) { putStringSet(Prefs.K.LANGUAGES, if (v) s.languages + code else s.languages - code) }
            }
        }
        Body("All selected languages work at the same time — no switching. The keyboard follows which language you are writing in, word by word, so predictions and corrections match. A word that is valid in any selected language is never corrected into another language.")
        Body("Dutch: accents are restored from the dictionary (ideeen → ideeën), and “IJ” is capitalised together (IJs, IJsland). Long-press e, i, o, u for ë, é, ï and other accents.")
    }
}

// ---- Autocorrect --------------------------------------------------------------------------------

@Composable
private fun CorrectionScreen() {
    val ctx = LocalContext.current
    val s = rememberSettings()
    val modes = CorrectionMode.entries
    SectionTitle("AUTOCORRECT")
    GlassCard {
        Choice(modes.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, modes.indexOf(s.correction)) { i -> edit(ctx) { putString(Prefs.K.CORRECTION, modes[i].name) } }
        Spacer(Modifier.height(8.dp))
        Body(when (s.correction) {
            CorrectionMode.OFF -> "Never changes what you type. Suggestions still appear if enabled."
            CorrectionMode.GENTLE -> "Fixes only clear one-slip typos of unknown words (teh → the). Leaves informal spellings like “dont” alone."
            CorrectionMode.BALANCED -> "Fixes typos of unknown words, up to two edits, and missing apostrophes (dont → don't). Never replaces a real word."
            CorrectionMode.STRONG -> "Also fixes likely slips that happen to form a rare real word, when the evidence is overwhelming."
        })
        Body("In every mode: backspace right after a correction restores exactly what you typed, and that correction is never applied again. Names you capitalise, ALL-CAPS, and words with digits are left alone.")
    }
    SectionTitle("OPTIONS")
    GlassCard {
        ToggleRow("Suggestions", "Word suggestions above the keys.", s.suggestions) { v -> edit(ctx) { putBoolean(Prefs.K.SUGGESTIONS, v) } }
        ToggleRow("Automatic capitalisation", null, s.autoCap) { v -> edit(ctx) { putBoolean(Prefs.K.AUTOCAP, v) } }
        ToggleRow("Double-space for period", null, s.doubleSpacePeriod) { v -> edit(ctx) { putBoolean(Prefs.K.DOUBLE_SPACE, v) } }
        ToggleRow("Learn new words", "Words you type twice (or keep after undoing a correction) become yours. Typos you delete are never learned.", s.learnWords) { v -> edit(ctx) { putBoolean(Prefs.K.LEARN_WORDS, v) } }
    }
}

// ---- Appearance ----------------------------------------------------------------------------------

@Composable
private fun AppearanceScreen() {
    val ctx = LocalContext.current
    val s = rememberSettings()
    val wm = ctx.getSystemService(WindowManager::class.java)
    val blurAvailable = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    SectionTitle("THEME")
    GlassCard {
        Choice(listOf("System", "Light", "Dark"), s.theme.ordinal) { i -> edit(ctx) { putString(Prefs.K.THEME, ThemeMode.entries[i].name) } }
        SliderRow("Transparency", s.transparency, 0f..1f, "${(s.transparency * 100).toInt()}%", enabled = !s.reduceTransparency) { v -> edit(ctx) { putFloat(Prefs.K.TRANSPARENCY, v) } }
        ToggleRow("Real background blur", if (blurAvailable) "Available on this device right now." else "Not available right now (needs Android 12+, the system blur setting on, and battery saver off).", s.systemBlur, enabled = blurAvailable) { v -> edit(ctx) { putBoolean(Prefs.K.BLUR, v) } }
        ToggleRow("Tint from wallpaper", null, s.wallpaperTint) { v -> edit(ctx) { putBoolean(Prefs.K.WALLPAPER_TINT, v) } }
    }
    SectionTitle("LAYOUT")
    GlassCard {
        ToggleRow("Number row", "Digits above the letters. Letters then hold symbols on long-press (hold m for ?).", s.numberRow) { v -> edit(ctx) { putBoolean(Prefs.K.NUMBER_ROW, v) } }
        ToggleRow("Symbol hints", "Show the long-press symbol in the corner of each key.", s.digitHints) { v -> edit(ctx) { putBoolean(Prefs.K.DIGIT_HINTS, v) } }
        ToggleRow("Slide to type", "Glide across the letters without lifting your finger.", s.slideToType) { v -> edit(ctx) { putBoolean(Prefs.K.SLIDE, v) } }
    }
    SectionTitle("SIZE")
    GlassCard {
        SliderRow("Keyboard height", s.heightScale, 0.8f..1.25f, "${(s.heightScale * 100).toInt()}%") { v -> edit(ctx) { putFloat(Prefs.K.HEIGHT, (v * 20).toInt() / 20f) } }
        SliderRow("Key spacing", s.spacingScale, 0.5f..1.6f, "${(s.spacingScale * 100).toInt()}%") { v -> edit(ctx) { putFloat(Prefs.K.SPACING, (v * 10).toInt() / 10f) } }
    }
    SectionTitle("FEEDBACK")
    GlassCard {
        Text("Haptics", fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Choice(listOf("Off", "Light", "Medium", "Strong"), s.haptics.ordinal) { i -> edit(ctx) { putString(Prefs.K.HAPTICS, Haptics.entries[i].name) } }
        ToggleRow("Key sounds", null, s.sound) { v -> edit(ctx) { putBoolean(Prefs.K.SOUND, v) } }
        ToggleRow("Key preview pop-ups", null, s.keyPopups) { v -> edit(ctx) { putBoolean(Prefs.K.POPUPS, v) } }
    }
    SectionTitle("ACCESSIBILITY & PERFORMANCE")
    GlassCard {
        ToggleRow("Reduce motion", "No lift or flex; instant highlights.", s.reduceMotion) { v -> edit(ctx) { putBoolean(Prefs.K.REDUCE_MOTION, v) } }
        ToggleRow("Reduce transparency", "Frosted, nearly opaque keys and background.", s.reduceTransparency) { v -> edit(ctx) { putBoolean(Prefs.K.REDUCE_TRANSPARENCY, v) } }
        ToggleRow("Increase contrast", "Solid keys with a contrasting border.", s.increaseContrast) { v -> edit(ctx) { putBoolean(Prefs.K.CONTRAST, v) } }
        ToggleRow("Simple rendering", "Flat keys without glass effects, for maximum responsiveness and battery.", s.simpleRendering) { v -> edit(ctx) { putBoolean(Prefs.K.SIMPLE, v) } }
    }
}

// ---- Privacy --------------------------------------------------------------------------------------

@Composable
private fun PrivacyScreen() {
    val ctx = LocalContext.current
    val s = rememberSettings()
    SectionTitle("PRIVATE MODE")
    GlassCard {
        ToggleRow("Private mode", "Nothing is learned or remembered: no new words, no touch learning, no recent emoji.", s.privateMode) { v -> edit(ctx) { putBoolean(Prefs.K.PRIVATE, v) } }
        Body("Also available from the keyboard: tap the sliders button on the left of the suggestion bar.")
    }
    SectionTitle("HOW YOUR DATA IS HANDLED")
    GlassCard {
        Body("• Lucid has no internet permission. Typing, prediction and learning happen only on this phone.")
        Body("• No analytics, no advertising, no crash reporting.")
        Body("• Password, e-mail, URL and number fields are never learned from or corrected, and are never stored.")
        Body("• Apps that ask keyboards not to learn (incognito tabs, some messengers) are honoured.")
        Body("• What is stored: your personal words and replacements, counts of word pairs you use (e.g. “see you”) so suggestions follow your phrasing, and touch-offset statistics. Messages themselves are not stored. Everything is in app-private storage and excluded from backups.")
        Body("• Word-pair counts are cleared by Dictionary ▸ Forget learned words, and are not collected in private mode, sensitive fields, or when “Learn new words” is off.")
        Body("• Export, import or delete it any time in Dictionary.")
    }
}

// ---- About -----------------------------------------------------------------------------------------

@Composable
private fun AboutScreen() {
    SectionTitle("LUCID KEYBOARD 0.1.0")
    GlassCard {
        Body("An English (US) keyboard built around adaptive touch targets: invisible key areas combine where you touch with what you are likely to type, within strict limits so every key stays typeable.")
    }
    SectionTitle("OPEN DATA AND LICENCES")
    GlassCard {
        Body("Word frequencies: wordfreq by Robyn Speer — data licensed CC BY-SA 4.0 (includes data from Wikipedia, OpenSubtitles, Reddit and others). The bundled frequency list is a derived database under the same licence.")
        Body("Spell-checked word list and capitalisation: SCOWL / en_US Hunspell dictionary © Kevin Atkinson and contributors, permissive licence (see third_party/SCOWL-LICENSE.txt).")
        Body("Emoji list: Unicode emoji-test.txt, Unicode License v3.")
        Body("AndroidX, Jetpack Compose, Kotlin and kotlinx.serialization: Apache License 2.0.")
        Body("Design inspired by Apple's publicly documented Liquid Glass. Not affiliated with Apple.")
    }
}
