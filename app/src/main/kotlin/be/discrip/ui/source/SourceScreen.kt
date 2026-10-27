package be.discrip.ui.source

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.format.Formats
import be.discrip.core.model.QualityPreset
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
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.ValueRow
import be.discrip.ui.components.rememberHaptics
import be.discrip.ui.rip.trackFormat
import be.discrip.ui.rip.trackTitle
import be.discrip.ui.settings.FolderAccess
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
    onRipStarted: () -> Unit,
) {
    val viewModel = viewModel { SourceViewModel(container, source) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var replace by remember { mutableStateOf<RipJob?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFolderAccess()
        onPauseOrDispose { }
    }

    val current = state
    val ready = current?.ready
    val start: () -> Unit = {
        scope.launch {
            when (val check = viewModel.prepareStart()) {
                StartCheck.NeedsMetadata -> onOpenMetadata(ready?.name)
                StartCheck.NeedsFolder -> onOpenStorageSettings()
                StartCheck.Busy -> haptics.failure()
                is StartCheck.Exists -> replace = check.job
                is StartCheck.Ready -> if (viewModel.start(check.job)) {
                    haptics.success()
                    onRipStarted()
                }
            }
        }
    }

    HigScaffold(
        title = ready?.name ?: stringResource(if (source is DvdSource.Folder) R.string.home_open_folder else R.string.home_open_iso),
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
                if (dvd.problem != SourceProblem.NOT_DVD_VIDEO && dvd.problem != SourceProblem.ENGINE_UNAVAILABLE) {
                    row { ButtonRow(stringResource(R.string.action_retry), onClick = viewModel::retry) }
                }
            }
            is SourceState.Ready -> ReadyContent(current, dvd, viewModel, onOpenMetadata, onOpenStorageSettings, start)
        }
    }

    replace?.let { job ->
        HigAlert(
            title = stringResource(R.string.source_exists_title),
            message = stringResource(R.string.source_exists_message, job.path.fileName),
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
    start: () -> Unit,
) {
    val colors = HigTheme.colors
    val chosen = state.title
    val main = dvd.mainTitle

    GroupedSection(header = stringResource(R.string.source_titles_header), footer = stringResource(R.string.source_titles_footer)) {
        for (title in dvd.titles) {
            row {
                CheckRow(
                    title = stringResource(R.string.source_title_name, title.number),
                    subtitle = titleDetails(title),
                    checked = title.number == chosen?.number,
                    onClick = { viewModel.select(title.number) },
                    trailingText = if (title.number == main?.number) stringResource(R.string.source_main_title) else null,
                )
            }
        }
    }

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
        val tracks = state.audio
        GroupedSection(
            header = stringResource(R.string.source_audio_header),
            footer = if (tracks.isEmpty()) stringResource(R.string.source_audio_none) else stringResource(R.string.source_audio_footer),
        ) {
            for (track in tracks) {
                row {
                    RowFrame {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(trackTitle(track), style = HigTheme.typography.body, color = colors.label)
                            Text(trackFormat(track, state.settings.convertLpcmToFlac), style = HigTheme.typography.subheadline, color = colors.secondaryLabel)
                        }
                        if (track.isDefault) {
                            Text(stringResource(R.string.source_default_track), style = HigTheme.typography.subheadline, color = colors.secondaryLabel)
                        }
                    }
                }
            }
        }
    }

    val path = state.path
    val folderFooter = when (state.folderAccess) {
        FolderAccess.GRANTED -> state.settings.local.displayName?.let { stringResource(R.string.source_output_footer, it) }
        FolderAccess.REVOKED -> stringResource(R.string.source_folder_revoked)
        FolderAccess.NONE -> stringResource(R.string.source_needs_folder)
    }
    GroupedSection(
        header = stringResource(R.string.source_output_header),
        footer = folderFooter,
        footerColor = if (state.folderAccess == FolderAccess.GRANTED) null else colors.orange,
    ) {
        if (path != null) {
            row { ValueRow(stringResource(R.string.source_output_folder), path.relativeFolder) }
            row { ValueRow(stringResource(R.string.source_output_name), path.fileName) }
        }
        if (state.folderAccess != FolderAccess.GRANTED) {
            row { NavigationRow(stringResource(R.string.action_open_settings), onClick = onOpenStorageSettings) }
        }
    }

    GroupedSection(
        footer = if (state.settings.defaultPreset.isLossy) stringResource(R.string.source_quality_lossy_footer) else null,
    ) {
        row { ValueRow(stringResource(R.string.source_quality), stringResource(QualityPreset.LOSSLESS.shortLabelRes())) }
    }

    FilledButton(
        text = stringResource(if (metadata == null) R.string.source_choose_metadata else R.string.source_start),
        onClick = start,
        enabled = chosen != null && !state.ripRunning && (metadata == null || state.folderAccess == FolderAccess.GRANTED),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp),
    )
    if (state.ripRunning) {
        Text(
            text = stringResource(R.string.home_rip_busy_footer),
            style = HigTheme.typography.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )
    }
}

@Composable
private fun titleDetails(title: DvdTitle): String = buildList {
    add(Formats.duration(title.durationMs / 1000))
    add(pluralStringResource(R.plurals.source_chapters, title.chapters, title.chapters))
    add(pluralStringResource(R.plurals.source_audio_tracks, title.audio.size, title.audio.size))
    if (title.angles > 1) add(pluralStringResource(R.plurals.source_angles, title.angles, title.angles))
}.joinToString(" · ")

private fun problemText(problem: SourceProblem): Int = when (problem) {
    SourceProblem.CANNOT_OPEN -> R.string.source_problem_cannot_open
    SourceProblem.NOT_DVD_VIDEO -> R.string.source_problem_not_dvd
    SourceProblem.UNREADABLE -> R.string.source_problem_unreadable
    SourceProblem.NO_TITLES -> R.string.source_problem_no_titles
    SourceProblem.BUSY -> R.string.source_problem_busy
    SourceProblem.ENGINE_UNAVAILABLE -> R.string.source_problem_engine
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
