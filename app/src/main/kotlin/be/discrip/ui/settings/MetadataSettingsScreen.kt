package be.discrip.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.model.MetadataMode
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.CheckRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.labelRes
import be.discrip.ui.theme.HigTheme

@Composable
fun MetadataSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val check by viewModel.tmdbCheck.collectAsStateWithLifecycle()
    val colors = HigTheme.colors

    HigScaffold(
        title = stringResource(R.string.metadata_settings_title),
        largeTitle = false,
        backLabel = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        val current = settings ?: return@HigScaffold
        val storedSecrets = secrets ?: return@HigScaffold

        GroupedSection(
            header = stringResource(R.string.metadata_default_header),
            footer = stringResource(R.string.metadata_default_footer),
        ) {
            MetadataMode.entries.forEach { mode ->
                row {
                    CheckRow(
                        title = stringResource(mode.labelRes()),
                        checked = current.metadataMode == mode,
                        onClick = { viewModel.update { it.copy(metadataMode = mode) } },
                    )
                }
            }
        }

        val (footer, footerColor) = when (val state = check) {
            CheckState.Passed -> stringResource(R.string.tmdb_test_passed) to colors.green
            is CheckState.Failed -> stringResource(
                when (state.reason) {
                    CheckFailure.REJECTED -> R.string.tmdb_error_key
                    CheckFailure.UNREACHABLE -> R.string.tmdb_error_unreachable
                    CheckFailure.MISSING_CREDENTIAL -> R.string.tmdb_error_missing_key
                    else -> R.string.tmdb_error_other
                },
            ) to colors.red
            else -> stringResource(R.string.tmdb_footer) to null
        }
        GroupedSection(header = stringResource(R.string.tmdb_header), footer = footer, footerColor = footerColor) {
            row {
                SettingTextField(
                    label = stringResource(R.string.tmdb_key),
                    initial = storedSecrets.tmdbApiKey,
                    onChange = { value -> viewModel.updateSecrets { it.copy(tmdbApiKey = value.trim()) } },
                    placeholder = stringResource(R.string.tmdb_key_placeholder),
                    secure = true,
                )
            }
            row {
                ButtonRow(
                    text = stringResource(R.string.tmdb_test),
                    onClick = viewModel::testTmdbKey,
                    enabled = storedSecrets.tmdbApiKey.isNotBlank(),
                    loading = check == CheckState.Running,
                )
            }
        }
    }
}
