package be.discrip.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.ui.components.TextFieldRow
import java.util.Locale

@Composable
internal fun rememberSettingsViewModel(container: AppContainer): SettingsViewModel =
    viewModel { SettingsViewModel(container) }

@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/**
 * A settings text field that owns its text while the user types and reports every change. The
 * stored value only seeds it, so asynchronous persistence never fights with the cursor.
 */
@Composable
internal fun SettingTextField(
    label: String,
    initial: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    secure: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    TextFieldRow(
        label = label,
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        placeholder = placeholder,
        keyboardType = keyboardType,
        capitalization = capitalization,
        secure = secure,
    )
}
