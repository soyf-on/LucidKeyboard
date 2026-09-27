package dev.lucid.keyboard.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF1F7BFF)

@Composable
fun LucidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Accent, background = Color(0xFF0E1016), surface = Color(0xFF151822))
        else lightColorScheme(primary = Accent, background = Color(0xFFF1F3F8), surface = Color.White),
        content = content,
    )
}

/**
 * Colour field behind the settings UI. It is blurred (Android 12+) so the translucent
 * cards above read as frosted glass. This is our own content, so blurring it is allowed.
 */
@Composable
fun GlassBackdrop(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF0B0D13) else Color(0xFFEFF2F8))) {
        Canvas(Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(80.dp) else it }) {
            val a = if (dark) 0.55f else 0.70f
            drawCircle(Color(0xFF6EA8FF).copy(alpha = a), radius = size.width * 0.55f, center = Offset(size.width * 0.1f, size.height * 0.12f))
            drawCircle(Color(0xFFC3A0FF).copy(alpha = a), radius = size.width * 0.45f, center = Offset(size.width * 0.95f, size.height * 0.35f))
            drawCircle(Color(0xFFFFB3CF).copy(alpha = a * 0.8f), radius = size.width * 0.5f, center = Offset(size.width * 0.25f, size.height * 0.85f))
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(if (dark) listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF)) else listOf(Color(0xCCFFFFFF), Color(0x99FFFFFF))))
            .border(1.dp, Brush.linearGradient(if (dark) listOf(Color(0x66FFFFFF), Color(0x0DFFFFFF)) else listOf(Color(0xFFFFFFFF), Color(0x33FFFFFF))), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row { Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(valueLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (dark) Color(0x22FFFFFF) else Color(0x14000000)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) (if (dark) Color(0x55FFFFFF) else Color.White) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
fun Body(text: String) = Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), modifier = Modifier.padding(vertical = 4.dp))
