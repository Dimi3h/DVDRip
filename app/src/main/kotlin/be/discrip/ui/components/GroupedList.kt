package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.discrip.ui.theme.HigMetrics
import be.discrip.ui.theme.HigTheme

/** Collects the rows of a [GroupedSection]; each row owns the separator below it. */
class SectionScope internal constructor() {
    internal val rows = mutableListOf<SectionRow>()

    /**
     * Adds a row. [separatorInset] is where the separator under this row starts: the text column,
     * which moves right when the row has an icon.
     */
    fun row(separatorInset: Dp = HigMetrics.rowHorizontalPadding, content: @Composable () -> Unit) {
        rows += SectionRow(separatorInset, content)
    }
}

internal class SectionRow(val separatorInset: Dp, val content: @Composable () -> Unit)

/** Separator inset for rows that start with a [SettingsIcon]. */
val IconRowSeparatorInset: Dp = HigMetrics.rowHorizontalPadding + HigMetrics.settingsIconSize + 14.dp

/**
 * One section of an inset grouped list: optional uppercase header, rounded card with hairline
 * separators between rows, optional footer.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    footerColor: Color? = null,
    content: SectionScope.() -> Unit,
) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val scope = SectionScope().apply(content)

    Column(modifier.fillMaxWidth().padding(top = if (header != null) 24.dp else HigMetrics.sectionSpacing)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 6.dp),
            )
        }
        if (scope.rows.isNotEmpty()) {
            Column(
                Modifier
                    .padding(horizontal = HigMetrics.margin)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(HigMetrics.sectionCornerRadius))
                    .background(colors.secondaryGroupedBackground),
            ) {
                scope.rows.forEachIndexed { index, row ->
                    row.content()
                    if (index < scope.rows.lastIndex) Hairline(Modifier.padding(start = row.separatorInset))
                }
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = typography.footnote,
                color = footerColor ?: colors.secondaryLabel,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            )
        }
    }
}

/** A one-physical-pixel separator, like UIKit's. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = HigTheme.colors.separator) {
    val onePixel = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(onePixel).background(color))
}
