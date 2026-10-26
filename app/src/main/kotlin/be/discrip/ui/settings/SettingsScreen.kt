package be.discrip.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.HigScaffold
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.components.ValueRow
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.labelRes
import be.discrip.ui.shortLabelRes
import be.discrip.ui.theme.HigTheme

@Composable
fun SettingsScreen(
    container: AppContainer,
    bottomPadding: Dp,
    onOpenQuality: () -> Unit,
    onOpenMetadata: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenNas: () -> Unit,
    onOpenPlex: () -> Unit,
) {
    val viewModel = rememberSettingsViewModel(container)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = HigTheme.colors
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    HigScaffold(title = stringResource(R.string.settings_title), bottomPadding = bottomPadding) {
        val current = settings ?: return@HigScaffold
        val notSet = stringResource(R.string.not_set)

        GroupedSection(header = stringResource(R.string.settings_section_rip)) {
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.settings_quality),
                    value = stringResource(current.defaultPreset.shortLabelRes()),
                    onClick = onOpenQuality,
                    icon = { SettingsIcon(HigIcons.Sliders, colors.accent) },
                )
            }
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.settings_metadata),
                    value = stringResource(current.metadataMode.labelRes()),
                    onClick = onOpenMetadata,
                    icon = { SettingsIcon(HigIcons.MagnifyingGlass, colors.orange) },
                )
            }
        }

        GroupedSection(header = stringResource(R.string.settings_section_storage)) {
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.settings_storage),
                    value = stringResource(current.defaultStorage.labelRes()),
                    onClick = onOpenStorage,
                    icon = { SettingsIcon(HigIcons.Folder, colors.green) },
                )
            }
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.settings_nas),
                    value = current.nas.host.ifBlank { notSet },
                    onClick = onOpenNas,
                    icon = { SettingsIcon(HigIcons.Server, colors.indigo) },
                )
            }
            row(IconRowSeparatorInset) {
                NavigationRow(
                    title = stringResource(R.string.settings_plex),
                    value = if (current.plex.isConfigured) stringResource(R.string.configured) else notSet,
                    onClick = onOpenPlex,
                    icon = { SettingsIcon(HigIcons.Play, colors.orange) },
                )
            }
        }

        GroupedSection(header = stringResource(R.string.settings_section_about)) {
            row(IconRowSeparatorInset) {
                ValueRow(
                    title = stringResource(R.string.settings_version),
                    value = version,
                    icon = { SettingsIcon(HigIcons.Info, colors.gray) },
                )
            }
        }
    }
}

private fun appVersion(context: Context): String {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    return info.versionName.orEmpty()
}
