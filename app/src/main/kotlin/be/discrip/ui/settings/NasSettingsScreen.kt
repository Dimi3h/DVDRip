package be.discrip.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.settings.normalizeFolderSetting
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.ToggleRow

@Composable
fun NasSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()

    HigScaffold(
        title = stringResource(R.string.nas_title),
        largeTitle = false,
        backLabel = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        val current = settings ?: return@HigScaffold
        val storedSecrets = secrets ?: return@HigScaffold

        GroupedSection(
            header = stringResource(R.string.nas_server_header),
            footer = stringResource(R.string.nas_server_footer),
        ) {
            row {
                SettingTextField(
                    label = stringResource(R.string.nas_host),
                    initial = current.nas.host,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(host = value.trim())) } },
                    placeholder = stringResource(R.string.nas_host_placeholder),
                    keyboardType = KeyboardType.Uri,
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.nas_share),
                    initial = current.nas.share,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(share = value.trim().trim('/', '\\'))) } },
                    placeholder = stringResource(R.string.nas_share_placeholder),
                    keyboardType = KeyboardType.Uri,
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.nas_username),
                    initial = current.nas.username,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(username = value.trim())) } },
                    keyboardType = KeyboardType.Email,
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.nas_password),
                    initial = storedSecrets.nasPassword,
                    onChange = { value -> viewModel.updateSecrets { it.copy(nasPassword = value) } },
                    secure = true,
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.nas_domain),
                    initial = current.nas.domain,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(domain = value.trim())) } },
                    placeholder = stringResource(R.string.placeholder_optional),
                )
            }
        }

        GroupedSection(
            header = stringResource(R.string.nas_folders_header),
            footer = stringResource(R.string.nas_folders_footer),
        ) {
            row {
                SettingTextField(
                    label = stringResource(R.string.folder_films),
                    initial = current.nas.filmsFolder,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(filmsFolder = normalizeFolderSetting(value))) } },
                    placeholder = stringResource(R.string.folder_share_root_placeholder),
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.folder_home_videos),
                    initial = current.nas.homeVideosFolder,
                    onChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(homeVideosFolder = normalizeFolderSetting(value))) } },
                    placeholder = stringResource(R.string.folder_share_root_placeholder),
                )
            }
        }

        GroupedSection(footer = stringResource(R.string.nas_direct_footer)) {
            row {
                ToggleRow(
                    title = stringResource(R.string.nas_direct),
                    checked = current.nas.writeDirectly,
                    onCheckedChange = { value -> viewModel.update { it.copy(nas = it.nas.copy(writeDirectly = value)) } },
                )
            }
        }
    }
}
