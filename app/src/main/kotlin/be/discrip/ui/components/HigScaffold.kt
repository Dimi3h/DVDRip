package be.discrip.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.theme.HigMetrics
import be.discrip.ui.theme.HigTheme

/**
 * Screen frame in the style of a UINavigationController page: a translucent navigation bar with
 * back button, inline title and actions, over a scrolling grouped background. With [largeTitle]
 * the title starts large under the bar and moves into the bar once it scrolls away.
 */
@Composable
fun HigScaffold(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = true,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomPadding: Dp = 0.dp,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = statusBarTop + HigMetrics.navigationBarHeight
    val largeTitlePx = with(density) { HigMetrics.largeTitleHeight.toPx() }

    val inlineTitleVisible by remember(largeTitle) {
        derivedStateOf { !largeTitle || scrollState.value > largeTitlePx * 0.75f }
    }
    val contentUnderBar by remember(largeTitle) {
        derivedStateOf { scrollState.value > (if (largeTitle) largeTitlePx else 0f) }
    }
    val titleAlpha by animateFloatAsState(if (inlineTitleVisible) 1f else 0f, tween(160), label = "inlineTitle")
    val barAlpha by animateFloatAsState(if (contentUnderBar) 1f else 0f, tween(160), label = "barBackground")

    Box(modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(top = barHeight, bottom = bottomPadding + navigationBarBottom + 32.dp),
        ) {
            if (largeTitle) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HigMetrics.largeTitleHeight)
                        .padding(horizontal = HigMetrics.margin),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(
                        text = title,
                        style = typography.largeTitle,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .semantics { heading() },
                    )
                }
            }
            content()
        }

        val chrome = colors.chrome
        val separator = colors.separator
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .drawBehind {
                    drawRect(chrome.copy(alpha = chrome.alpha * barAlpha))
                    drawLine(
                        color = separator.copy(alpha = separator.alpha * barAlpha),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1f,
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(HigMetrics.navigationBarHeight),
            ) {
                if (onBack != null) {
                    BackButton(label = backLabel, onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
                }
                Text(
                    text = title,
                    style = typography.headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 96.dp)
                        .alpha(titleAlpha),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun BackButton(label: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HigTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (pressed) colors.accent.copy(alpha = 0.3f) else colors.accent
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HigIcons.BackChevron, contentDescription = null, tint = tint, modifier = Modifier.size(width = 12.dp, height = 21.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = HigTheme.typography.body, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
