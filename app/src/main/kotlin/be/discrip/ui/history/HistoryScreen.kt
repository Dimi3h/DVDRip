package be.discrip.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.dvd.RipFailure
import be.discrip.core.format.Formats
import be.discrip.core.history.RipRecord
import be.discrip.core.history.RipResult
import be.discrip.core.plex.PlexResult
import be.discrip.rip.Retry
import be.discrip.rip.RipStage
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.HigSheet
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.components.ValueRow
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.labelRes
import be.discrip.ui.rip.failureText
import be.discrip.ui.rip.percent
import be.discrip.ui.rip.plexResultText
import be.discrip.ui.settings.currentLocale
import be.discrip.ui.settings.nasProblemText
import be.discrip.ui.theme.HigTheme
import java.text.DateFormat
import java.util.Date

/** The rips made so far, newest first, with what became of each file ("Geschiedenis"). */
@Composable
fun HistoryScreen(container: AppContainer, bottomPadding: Dp) {
    val records by container.history.records.collectAsStateWithLifecycle()
    val retry by container.rips.retry.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { container.history.load() }

    HigScaffold(title = stringResource(R.string.history_title), bottomPadding = bottomPadding) {
        val list = records
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                text = stringResource(R.string.history_empty),
                style = HigTheme.typography.body,
                color = HigTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
            )
            else -> GroupedSection(footer = stringResource(R.string.history_footer)) {
                for (record in list) {
                    row(IconRowSeparatorInset) {
                        NavigationRow(
                            title = filmName(record),
                            subtitle = summary(record),
                            value = statusText(record, retry),
                            icon = { SettingsIcon(if (record.nasFile != null || record.storage.usesNas) HigIcons.Server else HigIcons.Folder, statusColor(record)) },
                            onClick = { selectedId = record.id },
                        )
                    }
                }
            }
        }
    }

    val selected = records?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        RecordSheet(container, selected, retry, onClose = { selectedId = null })
    }
}

@Composable
private fun RecordSheet(container: AppContainer, record: RipRecord, retry: Retry?, onClose: () -> Unit) {
    val locale = currentLocale()
    val colors = HigTheme.colors
    val busy = retry?.recordId == record.id
    HigSheet(
        title = filmName(record),
        onDismissRequest = onClose,
        doneLabel = stringResource(R.string.action_done),
        onDone = onClose,
    ) {
        GroupedSection {
            row { ValueRow(stringResource(R.string.history_when), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(record.endedAt))) }
            row {
                ValueRow(
                    stringResource(R.string.history_source),
                    listOfNotNull(record.sourceName, stringResource(R.string.source_title_name, record.titleNumber)).joinToString(" · "),
                )
            }
            row { ValueRow(stringResource(R.string.source_storage), stringResource(record.storage.labelRes())) }
            if (record.result == RipResult.SUCCEEDED) {
                row { ValueRow(stringResource(R.string.rip_result_size), Formats.bytes(record.bytes, locale)) }
                row { ValueRow(stringResource(R.string.rip_result_duration), Formats.duration(record.durationMs / 1000)) }
            }
            if (record.badSectors > 0) {
                row {
                    ValueRow(
                        stringResource(R.string.history_bad_sectors),
                        pluralStringResource(R.plurals.rip_bad_sectors, record.badSectors.toInt(), record.badSectors.toInt()),
                        valueColor = colors.orange,
                    )
                }
            }
        }

        if (record.result != RipResult.SUCCEEDED) {
            GroupedSection(
                header = stringResource(if (record.result == RipResult.CANCELLED) R.string.history_cancelled else R.string.rip_failed_header),
                footer = record.message?.let { stringResource(R.string.rip_failure_detail, it) },
            ) {
                row {
                    DetailRow(
                        text = record.nasProblem?.let { stringResource(nasProblemText(it)) }
                            ?: failureText(record.failure ?: RipFailure.FAILED),
                    )
                }
            }
        } else {
            GroupedSection(header = stringResource(R.string.rip_saved_header)) {
                record.localFile?.let { row { DetailRow(stringResource(R.string.rip_saved_local), it) } }
                when {
                    record.nasFile != null -> row { DetailRow(stringResource(R.string.rip_saved_nas), record.nasFile!!) }
                    record.nasProblem != null -> row {
                        DetailRow(stringResource(R.string.rip_saved_nas), stringResource(nasProblemText(record.nasProblem!!)), colors.orange)
                    }
                }
                record.plex?.let { plex ->
                    row {
                        DetailRow(
                            stringResource(R.string.rip_saved_plex),
                            stringResource(plexResultText(plex)),
                            if (plex == PlexResult.ADDED) null else colors.orange,
                        )
                    }
                }
            }
        }

        if (record.warnings.isNotEmpty()) {
            GroupedSection(header = stringResource(R.string.rip_warnings_header)) {
                for (warning in record.warnings) row { DetailRow(text = warning) }
            }
        }

        val pending = record.pendingUpload
        if (pending != null) {
            GroupedSection(
                footer = when {
                    busy && retry?.stage == RipStage.PLEX -> stringResource(R.string.rip_stage_plex)
                    busy -> stringResource(R.string.rip_status_uploading, percent(retry?.progress?.fraction ?: 0f, locale))
                    pending.stagedFile != null -> stringResource(R.string.history_pending_footer)
                    else -> stringResource(R.string.history_pending_local_footer)
                },
            ) {
                row {
                    ButtonRow(
                        text = stringResource(R.string.history_retry_upload),
                        onClick = { container.rips.retryUpload(record) },
                        enabled = retry == null,
                        loading = busy,
                    )
                }
                row {
                    ButtonRow(
                        text = stringResource(if (pending.stagedFile != null) R.string.history_discard_file else R.string.history_skip_upload),
                        onClick = { container.rips.discardPending(record) },
                        color = colors.red,
                        enabled = !busy,
                    )
                }
            }
        }

        GroupedSection {
            row {
                ButtonRow(
                    text = stringResource(R.string.history_remove),
                    onClick = {
                        container.rips.forget(record)
                        onClose()
                    },
                    color = colors.red,
                    enabled = !busy,
                )
            }
        }
    }
}

private fun filmName(record: RipRecord): String = if (record.year != null) "${record.title} (${record.year})" else record.title

@Composable
private fun summary(record: RipRecord): String {
    val locale = currentLocale()
    return buildList {
        add(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(record.endedAt)))
        if (record.result == RipResult.SUCCEEDED) add(Formats.bytes(record.bytes, locale))
        add(stringResource(record.storage.labelRes()))
    }.joinToString(" · ")
}

@Composable
private fun statusText(record: RipRecord, retry: Retry?): String = when {
    retry?.recordId == record.id -> stringResource(R.string.history_status_uploading)
    record.result == RipResult.CANCELLED -> stringResource(R.string.history_status_cancelled)
    record.result == RipResult.FAILED -> stringResource(R.string.history_status_failed)
    record.pendingUpload != null -> stringResource(R.string.history_status_pending)
    record.nasProblem != null -> stringResource(R.string.history_status_upload_failed)
    else -> stringResource(R.string.history_status_done)
}

@Composable
private fun statusColor(record: RipRecord): Color {
    val colors = HigTheme.colors
    return when {
        record.result == RipResult.FAILED -> colors.red
        record.result == RipResult.CANCELLED -> colors.secondaryLabel
        record.nasProblem != null || record.badSectors > 0 || (record.plex != null && record.plex != PlexResult.ADDED) -> colors.orange
        else -> colors.green
    }
}

@Composable
private fun DetailRow(title: String? = null, text: String, color: Color? = null) {
    RowFrame {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (title != null) Text(title, style = HigTheme.typography.body, color = HigTheme.colors.label)
            Text(
                text,
                style = if (title != null) HigTheme.typography.subheadline else HigTheme.typography.body,
                color = color ?: if (title != null) HigTheme.colors.secondaryLabel else HigTheme.colors.label,
            )
        }
    }
}
