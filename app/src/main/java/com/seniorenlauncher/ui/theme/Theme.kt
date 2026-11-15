package com.seniorenlauncher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.seniorenlauncher.data.model.AppTheme

// Every role is set explicitly: anything left out falls back to Material's pale purple defaults.
// Solid, saturated colours with strong contrast (no wallpaper-based Material You colours).
val ClassicColors = darkColorScheme(
    primary = Color(0xFF60A5FA), // Blue 400: icons/text on dark, 7:1 on Slate 900
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF2563EB), // Blue 600 with white text
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF34D399), // Emerald 400
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFF047857), // Emerald 700
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFBBF24), // Amber 400
    onTertiary = Color(0xFF1C1917),
    tertiaryContainer = Color(0xFFB45309), // Amber 700
    onTertiaryContainer = Color.White,
    background = Color(0xFF0F172A), // Slate 900
    onBackground = Color.White,
    surface = Color(0xFF1E293B), // Slate 800
    onSurface = Color.White,
    surfaceVariant = Color(0xFF334155), // Slate 700
    onSurfaceVariant = Color(0xFFE2E8F0), // Slate 200
    surfaceTint = Color(0xFF60A5FA),
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = Color(0xFF162033),
    surfaceContainer = Color(0xFF1E293B),
    surfaceContainerHigh = Color(0xFF263449),
    surfaceContainerHighest = Color(0xFF334155),
    inverseSurface = Color(0xFFF8FAFC),
    inverseOnSurface = Color(0xFF0F172A),
    inversePrimary = Color(0xFF1D4ED8),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF475569),
    error = Color(0xFFF87171), // Red 400
    onError = Color(0xFF1C1917),
    errorContainer = Color(0xFFB91C1C), // Red 700
    onErrorContainer = Color.White
)
val HighContrastColors = darkColorScheme(
    primary = Color(0xFF00CCFF), onPrimary = Color.Black, secondary = Color(0xFF00FF00),
    background = Color.Black, surface = Color(0xFF111111), surfaceVariant = Color(0xFF222222),
    onBackground = Color(0xFFFFFF00), onSurface = Color(0xFFFFFF00), onSurfaceVariant = Color.White,
    error = Color.Red
)
val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8), // Blue 700
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2563EB),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF047857),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF059669),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD97706),
    onTertiaryContainer = Color.Black,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF334155), // Slate 700: readable grey
    surfaceTint = Color(0xFF1D4ED8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFDC2626),
    onErrorContainer = Color.White
)

@Composable
fun SeniorenLauncherTheme(
    appTheme: AppTheme = AppTheme.CLASSIC, 
    fontSize: Int = 18,
    dynamicColor: Boolean = false, // wallpaper colours made the UI pale and low-contrast
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme() || appTheme != AppTheme.LIGHT
    
    val colorScheme = when {
        appTheme == AppTheme.HIGH_CONTRAST -> HighContrastColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        appTheme == AppTheme.LIGHT -> LightColors
        else -> ClassicColors
    }

    val scaleFactor = fontSize / 18f
    
    val currentDensity = LocalDensity.current
    val customDensity = Density(
        density = currentDensity.density * scaleFactor,
        fontScale = currentDensity.fontScale * scaleFactor
    )

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}
