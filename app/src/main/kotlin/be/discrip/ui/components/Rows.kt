package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.theme.HigMetrics
import be.discrip.ui.theme.HigTheme

/**
 * Base for every table row: 44 dp minimum height, 16 dp side padding and, when tappable, the grey
 * press highlight of a UITableViewCell instead of a ripple.
 */
@Composable
fun RowFrame(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (pressed) HigTheme.colors.cellHighlight else Color.Transparent)
            .then(clickModifier)
            .heightIn(min = HigMetrics.rowMinHeight)
            .padding(horizontal = HigMetrics.rowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Colored rounded square with a white glyph, as in iOS Settings. */
@Composable
fun SettingsIcon(icon: ImageVector, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(HigMetrics.settingsIconSize)
            .clip(RoundedCornerShape(HigMetrics.settingsIconCornerRadius))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, titleColor: Color, modifier: Modifier = Modifier) {
    val typography = HigTheme.typography
    Column(modifier.padding(vertical = if (subtitle != null) 8.dp else 11.dp)) {
        Text(title, style = typography.body, color = titleColor)
        if (subtitle != null) {
            Text(subtitle, style = typography.subheadline, color = HigTheme.colors.secondaryLabel)
        }
    }
}

/** Row that pushes another screen: title, optional value and a chevron. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = HigTheme.colors
    RowFrame(modifier, onClick = onClick, enabled = enabled) {
        icon?.invoke()
        RowTexts(title, subtitle, if (enabled) colors.label else colors.tertiaryLabel, Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = HigTheme.typography.body,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Icon(HigIcons.Chevron, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(width = 8.dp, height = 14.dp))
    }
}

/** Static label and value. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    valueColor: Color = HigTheme.colors.secondaryLabel,
) {
    RowFrame(modifier) {
        icon?.invoke()
        RowTexts(title, null, HigTheme.colors.label, Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = HigTheme.typography.body,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/** Row with an iOS switch; the whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    RowFrame(
        modifier,
        onClick = {
            haptics.toggle(!checked)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        role = Role.Switch,
    ) {
        icon?.invoke()
        RowTexts(title, subtitle, HigTheme.colors.label, Modifier.weight(1f))
        IosSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Selectable option with a trailing accent checkmark (iOS picker list). */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
) {
    val colors = HigTheme.colors
    val haptics = rememberHaptics()
    RowFrame(
        modifier,
        onClick = {
            if (!checked) haptics.selection()
            onClick()
        },
        enabled = enabled,
        role = Role.RadioButton,
    ) {
        RowTexts(title, subtitle, if (enabled) colors.label else colors.tertiaryLabel, Modifier.weight(1f))
        if (trailingText != null) {
            Text(trailingText, style = HigTheme.typography.subheadline, color = colors.secondaryLabel)
        }
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(HigIcons.Checkmark, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Action row in accent color (or red for destructive actions), optionally with a spinner. */
@Composable
fun ButtonRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = HigTheme.colors.accent,
    enabled: Boolean = true,
    loading: Boolean = false,
    centered: Boolean = false,
) {
    val colors = HigTheme.colors
    RowFrame(modifier, onClick = onClick, enabled = enabled && !loading) {
        Text(
            text = text,
            style = HigTheme.typography.body,
            color = if (enabled) color else colors.tertiaryLabel,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f).padding(vertical = 11.dp),
        )
        if (loading) ActivityIndicator(Modifier.size(20.dp))
    }
}

/** Status line for a configuration check: green check, orange warning or neutral. */
@Composable
fun StatusRow(
    title: String,
    status: String,
    ok: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = HigTheme.colors
    RowFrame(modifier, onClick = onClick) {
        icon?.invoke()
        RowTexts(title, status, colors.label, Modifier.weight(1f))
        Icon(
            imageVector = if (ok) HigIcons.Checkmark else HigIcons.ExclamationTriangle,
            contentDescription = null,
            tint = if (ok) colors.green else colors.orange,
            modifier = Modifier.size(20.dp),
        )
        if (onClick != null) {
            Icon(HigIcons.Chevron, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
    }
}
