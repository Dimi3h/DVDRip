package be.discrip.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The HIG text styles at the default ("Large") content size, on the system sans-serif. Sizes are
 * in sp so they follow the user's font scale, as Dynamic Type does on iOS.
 */
@Immutable
data class HigTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
) {
    companion object {
        private fun style(size: Int, lineHeight: Int, weight: FontWeight, tracking: TextUnit) = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            letterSpacing = tracking,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )

        val Default = HigTypography(
            largeTitle = style(34, 41, FontWeight.Bold, 0.011.em),
            title1 = style(28, 34, FontWeight.Normal, 0.013.em),
            title2 = style(22, 28, FontWeight.Normal, 0.016.em),
            title3 = style(20, 25, FontWeight.Normal, 0.019.em),
            headline = style(17, 22, FontWeight.SemiBold, (-0.024).em),
            body = style(17, 22, FontWeight.Normal, (-0.024).em),
            callout = style(16, 21, FontWeight.Normal, (-0.020).em),
            subheadline = style(15, 20, FontWeight.Normal, (-0.016).em),
            footnote = style(13, 18, FontWeight.Normal, (-0.006).em),
            caption1 = style(12, 16, FontWeight.Normal, 0.em),
            caption2 = style(11, 13, FontWeight.Normal, 0.006.em),
        )
    }
}
