package au.edu.unimelb.campuscompanion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = CampusTeal,
    onPrimary = Color.White,
    secondary = CampusGreen,
    onSecondary = Color.White,
    tertiary = CampusAmber,
    onTertiary = Color.White,
    error = CampusRed,
    background = CampusSurface,
    surface = CampusSurface,
    surfaceVariant = Color(0xFFE4ECEB),
    outlineVariant = Color(0xFFC9D6D4)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FCFD2),
    onPrimary = Color(0xFF00363A),
    secondary = Color(0xFFB4D69D),
    onSecondary = Color(0xFF213515),
    tertiary = Color(0xFFF1C06E),
    onTertiary = Color(0xFF442B00),
    background = CampusSurfaceDark,
    surface = CampusSurfaceDark,
    surfaceVariant = Color(0xFF334342),
    outlineVariant = Color(0xFF465856)
)

@Composable
fun CampusCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CampusTypography,
        content = content
    )
}
