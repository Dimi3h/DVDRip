package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.theme.HigMetrics
import be.discrip.ui.theme.HigTheme

/**
 * A form row with a fixed-width label and an inline text field, like iOS Wi-Fi or account forms.
 * Secure fields hide their content and offer a reveal toggle.
 */
@Composable
fun TextFieldRow(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    secure: Boolean = false,
    enabled: Boolean = true,
    labelWidth: Dp = 116.dp,
) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var revealed by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HigMetrics.rowHorizontalPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(label, style = typography.body, color = colors.label, modifier = Modifier.widthIn(min = labelWidth).padding(end = 12.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = typography.body.copy(color = if (enabled) colors.label else colors.tertiaryLabel),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (secure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = if (secure && !revealed) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onAny = {
                if (onImeAction != null) onImeAction() else focusManager.clearFocus()
            }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = typography.body, color = colors.tertiaryLabel, maxLines = 1)
                    inner()
                }
            },
        )
        if (focused && enabled && value.isNotEmpty()) {
            ClearButton(onClick = { onValueChange("") })
        }
        if (secure) {
            Box(
                Modifier
                    .size(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { revealed = !revealed },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (revealed) HigIcons.EyeSlash else HigIcons.Eye,
                    contentDescription = null,
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** UISearchBar-style field: rounded grey capsule with a magnifying glass. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val focusManager = LocalFocusManager.current
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.tertiaryFill)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HigIcons.MagnifyingGlass, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = typography.body.copy(color = colors.label),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                onSearch()
            }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = typography.body, color = colors.secondaryLabel, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) ClearButton(onClick = { onValueChange("") })
    }
}
