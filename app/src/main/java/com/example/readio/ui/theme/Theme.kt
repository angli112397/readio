package com.example.readio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.readio.domain.model.ReadingTheme

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// ── Reading theme color schemes ─────────────────────────────────────────────
// Color values derived from Kindle/Apple Books research.
// Contrast ratios: WARM/SEPIA primary text ~11:1, NIGHT ~14:1 (WCAG AAA).

private fun ColorScheme.applyWarm() = copy(
    background                = Color(0xFFFFF8EF),
    onBackground              = Color(0xFF3B2418),
    surface                   = Color(0xFFFFF8EF),
    onSurface                 = Color(0xFF3B2418),
    surfaceVariant            = Color(0xFFF5EADA),
    onSurfaceVariant          = Color(0xFF7B5C46),
    surfaceContainerLowest    = Color(0xFFFFF8EF),
    surfaceContainerLow       = Color(0xFFF8F0E3),
    surfaceContainer          = Color(0xFFF5EADA),
    surfaceContainerHigh      = Color(0xFFEEE0CB),
    surfaceContainerHighest   = Color(0xFFE8D8BF),
    inverseSurface            = Color(0xFF3B2418),
    inverseOnSurface          = Color(0xFFFFF8EF),
)

private fun ColorScheme.applySepia() = copy(
    background                = Color(0xFFF2E8D5),
    onBackground              = Color(0xFF3B2418),
    surface                   = Color(0xFFF2E8D5),
    onSurface                 = Color(0xFF3B2418),
    surfaceVariant            = Color(0xFFE8D9C0),
    onSurfaceVariant          = Color(0xFF7B5C46),
    surfaceContainerLowest    = Color(0xFFF2E8D5),
    surfaceContainerLow       = Color(0xFFEDE1CB),
    surfaceContainer          = Color(0xFFE8D9C0),
    surfaceContainerHigh      = Color(0xFFE1D0B4),
    surfaceContainerHighest   = Color(0xFFDAC7A8),
    inverseSurface            = Color(0xFF3B2418),
    inverseOnSurface          = Color(0xFFF2E8D5),
)

private fun ColorScheme.applyNight() = copy(
    background                = Color(0xFF1A1A2E),
    onBackground              = Color(0xFFE8DCC8),
    surface                   = Color(0xFF1A1A2E),
    onSurface                 = Color(0xFFE8DCC8),
    surfaceVariant            = Color(0xFF252538),
    onSurfaceVariant          = Color(0xFF9A8E82),
    surfaceContainerLowest    = Color(0xFF141422),
    surfaceContainerLow       = Color(0xFF1E1E32),
    surfaceContainer          = Color(0xFF252538),
    surfaceContainerHigh      = Color(0xFF2E2E42),
    surfaceContainerHighest   = Color(0xFF38384E),
    inverseSurface            = Color(0xFFE8DCC8),
    inverseOnSurface          = Color(0xFF1A1A2E),
)

@Composable
fun ReadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    readingTheme: ReadingTheme = ReadingTheme.DEFAULT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (readingTheme) {
        ReadingTheme.DEFAULT -> when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else      -> LightColorScheme
        }
        ReadingTheme.WARM  -> LightColorScheme.applyWarm()
        ReadingTheme.SEPIA -> LightColorScheme.applySepia()
        ReadingTheme.NIGHT -> DarkColorScheme.applyNight()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
