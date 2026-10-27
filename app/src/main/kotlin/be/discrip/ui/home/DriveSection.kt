package be.discrip.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.discrip.R
import be.discrip.core.disc.DiscInfo
import be.discrip.core.disc.DiscProblem
import be.discrip.core.disc.DiscState
import be.discrip.core.disc.RegionStatus
import be.discrip.core.disc.RegionVerdict
import be.discrip.core.format.Formats
import be.discrip.drive.DriveFailure
import be.discrip.drive.DriveInfo
import be.discrip.drive.DriveStatus
import be.discrip.drive.UsbCandidate
import be.discrip.ui.components.ActivityIndicator
import be.discrip.ui.components.ButtonRow
import be.discrip.ui.components.GroupedSection
import be.discrip.ui.components.IconRowSeparatorInset
import be.discrip.ui.components.NavigationRow
import be.discrip.ui.components.RowFrame
import be.discrip.ui.components.SectionScope
import be.discrip.ui.components.SettingsIcon
import be.discrip.ui.components.ValueRow
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.settings.currentLocale
import be.discrip.ui.theme.HigTheme

/** What the user can do from the drive section. */
class DriveActions(
    val connect: (deviceName: String) -> Unit,
    val eject: () -> Unit,
    val closeTray: () -> Unit,
    val retryDisc: () -> Unit,
    val openMetadata: (volumeLabel: String?) -> Unit,
)

/** The "Station" section of Home: the USB drive and the disc in it. */
@Composable
fun DriveSection(status: DriveStatus, actions: DriveActions) {
    val header = stringResource(R.string.drive_header)
    when (status) {
        DriveStatus.UsbHostUnsupported -> GroupedSection(header = header) {
            row(IconRowSeparatorInset) {
                DriveRow(stringResource(R.string.drive_usb_unsupported_title), stringResource(R.string.drive_usb_unsupported_subtitle), warning = true)
            }
        }

        DriveStatus.NoDrive -> GroupedSection(header = header) {
            row(IconRowSeparatorInset) {
                DriveRow(stringResource(R.string.drive_none_title), stringResource(R.string.drive_none_subtitle))
            }
        }

        is DriveStatus.NeedsPermission -> GroupedSection(header = header, footer = stringResource(R.string.drive_connect_footer)) {
            deviceRow(status.device, R.string.drive_needs_permission)
            row { ButtonRow(stringResource(R.string.drive_connect), onClick = { actions.connect(status.device.deviceName) }) }
        }

        is DriveStatus.AwaitingPermission -> GroupedSection(header = header) {
            deviceRow(status.device, R.string.drive_awaiting_permission, busy = true)
        }

        is DriveStatus.PermissionDenied -> GroupedSection(header = header, footer = stringResource(R.string.drive_connect_footer)) {
            deviceRow(status.device, R.string.drive_permission_denied, warning = true)
            row { ButtonRow(stringResource(R.string.drive_ask_again), onClick = { actions.connect(status.device.deviceName) }) }
        }

        is DriveStatus.Connecting -> GroupedSection(header = header) {
            deviceRow(status.device, R.string.drive_connecting, busy = true)
        }

        is DriveStatus.NotOptical -> GroupedSection(header = header, footer = stringResource(R.string.drive_not_optical_footer)) {
            deviceRow(status.device, R.string.drive_not_optical, warning = true)
            row { ButtonRow(stringResource(R.string.drive_try_anyway), onClick = { actions.connect(status.device.deviceName) }) }
        }

        is DriveStatus.Failed -> GroupedSection(header = header, footer = stringResource(R.string.drive_failed_footer)) {
            val reason = when (status.reason) {
                DriveFailure.OPEN_FAILED -> R.string.drive_failed_open
                DriveFailure.CLAIM_FAILED -> R.string.drive_failed_claim
                DriveFailure.NO_ANSWER -> R.string.drive_failed_no_answer
                DriveFailure.UNRESPONSIVE -> R.string.drive_failed_unresponsive
            }
            deviceRow(status.device, reason, warning = true)
            row { ButtonRow(stringResource(R.string.drive_reconnect), onClick = { actions.connect(status.device.deviceName) }) }
        }

        is DriveStatus.Connected -> ConnectedDrive(header, status.drive, status.disc, actions)
    }
}

@Composable
private fun ConnectedDrive(header: String, drive: DriveInfo, disc: DiscState, actions: DriveActions) {
    val transport = drive.libusbVersion?.let { stringResource(R.string.drive_transport_libusb, it) }
        ?: stringResource(R.string.drive_transport_android)
    val footer = when (disc) {
        is DiscState.Ready -> readyFooter(disc.disc)
        is DiscState.Unusable -> if (disc.problem is DiscProblem.ReadError) stringResource(R.string.disc_read_error_footer) else null
        else -> null
    }
    GroupedSection(header = header, footer = footer, footerColor = if (footer != null && disc is DiscState.Ready) HigTheme.colors.orange else null) {
        row(IconRowSeparatorInset) { DriveRow(drive.displayName, transport) }
        when (disc) {
            DiscState.Unknown, DiscState.Loading -> row { BusyRow(stringResource(R.string.disc_reading)) }
            is DiscState.NoDisc -> if (disc.trayOpen == true) {
                row { TextRow(stringResource(R.string.disc_tray_open)) }
                row { ButtonRow(stringResource(R.string.disc_close_tray), onClick = actions.closeTray) }
            } else {
                row { TwoLineRow(stringResource(R.string.disc_none), stringResource(R.string.disc_none_subtitle)) }
                row { ButtonRow(stringResource(R.string.disc_open_tray), onClick = actions.eject) }
            }
            is DiscState.Ready -> readyDisc(disc.disc, actions)
            is DiscState.Unusable -> unusableDisc(disc.problem, actions)
        }
    }
}

private fun SectionScope.readyDisc(disc: DiscInfo, actions: DriveActions) {
    row {
        val kind = if (disc.isDvdVideo) R.string.disc_dvd_video else R.string.disc_not_dvd_video
        TwoLineRow(
            title = disc.label ?: stringResource(R.string.disc_untitled),
            subtitle = stringResource(kind) + " · " + Formats.bytes(disc.sizeBytes, currentLocale()),
        )
    }
    row {
        val protection = when (disc.cssProtected) {
            true -> R.string.disc_protection_css
            false -> R.string.disc_protection_none
            null -> R.string.disc_protection_unknown
        }
        ValueRow(stringResource(R.string.disc_protection), stringResource(protection))
    }
    disc.region?.let { region ->
        row {
            val problem = region.verdict == RegionVerdict.MISMATCH || region.verdict == RegionVerdict.DRIVE_REGION_NOT_SET
            ValueRow(
                stringResource(R.string.disc_region),
                regionText(region),
                valueColor = if (problem) HigTheme.colors.orange else HigTheme.colors.secondaryLabel,
            )
        }
    }
    if (disc.isDvdVideo) {
        row { NavigationRow(stringResource(R.string.disc_metadata), onClick = { actions.openMetadata(disc.label) }) }
    }
    row { ButtonRow(stringResource(R.string.disc_eject), onClick = actions.eject) }
}

private fun SectionScope.unusableDisc(problem: DiscProblem, actions: DriveActions) {
    row {
        when (problem) {
            is DiscProblem.WrongMedium -> TwoLineRow(
                stringResource(R.string.disc_wrong_medium_title),
                stringResource(R.string.disc_wrong_medium_subtitle, problem.profile.name),
                warning = true,
            )
            DiscProblem.Blank -> TwoLineRow(stringResource(R.string.disc_blank_title), stringResource(R.string.disc_blank_subtitle), warning = true)
            is DiscProblem.ReadError -> TwoLineRow(
                stringResource(R.string.disc_read_error_title),
                problem.sense?.let { stringResource(R.string.disc_read_error_sense, it.toString()) }
                    ?: stringResource(R.string.disc_read_error_unknown),
                warning = true,
            )
        }
    }
    if (problem is DiscProblem.ReadError) row { ButtonRow(stringResource(R.string.disc_retry), onClick = actions.retryDisc) }
    row { ButtonRow(stringResource(R.string.disc_eject), onClick = actions.eject) }
}

@Composable
private fun readyFooter(disc: DiscInfo): String? {
    val region = disc.region
    val target = region?.let { preferredRegion(it) }
    return when {
        !disc.isDvdVideo -> stringResource(R.string.disc_not_dvd_video_footer)
        region?.verdict == RegionVerdict.MISMATCH -> listOfNotNull(
            stringResource(
                R.string.disc_region_mismatch_footer,
                regions(region.driveRegions.orEmpty()),
                regions(region.discRegions),
                target.toString(),
            ),
            region.changesLeft?.let { pluralStringResource(R.plurals.disc_region_changes_left, it, it) },
        ).joinToString(" ")
        region?.verdict == RegionVerdict.DRIVE_REGION_NOT_SET -> stringResource(R.string.disc_region_not_set_footer, target.toString())
        else -> null
    }
}

/** Region 2 (Europe) when the disc allows it, else the disc's first region. */
private fun preferredRegion(region: RegionStatus): Int =
    if (region.discRegions.isEmpty() || 2 in region.discRegions) 2 else region.discRegions.first()

@Composable
private fun regionText(region: RegionStatus): String = when (region.verdict) {
    RegionVerdict.MATCH, RegionVerdict.MISMATCH -> stringResource(
        R.string.disc_region_value,
        regions(region.discRegions),
        regions(region.driveRegions.orEmpty()),
    )
    RegionVerdict.DRIVE_REGION_NOT_SET -> stringResource(R.string.disc_region_not_set)
    RegionVerdict.NOT_ENFORCED -> stringResource(R.string.disc_region_not_enforced)
    RegionVerdict.UNKNOWN -> stringResource(R.string.disc_region_unknown)
}

@Composable
private fun regions(set: Set<Int>): String = if (set.size == 8) stringResource(R.string.disc_region_all) else set.sorted().joinToString(", ")

private fun SectionScope.deviceRow(device: UsbCandidate, status: Int, busy: Boolean = false, warning: Boolean = false) {
    row(IconRowSeparatorInset) { DriveRow(device.displayName, stringResource(status), busy = busy, warning = warning) }
}

/** The drive itself: disc icon, name and a status line. */
@Composable
private fun DriveRow(title: String, subtitle: String, busy: Boolean = false, warning: Boolean = false) {
    val colors = HigTheme.colors
    RowFrame {
        SettingsIcon(HigIcons.Disc, if (warning) colors.orange else colors.accent)
        TwoLineTexts(title, subtitle, Modifier.weight(1f))
        if (busy) ActivityIndicator(Modifier.size(20.dp))
    }
}

@Composable
private fun TwoLineRow(title: String, subtitle: String, warning: Boolean = false) {
    RowFrame {
        TwoLineTexts(title, subtitle, Modifier.weight(1f), subtitleColor = if (warning) HigTheme.colors.orange else null)
    }
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

@Composable
private fun TwoLineTexts(title: String, subtitle: String, modifier: Modifier, subtitleColor: Color? = null) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(title, style = HigTheme.typography.body, color = HigTheme.colors.label)
        Text(subtitle, style = HigTheme.typography.subheadline, color = subtitleColor ?: HigTheme.colors.secondaryLabel)
    }
}
