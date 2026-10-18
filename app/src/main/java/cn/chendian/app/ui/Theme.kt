package cn.chendian.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

val Gold: Color @Composable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFD4B16A), onPrimary = Color(0xFF211B10),
    background = Color(0xFF0B0C10), onBackground = Color(0xFFEEE9DF),
    surface = Color(0xFF15161C), onSurface = Color(0xFFEEE9DF),
    surfaceVariant = Color(0xFF232127), onSurfaceVariant = Color(0xFFA29E97),
    outline = Color(0xFF51452F), error = Color(0xFFD9938E)
)
private val LightPalette = lightColorScheme(
    primary = Color(0xFF79551C), onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E3C3), onPrimaryContainer = Color(0xFF2A1C06),
    secondary = Color(0xFF6C5D43), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE1CB), onSecondaryContainer = Color(0xFF261D0D),
    background = Color(0xFFFAF7F0), onBackground = Color(0xFF24211C),
    surface = Color(0xFFFFFCF6), onSurface = Color(0xFF24211C),
    surfaceVariant = Color(0xFFECE5D9), onSurfaceVariant = Color(0xFF655E53),
    outline = Color(0xFF857964), error = Color(0xFFBA1A1A)
)

@Composable
fun HandbookTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = palette.background.toArgb()
                window.navigationBarColor = palette.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = palette, content = content)
}

@Composable
fun GoldPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val gradient = if (dark) listOf(Color(0xFF252119), Color(0xFF17171C), Color(0xFF1D1B17))
        else listOf(Color(0xFFFFF4DD), Color(0xFFFFFCF6), Color(0xFFF5EFE3))
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color.Transparent, border = BorderStroke(1.dp, if (dark) Color(0xFF4A3D28) else Color(0xFFD6C6A7))) {
        Column(Modifier.background(Brush.linearGradient(gradient)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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
