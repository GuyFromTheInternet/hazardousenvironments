package com.abandonsearch.hazardgrid.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HazardDarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1C1C1C),
    onPrimaryContainer = AccentPrimary,
    secondary = AccentStrong,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A1515),
    onSecondaryContainer = AccentStrong,
    tertiary = AccentPrimary.copy(alpha = 0.7f),
    onTertiary = Color.Black,
    background = NightBackground,
    onBackground = TextPrimary,
    surface = NightOverlay,
    onSurface = TextPrimary,
    surfaceVariant = NightOverlay,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorder,
    outlineVariant = SurfaceBorder.copy(alpha = 0.4f),
    scrim = Color(0xCC000000),
    surfaceTint = AccentPrimary,
)

@Composable
fun HazardGridTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        HazardDarkColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val transparent = Color.Transparent.toArgb()
                window.statusBarColor = transparent
                window.navigationBarColor = transparent
                val controller = WindowCompat.getInsetsController(window, view)
                controller?.isAppearanceLightStatusBars = false
                controller?.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HazardTypography,
        content = content
    )
}
