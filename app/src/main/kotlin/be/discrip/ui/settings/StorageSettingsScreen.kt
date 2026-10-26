package be.discrip.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.model.StorageTarget
import be.discrip.core.settings.normalizeFolderSetting
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.CheckRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.descriptionRes
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.labelRes
import be.discrip.ui.theme.HigTheme

@Composable
fun StorageSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val access by viewModel.folderAccess.collectAsStateWithLifecycle()
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    // The grant can disappear while the app is in the background (card removed, access revoked).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshFolderAccess()
        onPauseOrDispose { }
    }

    HigScaffold(
        title = stringResource(R.string.storage_title),
        largeTitle = false,
        backLabel = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        val current = settings ?: return@HigScaffold

        GroupedSection(
            header = stringResource(R.string.storage_default_header),
            footer = stringResource(R.string.storage_default_footer),
        ) {
            StorageTarget.entries.forEach { target ->
                row {
                    CheckRow(
                        title = stringResource(target.labelRes()),
                        subtitle = stringResource(target.descriptionRes()),
                        checked = current.defaultStorage == target,
                        onClick = { viewModel.update { it.copy(defaultStorage = target) } },
                    )
                }
            }
        }

        val revoked = access == FolderAccess.REVOKED
        GroupedSection(
            header = stringResource(R.string.local_header),
            footer = stringResource(if (revoked) R.string.local_revoked else R.string.local_footer),
            footerColor = if (revoked) colors.red else null,
        ) {
            row(IconRowSeparatorInset) {
                RowFrame {
                    SettingsIcon(HigIcons.Folder, if (revoked) colors.red else colors.green)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            text = current.local.displayName ?: stringResource(R.string.local_none),
                            style = typography.body,
                            color = if (current.local.isConfigured) colors.label else colors.secondaryLabel,
                        )
                        if (current.local.isConfigured) {
                            Text(
                                text = stringResource(if (revoked) R.string.local_status_revoked else R.string.local_status_ok),
                                style = typography.subheadline,
                                color = if (revoked) colors.red else colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
            row {
                ButtonRow(
                    text = stringResource(if (current.local.isConfigured) R.string.local_change else R.string.local_choose),
                    onClick = { pickFolder.launch(current.local.treeUri?.toUri()) },
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.folder_films),
                    initial = current.local.filmsFolder,
                    onChange = { value ->
                        viewModel.update { it.copy(local = it.local.copy(filmsFolder = normalizeFolderSetting(value))) }
                    },
                    placeholder = stringResource(R.string.folder_root_placeholder),
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.folder_home_videos),
                    initial = current.local.homeVideosFolder,
                    onChange = { value ->
                        viewModel.update { it.copy(local = it.local.copy(homeVideosFolder = normalizeFolderSetting(value))) }
                    },
                    placeholder = stringResource(R.string.folder_root_placeholder),
                )
            }
        }
    }
}
