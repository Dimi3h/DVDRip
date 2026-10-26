package be.discrip.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.components.StatusRow
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.settings.FolderAccess
import be.discrip.ui.theme.HigTheme

@Composable
fun HomeScreen(
    container: AppContainer,
    bottomPadding: Dp,
    onNewRip: () -> Unit,
    onOpenTmdbSettings: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenNasSettings: () -> Unit,
    onOpenPlexSettings: () -> Unit,
) {
    val viewModel = viewModel { HomeViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = HigTheme.colors

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HigScaffold(title = stringResource(R.string.app_name), bottomPadding = bottomPadding) {
        val home = state ?: return@HigScaffold

        val draft = home.draft
        if (draft != null) {
            GroupedSection(header = stringResource(R.string.home_draft_header)) {
                row {
                    NavigationRow(
                        title = if (draft.year != null) "${draft.title} (${draft.year})" else draft.title,
                        subtitle = home.draftPath,
                        onClick = onNewRip,
                    )
                }
                row {
                    ButtonRow(
                        text = stringResource(R.string.home_draft_clear),
                        onClick = viewModel::clearDraft,
                        color = colors.red,
                    )
                }
            }
        }

        GroupedSection(
            header = stringResource(R.string.home_new_rip_header),
            footer = stringResource(R.string.home_new_rip_footer),
        ) {
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.home_metadata_row),
                    onClick = onNewRip,
                    icon = { SettingsIcon(HigIcons.Disc, colors.accent) },
                )
            }
        }

        GroupedSection(header = stringResource(R.string.home_setup_header)) {
            row(IconRowSeparatorInset) {
                StatusRow(
                    title = stringResource(R.string.home_setup_tmdb),
                    status = stringResource(if (home.tmdbReady) R.string.status_tmdb_ok else R.string.status_tmdb_missing),
                    ok = home.tmdbReady,
                    onClick = onOpenTmdbSettings,
                    icon = { SettingsIcon(HigIcons.MagnifyingGlass, colors.orange) },
                )
            }
            row(IconRowSeparatorInset) {
                val local = home.settings.local
                StatusRow(
                    title = stringResource(R.string.home_setup_local),
                    status = when (home.folderAccess) {
                        FolderAccess.GRANTED -> local.displayName.orEmpty()
                        FolderAccess.REVOKED -> stringResource(R.string.status_local_revoked)
                        FolderAccess.NONE -> stringResource(R.string.status_local_missing)
                    },
                    ok = home.folderAccess == FolderAccess.GRANTED,
                    onClick = onOpenStorageSettings,
                    icon = { SettingsIcon(HigIcons.Folder, colors.green) },
                )
            }
            row(IconRowSeparatorInset) {
                val nas = home.settings.nas
                StatusRow(
                    title = stringResource(R.string.home_setup_nas),
                    status = if (home.nasReady) "smb://${nas.host}/${nas.share}" else stringResource(R.string.not_set),
                    ok = home.nasReady,
                    onClick = onOpenNasSettings,
                    icon = { SettingsIcon(HigIcons.Server, colors.indigo) },
                )
            }
            row(IconRowSeparatorInset) {
                StatusRow(
                    title = stringResource(R.string.home_setup_plex),
                    status = when {
                        !home.plexReady -> stringResource(R.string.not_set)
                        !home.plexSectionsChosen -> stringResource(R.string.status_plex_choose_sections)
                        else -> home.settings.plex.serverUrl
                    },
                    ok = home.plexReady && home.plexSectionsChosen,
                    onClick = onOpenPlexSettings,
                    icon = { SettingsIcon(HigIcons.Play, colors.orange) },
                )
            }
        }
    }
}
