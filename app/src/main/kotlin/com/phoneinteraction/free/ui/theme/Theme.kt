package com.phoneinteraction.free.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.phoneinteraction.free.data.ThemeMode

// Complete color role system. Every role is defined here so surface, error, outline, etc.
// do not fall back to the Material baseline colors.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2456D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF00695C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7F0E4),
    onSecondaryContainer = Color(0xFF002019),
    tertiary = Color(0xFFBF360C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCC),
    onTertiaryContainer = Color(0xFF3A0B00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    // surfaceContainer series. Without these values, Card/AlertDialog fall back to the
    // Material baseline purple-neutral color, which conflicts with the custom gray surface.
    surfaceDim = Color(0xFFDCD9E0),
    surfaceBright = Color(0xFFFDFBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F5FB),
    surfaceContainer = Color(0xFFF1EFF5),
    surfaceContainerHigh = Color(0xFFECEAF0),
    surfaceContainerHighest = Color(0xFFE6E4EA),
    surfaceTint = Color(0xFF2456D6),
    inversePrimary = Color(0xFFAEC6FF),
    scrim = Color(0xFF000000)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF5CDBC4),
    onSecondary = Color(0xFF003830),
    secondaryContainer = Color(0xFF005146),
    onSecondaryContainer = Color(0xFFB7F0E4),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF542100),
    tertiaryContainer = Color(0xFF7A3200),
    onTertiaryContainer = Color(0xFFFFDBCC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1B20),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF1A1B20),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    // Dark color side also needs surfaceContainer series to avoid Dialog/Card using default deep purple at night.
    surfaceDim = Color(0xFF131318),
    surfaceBright = Color(0xFF3A3A40),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1F2025),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    surfaceTint = Color(0xFFAEC6FF),
    inversePrimary = Color(0xFF2456D6),
    scrim = Color(0xFF000000)
)

// Custom typography and shapes for consistent appearance.
// Passed explicitly so the app does not fall back to the Material defaults.
private val PifTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
}

private val PifShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PifTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    SyncSystemBarAppearance(darkTheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PifTypography,
        shapes = PifShapes,
        content = content
    )
}

// Sync system bar appearance with theme. enabledEdgeToEdge() alone sets icons from
// system dark mode only, which conflicts with the app-controlled ThemeMode.
// Use ContextWrapper chain to find Activity: floating ComposeView is attached to Service context, safe to skip when not found.
@Composable
private fun SyncSystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
