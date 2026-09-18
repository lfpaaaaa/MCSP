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
    primary = CampusBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E4FF),
    onPrimaryContainer = Color(0xFF001C3A),
    inversePrimary = Color(0xFFA5C8FF),
    secondary = CampusBlueGrey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E5F6),
    onSecondaryContainer = Color(0xFF0C1D29),
    tertiary = CampusAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE169),
    onTertiaryContainer = Color(0xFF221B00),
    error = CampusRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = CampusSurface,
    onBackground = Color(0xFF191C20),
    surface = CampusSurface,
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF42474E),
    surfaceTint = CampusBlue,
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC2C7CF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8FAFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F4FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF004882),
    onPrimaryContainer = Color(0xFFD4E4FF),
    inversePrimary = CampusBlue,
    secondary = Color(0xFFB8C9D8),
    onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD4E5F6),
    tertiary = Color(0xFFE4C443),
    onTertiary = Color(0xFF3A3000),
    tertiaryContainer = Color(0xFF544600),
    onTertiaryContainer = Color(0xFFFFE169),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = CampusSurfaceDark,
    onBackground = Color(0xFFE2E2E9),
    surface = CampusSurfaceDark,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceTint = Color(0xFFA5C8FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A)
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
