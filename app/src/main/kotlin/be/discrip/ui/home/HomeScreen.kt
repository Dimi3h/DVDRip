package be.discrip.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.drive.DriveMessage
import be.discrip.rip.RipState
import be.discrip.rip.usesDisc
import be.discrip.ui.components.AlertAction
import be.discrip.ui.components.AlertActionStyle
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigAlert
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.components.StatusRow
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.rip.ripStatusLine
import be.discrip.ui.settings.FolderAccess
import be.discrip.ui.theme.HigTheme

@Composable
fun HomeScreen(
    container: AppContainer,
    bottomPadding: Dp,
    onNewRip: () -> Unit,
    onOpenDiscMetadata: (volumeLabel: String?) -> Unit,
    onOpenDrive: (insertion: Long) -> Unit,
    onOpenTmdbSettings: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenNasSettings: () -> Unit,
    onOpenPlexSettings: () -> Unit,
    onOpenSource: (folder: Boolean, uri: String) -> Unit,
    onOpenRip: () -> Unit,
) {
    val viewModel = viewModel { HomeViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val driveStatus by container.drives.status.collectAsStateWithLifecycle()
    val ripState by container.rips.rip.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenSource(false, uri.toString())
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onOpenSource(true, uri.toString())
    }
    val colors = HigTheme.colors
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var driveMessage by remember { mutableStateOf<DriveMessage?>(null) }
    val driveActions = remember(container, onOpenDiscMetadata, onOpenDrive) {
        DriveActions(
            connect = container.drives::connect,
            eject = container.drives::eject,
            closeTray = container.drives::closeTray,
            retryDisc = container.drives::retryDisc,
            openMetadata = onOpenDiscMetadata,
            openDisc = onOpenDrive,
        )
    }

    LaunchedEffect(container) {
        container.drives.messages.collect { driveMessage = it }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HigScaffold(title = stringResource(R.string.app_name), bottomPadding = bottomPadding) {
        val rip = ripState
        val job = when (rip) {
            RipState.Idle -> null
            is RipState.Running -> rip.job
            is RipState.Succeeded -> rip.job
            is RipState.Failed -> rip.job
        }
        if (job != null) {
            GroupedSection(header = stringResource(R.string.home_rip_header)) {
                row(IconRowSeparatorInset) {
                    NavigationRow(
                        title = if (job.metadata.year != null) "${job.metadata.title} (${job.metadata.year})" else job.metadata.title,
                        subtitle = ripStatusLine(rip),
                        onClick = onOpenRip,
                        icon = { SettingsIcon(HigIcons.Film, if (rip is RipState.Failed) colors.orange else colors.accent) },
                    )
                }
            }
        }

        DriveSection(driveStatus, driveActions)

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
                        onClick = { confirmClear = true },
                        color = colors.red,
                    )
                }
            }
        }

        // The next DVD can be opened while the last rip's file still travels to the NAS.
        val ripping = ripState.usesDisc
        GroupedSection(
            header = stringResource(R.string.home_new_rip_header),
            footer = stringResource(if (ripping) R.string.home_rip_busy_footer else R.string.home_new_rip_footer),
        ) {
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.home_open_iso),
                    onClick = { pickImage.launch(ISO_MIME_TYPES) },
                    icon = { SettingsIcon(HigIcons.Disc, colors.accent) },
                    enabled = !ripping,
                )
            }
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.home_open_folder),
                    onClick = { pickFolder.launch(null) },
                    icon = { SettingsIcon(HigIcons.Folder, colors.accent) },
                    enabled = !ripping,
                )
            }
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.home_metadata_row),
                    onClick = onNewRip,
                    icon = { SettingsIcon(HigIcons.MagnifyingGlass, colors.indigo) },
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

    driveMessage?.let { message ->
        HigAlert(
            title = stringResource(
                when (message) {
                    DriveMessage.EJECT_FAILED -> R.string.drive_message_eject_failed
                    DriveMessage.CLOSE_TRAY_FAILED -> R.string.drive_message_close_tray_failed
                },
            ),
            message = null,
            actions = listOf(AlertAction(stringResource(R.string.action_ok), AlertActionStyle.CANCEL) { driveMessage = null }),
            onDismissRequest = { driveMessage = null },
        )
    }

    if (confirmClear) {
        HigAlert(
            title = stringResource(R.string.home_clear_title),
            message = stringResource(R.string.home_clear_message),
            actions = listOf(
                AlertAction(stringResource(R.string.action_cancel), AlertActionStyle.CANCEL) { confirmClear = false },
                AlertAction(stringResource(R.string.home_draft_clear), AlertActionStyle.DESTRUCTIVE) {
                    confirmClear = false
                    viewModel.clearDraft()
                },
            ),
            onDismissRequest = { confirmClear = false },
        )
    }
}

/**
 * What the image picker offers: Android's own type for .iso, the freedesktop one some providers
 * use, and files of unknown type. The picked file is checked for VIDEO_TS before use.
 */
private val ISO_MIME_TYPES = arrayOf("application/x-iso9660-image", "application/x-cd-image", "application/octet-stream")
