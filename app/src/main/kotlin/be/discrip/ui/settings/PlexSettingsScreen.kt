package be.discrip.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.settings.PlexSectionRef
import be.discrip.ui.components.ActivityIndicator
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.CheckRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.HigSheet
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.theme.HigTheme

private enum class SectionTarget { FILMS, HOME_VIDEOS }

@Composable
fun PlexSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val sections by viewModel.plexSections.collectAsStateWithLifecycle()
    val colors = HigTheme.colors
    var picking by rememberSaveable { mutableStateOf<SectionTarget?>(null) }

    HigScaffold(
        title = stringResource(R.string.plex_title),
        largeTitle = false,
        backLabel = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        val current = settings ?: return@HigScaffold
        val storedSecrets = secrets ?: return@HigScaffold

        GroupedSection(
            header = stringResource(R.string.plex_server_header),
            footer = stringResource(R.string.plex_server_footer),
        ) {
            row {
                SettingTextField(
                    label = stringResource(R.string.plex_url),
                    initial = current.plex.serverUrl,
                    onChange = { value -> viewModel.update { it.copy(plex = it.plex.copy(serverUrl = value.trim())) } },
                    placeholder = stringResource(R.string.plex_url_placeholder),
                    keyboardType = KeyboardType.Uri,
                )
            }
            row {
                SettingTextField(
                    label = stringResource(R.string.plex_token),
                    initial = storedSecrets.plexToken,
                    onChange = { value -> viewModel.updateSecrets { it.copy(plexToken = value.trim()) } },
                    secure = true,
                )
            }
        }

        val (statusText, statusColor) = when (val state = sections) {
            is SectionsState.Loaded -> pluralStringResource(R.plurals.plex_sections_found, state.sections.size, state.sections.size) to colors.green
            is SectionsState.Failed -> stringResource(plexFailureText(state.reason)) to colors.red
            else -> stringResource(R.string.plex_libraries_footer) to null
        }
        GroupedSection(
            header = stringResource(R.string.plex_libraries_header),
            footer = statusText,
            footerColor = statusColor,
        ) {
            row {
                ButtonRow(
                    text = stringResource(R.string.plex_load_sections),
                    onClick = viewModel::loadPlexSections,
                    enabled = current.plex.serverUrl.isNotBlank() && storedSecrets.plexToken.isNotBlank(),
                    loading = sections == SectionsState.Loading,
                )
            }
            row {
                NavigationRow(
                    title = stringResource(R.string.folder_films),
                    value = current.plex.filmsSection?.title ?: stringResource(R.string.plex_section_choose),
                    onClick = {
                        if (sections !is SectionsState.Loaded) viewModel.loadPlexSections()
                        picking = SectionTarget.FILMS
                    },
                )
            }
            row {
                NavigationRow(
                    title = stringResource(R.string.folder_home_videos),
                    value = current.plex.homeVideosSection?.title ?: stringResource(R.string.plex_section_choose),
                    onClick = {
                        if (sections !is SectionsState.Loaded) viewModel.loadPlexSections()
                        picking = SectionTarget.HOME_VIDEOS
                    },
                )
            }
        }
    }

    val target = picking
    val current = settings
    if (target != null && current != null) {
        val selectedKey = when (target) {
            SectionTarget.FILMS -> current.plex.filmsSection?.key
            SectionTarget.HOME_VIDEOS -> current.plex.homeVideosSection?.key
        }
        HigSheet(
            title = stringResource(if (target == SectionTarget.FILMS) R.string.folder_films else R.string.folder_home_videos),
            onDismissRequest = { picking = null },
        ) {
            when (val state = sections) {
                is SectionsState.Loaded -> if (state.sections.isEmpty()) {
                    SheetMessage(stringResource(R.string.plex_no_movie_sections))
                } else {
                    GroupedSection(footer = stringResource(R.string.plex_sections_sheet_footer)) {
                        state.sections.forEach { section ->
                            row {
                                CheckRow(
                                    title = section.title,
                                    subtitle = section.locations.joinToString("\n").ifEmpty { null },
                                    checked = section.key == selectedKey,
                                    onClick = {
                                        val ref = PlexSectionRef(section.key, section.title)
                                        viewModel.update {
                                            it.copy(
                                                plex = when (target) {
                                                    SectionTarget.FILMS -> it.plex.copy(filmsSection = ref)
                                                    SectionTarget.HOME_VIDEOS -> it.plex.copy(homeVideosSection = ref)
                                                },
                                            )
                                        }
                                        picking = null
                                    },
                                )
                            }
                        }
                    }
                }
                is SectionsState.Failed -> SheetMessage(stringResource(plexFailureText(state.reason)))
                else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    ActivityIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String) {
    Text(
        text = text,
        style = HigTheme.typography.body,
        color = HigTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
    )
}

private fun plexFailureText(reason: CheckFailure): Int = when (reason) {
    CheckFailure.UNREACHABLE -> R.string.plex_error_unreachable
    CheckFailure.REJECTED -> R.string.plex_error_token
    CheckFailure.INVALID_URL -> R.string.plex_error_url
    CheckFailure.MISSING_CREDENTIAL -> R.string.plex_error_missing_token
    CheckFailure.OTHER -> R.string.plex_error_other
}
