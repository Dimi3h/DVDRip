package be.discrip.ui.source

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.MainFeature
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.format.Formats
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget
import be.discrip.rip.DvdSource
import be.discrip.rip.RipJob
import be.discrip.rip.SourceProblem
import be.discrip.rip.SourceState
import be.discrip.ui.components.ActivityIndicator
import be.discrip.ui.components.AlertAction
import be.discrip.ui.components.AlertActionStyle
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.CheckRow
import be.discrip.ui.components.FilledButton
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigAlert
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.HigSheet
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.ValueRow
import be.discrip.ui.components.rememberHaptics
import be.discrip.ui.descriptionRes
import be.discrip.ui.labelRes
import be.discrip.ui.rip.audioFormat
import be.discrip.ui.rip.audioTitle
import be.discrip.ui.rip.subtitleKind
import be.discrip.ui.rip.subtitleTitle
import be.discrip.ui.settings.FolderAccess
import be.discrip.ui.settings.currentLocale
import be.discrip.ui.settings.nasProblemText
import be.discrip.ui.shortLabelRes
import be.discrip.ui.theme.HigTheme
import kotlinx.coroutines.launch

/** A picked ISO image or VIDEO_TS folder: its titles, what goes into the file, and the start button. */
@Composable
fun SourceScreen(
    container: AppContainer,
    source: DvdSource,
    backLabel: String,
    onBack: () -> Unit,
    onOpenMetadata: (volumeLabel: String?) -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenNasSettings: () -> Unit,
    onRipStarted: () -> Unit,
) {
    val viewModel = viewModel { SourceViewModel(container, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var replace by remember { mutableStateOf<RipJob?>(null) }
    var nasTrouble by remember { mutableStateOf<StartCheck?>(null) }
    var choosingDefault by remember { mutableStateOf(false) }
    var choosingStorage by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFolder()
        onPauseOrDispose { }
    }

    val current = state
    val ready = current?.ready
    val context = LocalContext.current
    val startChecked: () -> Unit = {
        scope.launch {
            when (val check = viewModel.prepareStart()) {
                StartCheck.NeedsMetadata -> onOpenMetadata(ready?.name)
                StartCheck.NeedsFolder -> onOpenStorageSettings()
                StartCheck.NeedsNas -> onOpenNasSettings()
                StartCheck.NotEnoughSpace, StartCheck.Busy -> haptics.failure()
                is StartCheck.NasUnavailable, is StartCheck.NasFull -> {
                    haptics.failure()
                    nasTrouble = check
                }
                is StartCheck.Exists -> replace = check.job
                is StartCheck.Ready -> if (viewModel.start(check.job)) {
                    haptics.success()
                    onRipStarted()
                }
            }
        }
    }
    // The progress shows in a notification while the rip runs; Android 13 and later ask first.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startChecked() }
    val start: () -> Unit = {
        val ask = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && current?.metadata != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else startChecked()
    }

    HigScaffold(
        title = ready?.name ?: stringResource(
            when (source) {
                is DvdSource.Folder -> R.string.home_open_folder
                is DvdSource.Image -> R.string.home_open_iso
                is DvdSource.Drive -> R.string.source_title_drive
            },
        ),
        largeTitle = false,
        backLabel = backLabel,
        onBack = onBack,
    ) {
        when (val dvd = current?.source) {
            null, is SourceState.Opening, SourceState.None -> GroupedSection {
                row { BusyRow(stringResource(R.string.source_opening)) }
            }
            is SourceState.Failed -> GroupedSection(footer = dvd.detail) {
                row { TextRow(stringResource(problemText(dvd.problem))) }
                // Another try cannot change these; a disc gone from the drive is opened anew from Home.
                if (dvd.problem !in FINAL_PROBLEMS) {
                    row { ButtonRow(stringResource(R.string.action_retry), onClick = viewModel::retry) }
                }
            }
            is SourceState.Ready -> ReadyContent(
                state = current,
                dvd = dvd,
                viewModel = viewModel,
                onOpenMetadata = onOpenMetadata,
                onOpenStorageSettings = onOpenStorageSettings,
                onOpenNasSettings = onOpenNasSettings,
                onChooseDefault = { choosingDefault = true },
                onChooseStorage = { choosingStorage = true },
                start = start,
            )
        }
    }

    if (choosingDefault && current != null) {
        DefaultAudioSheet(current, viewModel, onClose = { choosingDefault = false })
    }
    if (choosingStorage && current != null) {
        StorageSheet(current, viewModel, onClose = { choosingStorage = false })
    }

    nasTrouble?.let { trouble ->
        val locale = currentLocale()
        HigAlert(
            title = stringResource(R.string.source_nas_alert_title),
            message = when (trouble) {
                is StartCheck.NasUnavailable -> stringResource(nasProblemText(trouble.problem))
                is StartCheck.NasFull -> stringResource(
                    R.string.source_nas_full_message,
                    Formats.bytes(trouble.neededBytes, locale),
                    Formats.bytes(trouble.freeBytes, locale),
                )
                else -> ""
            },
            actions = listOf(
                AlertAction(stringResource(R.string.action_ok), AlertActionStyle.CANCEL) { nasTrouble = null },
                AlertAction(stringResource(R.string.source_nas_open_settings)) {
                    nasTrouble = null
                    onOpenNasSettings()
                },
            ),
            onDismissRequest = { nasTrouble = null },
        )
    }

    replace?.let { job ->
        HigAlert(
            title = stringResource(R.string.source_exists_title),
            message = stringResource(R.string.source_exists_message, job.destination.path.fileName),
            actions = listOf(
                AlertAction(stringResource(R.string.action_cancel), AlertActionStyle.CANCEL) { replace = null },
                AlertAction(stringResource(R.string.source_replace), AlertActionStyle.DESTRUCTIVE) {
                    replace = null
                    if (viewModel.start(job)) {
                        haptics.success()
                        onRipStarted()
                    }
                },
            ),
            onDismissRequest = { replace = null },
        )
    }
}

@Composable
private fun ReadyContent(
    state: SourceUiState,
    dvd: SourceState.Ready,
    viewModel: SourceViewModel,
    onOpenMetadata: (String?) -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenNasSettings: () -> Unit,
    onChooseDefault: () -> Unit,
    onChooseStorage: () -> Unit,
    start: () -> Unit,
) {
    val colors = HigTheme.colors
    val chosen = state.title

    TitlesSection(state, dvd, viewModel)

    val metadata = state.metadata
    GroupedSection(header = stringResource(R.string.source_metadata_header)) {
        row {
            NavigationRow(
                title = metadata?.let { if (it.year != null) "${it.title} (${it.year})" else it.title }
                    ?: stringResource(R.string.source_metadata_choose),
                onClick = { onOpenMetadata(dvd.name) },
            )
        }
    }

    if (chosen != null) {
        AudioSection(state, chosen, viewModel, onChooseDefault)
        SubtitlesSection(state, chosen, viewModel)
    }

    OutputSection(state, onChooseStorage, onOpenStorageSettings, onOpenNasSettings)

    GroupedSection(
        footer = if (state.settings.defaultPreset.isLossy) stringResource(R.string.source_quality_lossy_footer) else null,
    ) {
        row { ValueRow(stringResource(R.string.source_quality), stringResource(QualityPreset.LOSSLESS.shortLabelRes())) }
    }

    FilledButton(
        text = stringResource(if (metadata == null) R.string.source_choose_metadata else R.string.source_start),
        onClick = start,
        enabled = chosen != null && !state.ripRunning &&
            (metadata == null || (!state.needsFolder && !state.needsNas && state.enoughSpace)),
        loading = state.starting,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp),
    )
    if (state.ripRunning) {
        Text(
            text = stringResource(R.string.source_rip_busy_footer),
            style = HigTheme.typography.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )
    }
}

@Composable
private fun TitlesSection(state: SourceUiState, dvd: SourceState.Ready, viewModel: SourceViewModel) {
    val chosen = state.title
    val main = dvd.mainTitle
    GroupedSection(header = stringResource(R.string.source_titles_header), footer = stringResource(R.string.source_titles_footer)) {
        for (title in state.listedTitles) {
            row {
                CheckRow(
                    title = stringResource(R.string.source_title_name, title.number),
                    subtitle = titleDetails(title),
                    checked = title.number == chosen?.number,
                    onClick = { viewModel.selectTitle(title.number) },
                    trailingText = if (title.number == main?.number) stringResource(R.string.source_main_title) else null,
                )
            }
        }
        if (state.hiddenTitleCount > 0) {
            row {
                ButtonRow(
                    text = pluralStringResource(R.plurals.source_show_all_titles, dvd.titles.size, dvd.titles.size),
                    onClick = { viewModel.setShowAllTitles(true) },
                )
            }
        } else if (state.showAllTitles) {
            row { ButtonRow(stringResource(R.string.source_show_fewer_titles), onClick = { viewModel.setShowAllTitles(false) }) }
        }
    }
}

@Composable
private fun AudioSection(state: SourceUiState, title: DvdTitle, viewModel: SourceViewModel, onChooseDefault: () -> Unit) {
    val selected = state.audio
    val streams = RipPlanning.orderedAudio(title)
    GroupedSection(
        header = stringResource(R.string.source_audio_header),
        footer = when {
            streams.isEmpty() -> stringResource(R.string.source_audio_none)
            selected.isEmpty() -> stringResource(R.string.source_audio_none_chosen)
            else -> stringResource(R.string.source_audio_footer)
        },
    ) {
        for (stream in streams) {
            val plan = selected.firstOrNull { it.streamId == stream.streamId }
            row {
                CheckRow(
                    title = audioTitle(stream),
                    subtitle = audioFormat(stream, state.settings.convertLpcmToFlac),
                    checked = plan != null,
                    onClick = { viewModel.toggleAudio(stream.streamId) },
                    trailingText = if (plan?.isDefault == true) stringResource(R.string.source_default_track) else null,
                )
            }
        }
        if (selected.size > 1) {
            val default = title.audio.firstOrNull { stream -> selected.any { it.isDefault && it.streamId == stream.streamId } }
            row {
                NavigationRow(
                    title = stringResource(R.string.source_default_audio),
                    value = default?.let { audioTitle(it) },
                    onClick = onChooseDefault,
                )
            }
        }
    }
}

@Composable
private fun SubtitlesSection(state: SourceUiState, title: DvdTitle, viewModel: SourceViewModel) {
    val streams = RipPlanning.orderedSubtitles(title)
    if (streams.isEmpty()) return
    val selected = state.subtitles.map { it.streamId }.toSet()
    GroupedSection(header = stringResource(R.string.source_subtitles_header), footer = stringResource(R.string.source_subtitles_footer)) {
        for (stream in streams) {
            row {
                CheckRow(
                    title = subtitleTitle(stream),
                    subtitle = subtitleKind(stream),
                    checked = stream.streamId in selected,
                    onClick = { viewModel.toggleSubtitle(stream.streamId) },
                )
            }
        }
    }
}

@Composable
private fun OutputSection(
    state: SourceUiState,
    onChooseStorage: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenNasSettings: () -> Unit,
) {
    val colors = HigTheme.colors
    val locale = currentLocale()
    val destination = state.destination
    val estimated = state.estimatedBytes
    val folderName = state.settings.local.displayName.orEmpty()
    val footer = when {
        state.needsFolder && state.folderAccess == FolderAccess.REVOKED -> stringResource(R.string.source_folder_revoked)
        state.needsFolder -> stringResource(R.string.source_needs_folder)
        state.needsNas -> stringResource(R.string.source_needs_nas)
        !state.enoughSpace -> stringResource(
            if (state.storage.usesLocalFolder) R.string.source_not_enough_space else R.string.source_not_enough_app_space,
            Formats.bytes(state.neededBytes ?: 0, locale),
            Formats.bytes(state.freeBytes ?: 0, locale),
        )
        else -> listOfNotNull(
            when (state.storage) {
                StorageTarget.LOCAL -> stringResource(R.string.source_output_footer, folderName)
                StorageTarget.BOTH -> stringResource(R.string.source_output_footer_both, folderName)
                StorageTarget.NAS -> stringResource(
                    if (state.settings.nas.writeDirectly) R.string.source_output_footer_nas_direct else R.string.source_output_footer_nas,
                )
            },
            if (destination?.plex != null) stringResource(R.string.source_output_footer_plex) else null,
        ).joinToString(" ")
    }
    val problem = state.needsFolder || state.needsNas || !state.enoughSpace
    GroupedSection(header = stringResource(R.string.source_output_header), footer = footer, footerColor = if (problem) colors.orange else null) {
        row {
            NavigationRow(
                title = stringResource(R.string.source_storage),
                value = stringResource(state.storage.labelRes()),
                onClick = onChooseStorage,
            )
        }
        destination?.local?.let { local -> row { ValueRow(stringResource(R.string.source_output_folder), local.path.relativeFolder) } }
        destination?.nas?.let { nas -> row { ValueRow(stringResource(R.string.source_output_nas), "${nas.shareName}/${nas.path.relativeFolder}") } }
        destination?.let { row { ValueRow(stringResource(R.string.source_output_name), it.path.fileName) } }
        if (estimated != null) {
            row { ValueRow(stringResource(R.string.source_output_size), "≈ " + Formats.bytes(estimated, locale)) }
        }
        state.freeBytes?.let { free ->
            row {
                ValueRow(
                    stringResource(R.string.source_output_free),
                    Formats.bytes(free, locale),
                    valueColor = if (state.enoughSpace) colors.secondaryLabel else colors.orange,
                )
            }
        }
        if (state.needsFolder) {
            row { NavigationRow(stringResource(R.string.action_open_settings), onClick = onOpenStorageSettings) }
        } else if (state.needsNas) {
            row { NavigationRow(stringResource(R.string.source_nas_open_settings), onClick = onOpenNasSettings) }
        }
    }
}

@Composable
private fun StorageSheet(state: SourceUiState, viewModel: SourceViewModel, onClose: () -> Unit) {
    HigSheet(
        title = stringResource(R.string.source_storage),
        onDismissRequest = onClose,
        doneLabel = stringResource(R.string.action_done),
        onDone = onClose,
    ) {
        GroupedSection(footer = stringResource(R.string.source_storage_footer)) {
            for (target in StorageTarget.entries) {
                row {
                    CheckRow(
                        title = stringResource(target.labelRes()),
                        subtitle = stringResource(target.descriptionRes()),
                        checked = state.storage == target,
                        onClick = {
                            viewModel.setStorage(target)
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultAudioSheet(state: SourceUiState, viewModel: SourceViewModel, onClose: () -> Unit) {
    HigSheet(
        title = stringResource(R.string.source_default_audio),
        onDismissRequest = onClose,
        doneLabel = stringResource(R.string.action_done),
        onDone = onClose,
    ) {
        val title = state.title
        GroupedSection(footer = stringResource(R.string.source_default_audio_footer)) {
            for (track in state.audio) {
                val stream = title?.audio?.firstOrNull { it.streamId == track.streamId } ?: continue
                row {
                    CheckRow(
                        title = audioTitle(stream),
                        subtitle = audioFormat(stream, state.settings.convertLpcmToFlac),
                        checked = track.isDefault,
                        onClick = { viewModel.setDefaultAudio(track.streamId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleDetails(title: DvdTitle): String = buildList {
    add(Formats.duration(title.durationMs / 1000))
    add(pluralStringResource(R.plurals.source_chapters, title.chapters, title.chapters))
    add(pluralStringResource(R.plurals.source_audio_tracks, title.audio.size, title.audio.size))
    if (title.angles > 1) add(pluralStringResource(R.plurals.source_angles, title.angles, title.angles))
    if (MainFeature.assess(title).looksScrambled) add(stringResource(R.string.source_title_scrambled))
}.joinToString(" · ")

private fun problemText(problem: SourceProblem): Int = when (problem) {
    SourceProblem.CANNOT_OPEN -> R.string.source_problem_cannot_open
    SourceProblem.NOT_DVD_VIDEO -> R.string.source_problem_not_dvd
    SourceProblem.UNREADABLE -> R.string.source_problem_unreadable
    SourceProblem.NO_TITLES -> R.string.source_problem_no_titles
    SourceProblem.BUSY -> R.string.source_problem_busy
    SourceProblem.ENGINE_UNAVAILABLE -> R.string.source_problem_engine
    SourceProblem.DISC_GONE -> R.string.source_problem_disc_gone
    SourceProblem.REGION_MISMATCH -> R.string.source_problem_region
    SourceProblem.CSS_FAILED -> R.string.source_problem_css
}

@Composable
private fun BusyRow(text: String) {
    RowFrame {
        Text(text, style = HigTheme.typography.body, color = HigTheme.colors.secondaryLabel, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
        ActivityIndicator(Modifier.size(20.dp))
    }
}

@Composable
private fun TextRow(text: String) {
    RowFrame {
        Text(text, style = HigTheme.typography.body, color = HigTheme.colors.label, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
    }
}

private val FINAL_PROBLEMS = setOf(
    SourceProblem.NOT_DVD_VIDEO,
    SourceProblem.ENGINE_UNAVAILABLE,
    SourceProblem.DISC_GONE,
    SourceProblem.REGION_MISMATCH,
)
