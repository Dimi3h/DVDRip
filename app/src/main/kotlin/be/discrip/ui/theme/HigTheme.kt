package be.discrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LocalHigColors = staticCompositionLocalOf { HigColors.Light }
private val LocalHigTypography = staticCompositionLocalOf { HigTypography.Default }

/** Access to the HIG tokens, mirroring `MaterialTheme.colorScheme` and friends. */
object HigTheme {
    val colors: HigColors
        @Composable @ReadOnlyComposable get() = LocalHigColors.current

    val typography: HigTypography
        @Composable @ReadOnlyComposable get() = LocalHigTypography.current
}

/** The 8 pt grid and the fixed metrics of UIKit's inset grouped lists and bars. */
object HigMetrics {
    val gridUnit = 8.dp
    val margin = 16.dp
    val rowMinHeight = 44.dp
    val rowHorizontalPadding = 16.dp
    val sectionCornerRadius = 10.dp
    val sectionSpacing = 32.dp
    val navigationBarHeight = 44.dp
    val largeTitleHeight = 52.dp
    val tabBarHeight = 49.dp
    val settingsIconSize = 29.dp
    val settingsIconCornerRadius = 7.dp
    val hairline = 0.5.dp
}

/**
 * The app theme: iOS colors and type, also mapped onto Material 3 so the few Material components in
 * use (bottom sheets, text selection) blend in.
 */
@Composable
fun HigTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) HigColors.Dark else HigColors.Light
    val typography = HigTypography.Default
    val selection = TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.3f))

    CompositionLocalProvider(
        LocalHigColors provides colors,
        LocalHigTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = typography.toMaterial(),
        ) {
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}

private fun HigColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        secondary = accent,
        onSecondary = Color.White,
        background = groupedBackground,
        onBackground = label,
        surface = secondaryGroupedBackground,
        onSurface = label,
        surfaceVariant = tertiaryGroupedBackground,
        onSurfaceVariant = secondaryLabel,
        surfaceContainerLow = groupedBackground,
        surfaceContainer = groupedBackground,
        surfaceContainerHigh = groupedBackground,
        outline = separator,
        outlineVariant = separator,
        error = red,
        onError = Color.White,
        scrim = Color.Black,
    )
}

private fun HigTypography.toMaterial() = Typography(
    displayLarge = largeTitle,
    displayMedium = largeTitle,
    displaySmall = title1,
    headlineLarge = title1,
    headlineMedium = title2,
    headlineSmall = title3,
    titleLarge = title3,
    titleMedium = headline,
    titleSmall = subheadline,
    bodyLarge = body,
    bodyMedium = callout,
    bodySmall = footnote,
    labelLarge = headline,
    labelMedium = footnote,
    labelSmall = caption1,
)
