package be.discrip.ui.rip

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.dvd.Iso639
import be.discrip.core.format.Formats
import be.discrip.rip.RipJob
import be.discrip.rip.RipState
import be.discrip.ui.components.AlertAction
import be.discrip.ui.components.AlertActionStyle
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.FilledButton
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigAlert
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.ProgressBar
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.ValueRow
import be.discrip.ui.settings.currentLocale
import be.discrip.ui.theme.HigTheme

/** The running rip with its progress, then its result. */
@Composable
fun RipScreen(container: AppContainer, backLabel: String, onBack: () -> Unit, onFinished: () -> Unit) {
    val state by container.rips.rip.collectAsStateWithLifecycle()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    // Nothing to show, for example when the app was restarted: back to where the user came from.
    LaunchedEffect(state) {
        if (state == RipState.Idle) onBack()
    }

    val running = state is RipState.Running
    // No foreground service yet: keep the screen, and with it the CPU, awake while ripping.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    HigScaffold(title = stringResource(R.string.rip_title), largeTitle = false, backLabel = backLabel, onBack = onBack) {
        when (val rip = state) {
            RipState.Idle -> Unit
            is RipState.Running -> {
                JobHeader(rip.job)
                RunningContent(rip)
                GroupedSection(footer = stringResource(R.string.rip_keep_open_footer)) {
                    row {
                        ButtonRow(
                            text = stringResource(if (rip.cancelling) R.string.rip_cancelling else R.string.rip_cancel),
                            onClick = { confirmCancel = true },
                            color = HigTheme.colors.red,
                            enabled = !rip.cancelling,
                            loading = rip.cancelling,
                        )
                    }
                }
            }
            is RipState.Succeeded -> {
                JobHeader(rip.job)
                SucceededContent(rip)
                DoneButton(container, onFinished)
            }
            is RipState.Failed -> {
                JobHeader(rip.job)
                GroupedSection(
                    header = stringResource(R.string.rip_failed_header),
                    footer = rip.message.takeIf { it.isNotBlank() }?.let { stringResource(R.string.rip_failure_detail, it) },
                ) {
                    row { TextRow(failureText(rip.reason)) }
                }
                DoneButton(container, onFinished)
            }
        }
    }

    if (confirmCancel && running) {
        HigAlert(
            title = stringResource(R.string.rip_cancel_title),
            message = stringResource(R.string.rip_cancel_message),
            actions = listOf(
                AlertAction(stringResource(R.string.rip_keep_going), AlertActionStyle.CANCEL) { confirmCancel = false },
                AlertAction(stringResource(R.string.rip_cancel), AlertActionStyle.DESTRUCTIVE) {
                    confirmCancel = false
                    container.rips.cancel()
                },
            ),
            onDismissRequest = { confirmCancel = false },
        )
    }
}

@Composable
private fun JobHeader(job: RipJob) {
    val metadata = job.metadata
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp)) {
        Text(
            text = if (metadata.year != null) "${metadata.title} (${metadata.year})" else metadata.title,
            style = HigTheme.typography.title3.copy(fontWeight = FontWeight.SemiBold),
            color = HigTheme.colors.label,
        )
        Text(
            text = listOfNotNull(job.sourceName, stringResource(R.string.source_title_name, job.title.number)).joinToString(" · "),
            style = HigTheme.typography.subheadline,
            color = HigTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun RunningContent(rip: RipState.Running) {
    val locale = currentLocale()
    val progress = rip.progress
    val measuring = stringResource(R.string.rip_measuring)
    GroupedSection(header = stringResource(R.string.rip_progress_header)) {
        row {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = if (progress == null) stringResource(R.string.rip_status_starting) else percent(progress.fraction, locale),
                    style = HigTheme.typography.title1.copy(fontWeight = FontWeight.SemiBold),
                    color = HigTheme.colors.label,
                )
                ProgressBar(progress?.fraction ?: 0f, Modifier.padding(top = 10.dp))
            }
        }
        row { ValueRow(stringResource(R.string.rip_speed), progress?.readBytesPerSecond?.let { Formats.speed(it, locale) } ?: measuring) }
        row { ValueRow(stringResource(R.string.rip_remaining), progress?.remainingMs?.let { Formats.duration(it / 1000) } ?: measuring) }
        row { ValueRow(stringResource(R.string.rip_written), Formats.bytes(progress?.outputBytes ?: 0, locale)) }
        row {
            ValueRow(
                stringResource(R.string.rip_position),
                Formats.duration((progress?.positionMs ?: 0) / 1000) + " / " + Formats.duration(rip.job.title.durationMs / 1000),
            )
        }
        row { ValueRow(stringResource(R.string.rip_elapsed), Formats.duration((progress?.elapsedMs ?: 0) / 1000)) }
    }
}

@Composable
private fun SucceededContent(rip: RipState.Succeeded) {
    val locale = currentLocale()
    val result = rip.result
    val file = rip.file
    val badSectors = result.badSectorCount
    GroupedSection(
        header = stringResource(R.string.rip_done_header),
        footer = stringResource(R.string.rip_result_footer, rip.job.path.relativeFile),
    ) {
        row { ValueRow(stringResource(R.string.rip_result_size), Formats.bytes(result.outputBytes, locale)) }
        row { ValueRow(stringResource(R.string.rip_result_duration), Formats.duration(file.durationMs / 1000)) }
        row { ValueRow(stringResource(R.string.rip_result_chapters), file.chapterStartsMs.size.toString()) }
        row {
            ValueRow(
                stringResource(R.string.rip_result_audio),
                file.audio.joinToString(", ") { stream ->
                    languageName(Iso639.fromBibliographic(stream.language), locale) ?: stream.codec.orEmpty()
                },
            )
        }
    }
    if (badSectors > 0 || result.warnings.isNotEmpty()) {
        GroupedSection(
            header = stringResource(R.string.rip_warnings_header),
            footer = if (badSectors > 0) stringResource(R.string.rip_bad_sectors_footer) else null,
            footerColor = HigTheme.colors.orange,
        ) {
            if (badSectors > 0) {
                row { TextRow(pluralStringResource(R.plurals.rip_bad_sectors, badSectors.toInt(), badSectors.toInt())) }
            }
            for (warning in result.warnings) row { TextRow(warning) }
        }
    }
}

@Composable
private fun DoneButton(container: AppContainer, onFinished: () -> Unit) {
    FilledButton(
        text = stringResource(R.string.action_done),
        onClick = {
            container.rips.dismiss()
            onFinished()
        },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp),
    )
}

@Composable
private fun TextRow(text: String) {
    RowFrame {
        Text(text, style = HigTheme.typography.body, color = HigTheme.colors.label, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
    }
}
