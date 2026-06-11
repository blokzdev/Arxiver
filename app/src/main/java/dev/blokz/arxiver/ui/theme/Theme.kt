package dev.blokz.arxiver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = Indigo40,
        onPrimary = Neutral99,
        primaryContainer = Indigo90,
        onPrimaryContainer = Indigo10,
        secondary = Amber40,
        onSecondary = Neutral99,
        secondaryContainer = Amber90,
        onSecondaryContainer = Amber10,
        background = Neutral99,
        onBackground = Neutral10,
        surface = Neutral99,
        onSurface = Neutral10,
        surfaceVariant = Neutral95,
    )

private val DarkColors =
    darkColorScheme(
        primary = Indigo80,
        onPrimary = Indigo20,
        primaryContainer = Indigo30,
        onPrimaryContainer = Indigo90,
        secondary = Amber80,
        onSecondary = Amber20,
        secondaryContainer = Amber30,
        onSecondaryContainer = Amber90,
        background = Neutral10,
        onBackground = Neutral90,
        surface = Neutral10,
        onSurface = Neutral90,
    )

@Composable
fun ArxiverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArxiverTypography,
        content = content,
    )
}
