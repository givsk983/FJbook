package cn.chendian.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

val Gold = Color(0xFFD4B16A)
val Muted = Color(0xFFA29E97)
val Background = Color(0xFF0B0C10)
private val Palette = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF211B10),
    background = Background, onBackground = Color(0xFFEEE9DF),
    surface = Color(0xFF15161C), onSurface = Color(0xFFEEE9DF),
    surfaceVariant = Color(0xFF232127), onSurfaceVariant = Muted,
    outline = Color(0xFF51452F), error = Color(0xFFD9938E)
)

@Composable
fun HandbookTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Palette, content = content)
}

@Composable
fun GoldPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color.Transparent, border = BorderStroke(1.dp, Color(0xFF4A3D28))) {
        Column(Modifier.background(Brush.linearGradient(listOf(
            Color(0xFF252119), Color(0xFF17171C), Color(0xFF1D1B17)
        ))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun CardPreview() {
    HandbookTheme {
        GoldPanel(Modifier.padding(20.dp)) {
            Text("封禁手册", style = MaterialTheme.typography.headlineMedium, color = Gold)
            Text("你的游戏档案，一目了然。", color = Muted)
        }
    }
}
