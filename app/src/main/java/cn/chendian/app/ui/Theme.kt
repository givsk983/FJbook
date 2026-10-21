package cn.chendian.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

val Gold: Color @Composable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Background: Color @Composable get() = MaterialTheme.colorScheme.background

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色 · 铂金银"), DARK("深色 · 黑金")
}

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }
private val LocalDarkTheme = staticCompositionLocalOf { false }
private const val ThemePreferences = "appearance"
private const val ThemeModeKey = "theme_mode"

private fun SharedPreferences.themeMode(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == getString(ThemeModeKey, null) } ?: ThemeMode.SYSTEM

fun setThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(ThemePreferences, Context.MODE_PRIVATE)
        .edit().putString(ThemeModeKey, mode.name).apply()
}
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFD4B16A), onPrimary = Color(0xFF211B10),
    background = Color(0xFF0B0C10), onBackground = Color(0xFFEEE9DF),
    surface = Color(0xFF15161C), onSurface = Color(0xFFEEE9DF),
    surfaceVariant = Color(0xFF232127), onSurfaceVariant = Color(0xFFA29E97),
    outline = Color(0xFF51452F), error = Color(0xFFD9938E)
)
private val LightPalette = lightColorScheme(
    primary = Color(0xFF505C68), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE5EB), onPrimaryContainer = Color(0xFF202A34),
    secondary = Color(0xFF59616B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7EC), onSecondaryContainer = Color(0xFF252C35),
    tertiary = Color(0xFF576574), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFE7EF), onTertiaryContainer = Color(0xFF202D3B),
    background = Color(0xFFF4F6F8), onBackground = Color(0xFF20252C),
    surface = Color(0xFFFCFDFE), onSurface = Color(0xFF20252C),
    surfaceVariant = Color(0xFFE5E9EE), onSurfaceVariant = Color(0xFF5C6570),
    surfaceTint = Color(0xFF737F8C),
    surfaceDim = Color(0xFFD9DEE4), surfaceBright = Color(0xFFFCFDFE),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F6),
    surfaceContainer = Color(0xFFEBEFF3), surfaceContainerHigh = Color(0xFFE5E9EE),
    surfaceContainerHighest = Color(0xFFDFE4EA),
    inverseSurface = Color(0xFF2C323A), inverseOnSurface = Color(0xFFEEF1F5),
    inversePrimary = Color(0xFFBDC8D4),
    outline = Color(0xFF7A8592), outlineVariant = Color(0xFFC7CFD8),
    error = Color(0xFFBA1A1A)
)

@Composable
fun HandbookTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(ThemePreferences, Context.MODE_PRIVATE) }
    var mode by remember(preferences) { mutableStateOf(preferences.themeMode()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ThemeModeKey) mode = preferences.themeMode()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mode = preferences.themeMode()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
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
    CompositionLocalProvider(LocalThemeMode provides mode, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = palette, content = content)
    }
}

@Composable
fun GoldPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalDarkTheme.current
    val gradient = if (dark) listOf(Color(0xFF252119), Color(0xFF17171C), Color(0xFF1D1B17))
        else listOf(Color(0xFFE4E9EF), Color(0xFFFFFFFF), Color(0xFFF1F4F7), Color(0xFFDDE3EA))
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color.Transparent, border = BorderStroke(1.dp, if (dark) Color(0xFF4A3D28) else Color(0xFFC4CDD7))) {
        Column(Modifier.background(Brush.linearGradient(gradient)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Preview(name = "黑金暗色", showBackground = true, backgroundColor = 0xFF0B0C10,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "铂金银浅色", showBackground = true, backgroundColor = 0xFFF4F6F8,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun CardPreview() {
    HandbookTheme {
        GoldPanel(Modifier.padding(20.dp)) {
            Text("封禁手册", style = MaterialTheme.typography.headlineMedium, color = Gold)
            Text("你的游戏档案，一目了然。", color = Muted)
        }
    }
}
