package be.discrip.ui.rip

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.disc.DiscState
import be.discrip.core.dvd.Iso639
import be.discrip.core.dvd.RipFailure
import be.discrip.core.format.Formats
import be.discrip.core.plex.PlexPublication
import be.discrip.drive.DriveStatus
import be.discrip.rip.DvdSource
import be.discrip.rip.NasOutcome
import be.discrip.rip.PlexOutcome
import be.discrip.rip.RipJob
import be.discrip.rip.RipStage
import be.discrip.rip.RipState
import be.discrip.ui.components.ActivityIndicator
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
import be.discrip.ui.settings.nasProblemText
import be.discrip.ui.shortLabelRes
import be.discrip.ui.theme.HigTheme

/** The running rip with its progress, then its result. */
@Composable
fun RipScreen(
    container: AppContainer,
    backLabel: String,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onOpenDisc: (insertion: Long) -> Unit,
) {
    val state by container.rips.rip.collectAsStateWithLifecycle()
    val driveStatus by container.drives.status.collectAsStateWithLifecycle()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    // Nothing to show, for example when the app was restarted: back to where the user came from.
    LaunchedEffect(state) {
        if (state == RipState.Idle) onBack()
    }

    // The rip service keeps the work going with the screen off.
    val running = state is RipState.Running

    HigScaffold(title = stringResource(R.string.rip_title), largeTitle = false, backLabel = backLabel, onBack = onBack) {
        when (val rip = state) {
            RipState.Idle -> Unit
            is RipState.Running -> {
                JobHeader(rip.job)
                PhaseLine(rip)
                when (rip.stage) {
                    RipStage.PREPARING -> GroupedSection(header = stringResource(R.string.rip_progress_header)) {
                        row { BusyRow(stringResource(R.string.rip_stage_preparing)) }
                    }
                    RipStage.RIPPING -> RunningContent(rip)
                    RipStage.UPLOADING -> UploadContent(rip)
                    RipStage.PLEX -> GroupedSection(header = stringResource(R.string.rip_stage_plex_header)) {
                        row { BusyRow(stringResource(R.string.rip_stage_plex)) }
                    }
                }
                val source = rip.job.source
                if (source is DvdSource.Drive && (rip.stage == RipStage.UPLOADING || rip.stage == RipStage.PLEX)) {
                    NextDisc(driveStatus, source, rip.discEjected, onOpenDisc)
                }
                GroupedSection(footer = stringResource(R.string.rip_keep_open_footer)) {
                    row {
                        if (rip.stage == RipStage.PLEX) {
                            // Plex scans either way; this only stops waiting for it.
                            ButtonRow(text = stringResource(R.string.rip_plex_skip), onClick = { container.rips.cancel() })
                        } else {
                            ButtonRow(
                                text = stringResource(
                                    when {
                                        rip.cancelling -> R.string.rip_cancelling
                                        rip.stage == RipStage.UPLOADING -> R.string.rip_cancel_upload
                                        else -> R.string.rip_cancel
                                    },
                                ),
                                onClick = { confirmCancel = true },
                                color = HigTheme.colors.red,
                                enabled = !rip.cancelling,
                                loading = rip.cancelling,
                            )
                        }
                    }
                }
            }
            is RipState.Succeeded -> {
                JobHeader(rip.job)
                SucceededContent(rip)
                DeliveryContent(rip)
                val source = rip.job.source
                if (source is DvdSource.Drive) {
                    NextDisc(driveStatus, source, rip.discEjected) { insertion ->
                        container.rips.dismiss()
                        onOpenDisc(insertion)
                    }
                }
                DoneButton(container, onFinished)
            }
            is RipState.Failed -> {
                JobHeader(rip.job)
                val locale = currentLocale()
                val needed = rip.neededBytes
                val free = rip.freeBytes
                val local = rip.job.destination.local != null
                GroupedSection(
                    header = stringResource(R.string.rip_failed_header),
                    footer = when {
                        needed != null && free != null -> stringResource(
                            when {
                                rip.nasProblem != null -> R.string.rip_failure_no_space_numbers_nas
                                local -> R.string.rip_failure_no_space_numbers
                                else -> R.string.rip_failure_no_space_numbers_app
                            },
                            Formats.bytes(needed, locale),
                            Formats.bytes(free, locale),
                        )
                        rip.message.isNotBlank() -> stringResource(R.string.rip_failure_detail, rip.message)
                        else -> null
                    },
                ) {
                    row { TextRow(failureText(rip)) }
                }
                DoneButton(container, onFinished)
            }
        }
    }

    val uploading = (state as? RipState.Running)?.takeIf { it.stage == RipStage.UPLOADING }
    if (confirmCancel && running) {
        HigAlert(
            title = stringResource(if (uploading != null) R.string.rip_cancel_upload_title else R.string.rip_cancel_title),
            message = stringResource(
                when {
                    uploading == null -> R.string.rip_cancel_message
                    uploading.job.destination.local != null -> R.string.rip_cancel_upload_message_both
                    else -> R.string.rip_cancel_upload_message_nas
                },
            ),
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
    val colors = HigTheme.colors
    val locale = currentLocale()
    val progress = rip.progress
    val measuring = stringResource(R.string.rip_measuring)
    // A lossy preset shows how fast it encodes, and whether that keeps up with the film.
    val lossy = rip.job.quality.isLossy
    val slower = lossy && progress?.slowerThanRealTime == true
    GroupedSection(
        header = stringResource(R.string.rip_progress_header),
        footer = if (slower) stringResource(R.string.rip_slower_footer) else null,
        footerColor = if (slower) colors.orange else null,
    ) {
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
        if (lossy) {
            rip.encoder?.let { encoder -> row { ValueRow(stringResource(R.string.rip_encoder), encoderName(encoder)) } }
            row {
                ValueRow(stringResource(R.string.rip_frame_rate), progress?.framesPerSecond?.let { Formats.framesPerSecond(it, locale) } ?: measuring)
            }
            row {
                ValueRow(
                    stringResource(R.string.rip_pace),
                    progress?.speed?.let { Formats.pace(it, locale) } ?: measuring,
                    valueColor = if (slower) colors.orange else colors.secondaryLabel,
                )
            }
        } else {
            row { ValueRow(stringResource(R.string.rip_speed), progress?.readBytesPerSecond?.let { Formats.speed(it, locale) } ?: measuring) }
        }
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
private fun UploadContent(rip: RipState.Running) {
    val locale = currentLocale()
    val upload = rip.upload
    val measuring = stringResource(R.string.rip_measuring)
    GroupedSection(header = stringResource(R.string.rip_upload_header)) {
        row {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = if (upload == null) stringResource(R.string.rip_status_starting) else percent(upload.fraction, locale),
                    style = HigTheme.typography.title1.copy(fontWeight = FontWeight.SemiBold),
                    color = HigTheme.colors.label,
                )
                ProgressBar(upload?.fraction ?: 0f, Modifier.padding(top = 10.dp))
            }
        }
        row { ValueRow(stringResource(R.string.rip_speed), upload?.bytesPerSecond?.let { Formats.speed(it, locale) } ?: measuring) }
        row { ValueRow(stringResource(R.string.rip_remaining), upload?.remainingMs?.let { Formats.duration(it / 1000) } ?: measuring) }
        if (upload != null) {
            row {
                ValueRow(
                    stringResource(R.string.rip_uploaded),
                    Formats.bytes(upload.bytesDone, locale) + " / " + Formats.bytes(upload.bytesTotal, locale),
                )
            }
        }
    }
}

/** Where the file ended up: the local folder, the NAS, and what Plex made of it. */
@Composable
private fun DeliveryContent(rip: RipState.Succeeded) {
    val colors = HigTheme.colors
    val uriHandler = LocalUriHandler.current
    GroupedSection(header = stringResource(R.string.rip_saved_header)) {
        rip.localFile?.let { row { DetailRow(stringResource(R.string.rip_saved_local), it) } }
        when (val nas = rip.nas) {
            is NasOutcome.Stored -> row { DetailRow(stringResource(R.string.rip_saved_nas), nas.location) }
            is NasOutcome.Failed -> row {
                val text = stringResource(nasProblemText(nas.problem))
                val again = when {
                    !nas.pending -> null
                    rip.localFile != null -> stringResource(R.string.rip_upload_pending_local)
                    else -> stringResource(R.string.rip_upload_pending)
                }
                DetailRow(stringResource(R.string.rip_saved_nas), listOfNotNull(text, again).joinToString(" "), colors.orange)
            }
            NasOutcome.Cancelled -> row { DetailRow(stringResource(R.string.rip_saved_nas), stringResource(R.string.rip_upload_cancelled)) }
            null -> Unit
        }
        rip.plex?.let { plex ->
            val problem = plex !is PlexOutcome.Published || plex.publication !is PlexPublication.Added
            row { DetailRow(stringResource(R.string.rip_saved_plex), plexText(plex), if (problem) colors.orange else null) }
            plex.link?.let { link ->
                row { ButtonRow(stringResource(R.string.plex_open), onClick = { uriHandler.openUri(link) }) }
            }
        }
    }
}

/** The phases of the job, the current one in the accent colour: "Lezen en muxen › Uploaden › Plex". */
@Composable
private fun PhaseLine(rip: RipState.Running) {
    val colors = HigTheme.colors
    val destination = rip.job.destination
    val phases = buildList {
        add(RipStage.RIPPING to stringResource(if (rip.job.quality.isLossy) R.string.rip_phase_read_encode else R.string.rip_phase_read_mux))
        if (destination.nas != null && !destination.writesDirectly) add(RipStage.UPLOADING to stringResource(R.string.rip_phase_upload))
        if (destination.plex != null) add(RipStage.PLEX to stringResource(R.string.rip_phase_plex))
    }
    if (phases.size < 2) return
    val current = phases.indexOfFirst { it.first == rip.stage }.coerceAtLeast(0)
    val text = buildAnnotatedString {
        phases.forEachIndexed { index, (_, name) ->
            if (index > 0) withStyle(SpanStyle(color = colors.tertiaryLabel)) { append(" › ") }
            val color = when {
                index < current -> colors.secondaryLabel
                index == current -> colors.accent
                else -> colors.tertiaryLabel
            }
            withStyle(SpanStyle(color = color, fontWeight = if (index == current) FontWeight.SemiBold else null)) { append(name) }
        }
    }
    Text(text, style = HigTheme.typography.subheadline, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

@Composable
private fun SucceededContent(rip: RipState.Succeeded) {
    val locale = currentLocale()
    val result = rip.result
    val file = rip.file
    val badSectors = result.badSectorCount
    GroupedSection(header = stringResource(R.string.rip_done_header), footer = stringResource(R.string.rip_result_footer)) {
        row { ValueRow(stringResource(R.string.rip_result_size), Formats.bytes(result.outputBytes, locale)) }
        row { ValueRow(stringResource(R.string.rip_result_duration), Formats.duration(file.durationMs / 1000)) }
        row { ValueRow(stringResource(R.string.rip_result_quality), stringResource(rip.job.quality.shortLabelRes())) }
        file.video.firstOrNull()?.let { video ->
            row { ValueRow(stringResource(R.string.rip_result_video), "${videoCodecName(video.codec)} ${video.width}×${video.height}") }
        }
        rip.encoder?.let { encoder -> row { ValueRow(stringResource(R.string.rip_encoder), encoderName(encoder)) } }
        if (result.interlacedFrames > 0) {
            row {
                ValueRow(
                    stringResource(R.string.rip_result_deinterlaced),
                    pluralStringResource(R.plurals.rip_frames, result.interlacedFrames.toInt(), result.interlacedFrames.toInt()),
                )
            }
        }
        row { ValueRow(stringResource(R.string.rip_result_chapters), file.chapterStartsMs.size.toString()) }
        row {
            ValueRow(
                stringResource(R.string.rip_result_audio),
                file.audio.joinToString(", ") { stream ->
                    languageName(Iso639.fromBibliographic(stream.language), locale) ?: stream.codec.orEmpty()
                },
            )
        }
        if (file.subtitles.isNotEmpty()) {
            row {
                ValueRow(
                    stringResource(R.string.rip_result_subtitles),
                    file.subtitles.joinToString(", ") { stream ->
                        languageName(Iso639.fromBibliographic(stream.language), locale) ?: stream.codec.orEmpty()
                    },
                )
            }
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

/**
 * After a rip from the drive, so a stack of discs goes quickly: put the next one in, then rip it
 * from here once the drive has read it.
 */
@Composable
private fun NextDisc(status: DriveStatus, ripped: DvdSource.Drive, ejected: Boolean, onOpenDisc: (insertion: Long) -> Unit) {
    val disc = (status as? DriveStatus.Connected)?.takeIf { !it.inUse }?.disc
    GroupedSection(
        header = stringResource(R.string.rip_next_disc_header),
        footer = if (ejected) stringResource(R.string.rip_next_disc_footer) else null,
    ) {
        when {
            disc is DiscState.Ready && disc.insertion != ripped.insertion && disc.disc.isDvdVideo -> {
                row { TextRow(disc.disc.label ?: stringResource(R.string.disc_untitled)) }
                row { ButtonRow(stringResource(R.string.rip_next_disc_open), onClick = { onOpenDisc(disc.insertion) }) }
            }
            disc == DiscState.Loading -> row { BusyRow(stringResource(R.string.disc_reading)) }
            else -> row {
                TextRow(stringResource(if (ejected || disc is DiscState.NoDisc) R.string.rip_next_disc_insert else R.string.rip_next_disc_take_out))
            }
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

@Composable
private fun BusyRow(text: String) {
    RowFrame {
        Text(text, style = HigTheme.typography.body, color = HigTheme.colors.secondaryLabel, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
        ActivityIndicator(Modifier.size(20.dp))
    }
}

/** A label with a longer text under it, such as a path. */
@Composable
private fun DetailRow(title: String, detail: String, detailColor: Color? = null) {
    RowFrame {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = HigTheme.typography.body, color = HigTheme.colors.label)
            Text(detail, style = HigTheme.typography.subheadline, color = detailColor ?: HigTheme.colors.secondaryLabel)
        }
    }
}

@Composable
private fun plexText(plex: PlexOutcome): String = when (plex) {
    is PlexOutcome.Published -> when (val publication = plex.publication) {
        is PlexPublication.Added -> stringResource(R.string.rip_plex_added, publication.title)
        is PlexPublication.NotSeen -> stringResource(R.string.rip_plex_not_seen)
        PlexPublication.SectionMissing -> stringResource(R.string.rip_plex_section_missing)
    }
    PlexOutcome.NotAwaited -> stringResource(R.string.rip_plex_not_awaited)
    is PlexOutcome.Failed -> stringResource(plexResultText(plex.result))
}

/** The failure in words that fit where the file was going: the local folder, the app's storage or the NAS. */
@Composable
private fun failureText(failed: RipState.Failed): String {
    val problem = failed.nasProblem
    val local = failed.job.destination.local != null
    return when {
        problem != null -> stringResource(nasProblemText(problem))
        !local && failed.reason == RipFailure.NO_SPACE -> stringResource(R.string.rip_failure_no_app_space)
        !local && failed.reason == RipFailure.WRITE_FAILED -> stringResource(R.string.rip_failure_app_write_failed)
        else -> failureText(failed.reason)
    }
}
