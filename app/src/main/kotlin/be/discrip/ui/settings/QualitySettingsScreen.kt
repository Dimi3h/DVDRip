package be.discrip.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.core.estimate.SizeEstimator
import be.discrip.core.format.Formats
import be.discrip.core.model.QualityPreset
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.CheckRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.Stepper
import be.discrip.ui.components.ToggleRow
import be.discrip.ui.descriptionRes
import be.discrip.ui.labelRes
import be.discrip.ui.shortLabelRes
import be.discrip.ui.theme.HigTheme
import java.util.Locale

private const val BITRATE_STEP_KBPS = 100

@Composable
fun QualitySettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locale = currentLocale()

    HigScaffold(
        title = stringResource(R.string.quality_title),
        largeTitle = false,
        backLabel = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        val current = settings ?: return@HigScaffold

        GroupedSection(
            header = stringResource(R.string.quality_default_header),
            footer = stringResource(R.string.quality_default_footer),
        ) {
            QualityPreset.entries.forEach { preset ->
                row {
                    val estimate = Formats.bytes(SizeEstimator.referenceBytes(preset, current.videoBitrateKbps(preset)), locale)
                    CheckRow(
                        title = stringResource(preset.labelRes()),
                        subtitle = stringResource(preset.descriptionRes()) + "\n" + stringResource(R.string.quality_estimate, estimate),
                        checked = current.defaultPreset == preset,
                        onClick = { viewModel.update { it.copy(defaultPreset = preset) } },
                    )
                }
            }
        }

        GroupedSection(
            header = stringResource(R.string.quality_bitrate_header),
            footer = stringResource(R.string.quality_bitrate_footer),
        ) {
            QualityPreset.lossy.forEach { preset ->
                row {
                    val kbps = current.videoBitrateKbps(preset) ?: return@row
                    BitrateRow(
                        label = stringResource(preset.shortLabelRes()),
                        kbps = kbps,
                        locale = locale,
                        onChange = { value ->
                            viewModel.update { it.copy(videoBitratesKbps = it.videoBitratesKbps + (preset to value)) }
                        },
                    )
                }
            }
            if (current.videoBitratesKbps.isNotEmpty()) {
                row {
                    ButtonRow(
                        text = stringResource(R.string.quality_reset),
                        onClick = { viewModel.update { it.copy(videoBitratesKbps = emptyMap()) } },
                    )
                }
            }
        }

        GroupedSection(
            header = stringResource(R.string.quality_audio_header),
            footer = stringResource(R.string.quality_flac_footer),
        ) {
            row {
                ToggleRow(
                    title = stringResource(R.string.quality_flac),
                    checked = current.convertLpcmToFlac,
                    onCheckedChange = { value -> viewModel.update { it.copy(convertLpcmToFlac = value) } },
                )
            }
        }
    }
}

@Composable
private fun BitrateRow(label: String, kbps: Int, locale: Locale, onChange: (Int) -> Unit) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    RowFrame {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = typography.body, color = colors.label)
            Text(Formats.bitrate(kbps, locale), style = typography.subheadline, color = colors.secondaryLabel)
        }
        Stepper(
            onDecrement = { onChange(QualityPreset.clampBitrate(kbps - BITRATE_STEP_KBPS)) },
            onIncrement = { onChange(QualityPreset.clampBitrate(kbps + BITRATE_STEP_KBPS)) },
            canDecrement = kbps > QualityPreset.MIN_VIDEO_BITRATE_KBPS,
            canIncrement = kbps < QualityPreset.MAX_VIDEO_BITRATE_KBPS,
        )
    }
}
