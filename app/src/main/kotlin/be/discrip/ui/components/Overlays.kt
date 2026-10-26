package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.discrip.ui.theme.HigTheme

enum class AlertActionStyle { DEFAULT, CANCEL, DESTRUCTIVE }

data class AlertAction(
    val label: String,
    val style: AlertActionStyle = AlertActionStyle.DEFAULT,
    val onClick: () -> Unit,
)

/** UIAlertController (.alert): 270 dp card, centered texts, buttons split by hairlines. */
@Composable
fun HigAlert(
    title: String,
    message: String?,
    actions: List<AlertAction>,
    onDismissRequest: () -> Unit,
) {
    val colors = HigTheme.colors
    val typography = HigTheme.typography
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 18.dp)) {
                Text(title, style = typography.headline, color = colors.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (message != null) {
                    Text(
                        text = message,
                        style = typography.footnote,
                        color = colors.label,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    )
                }
            }
            Hairline()
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    AlertButton(actions[0], Modifier.weight(1f))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))
                    AlertButton(actions[1], Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { index, action ->
                    if (index > 0) Hairline()
                    AlertButton(action, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val colors = HigTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(44.dp)
            .background(if (pressed) colors.fill else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            style = HigTheme.typography.body.copy(
                fontWeight = if (action.style == AlertActionStyle.CANCEL) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (action.style == AlertActionStyle.DESTRUCTIVE) colors.red else colors.accent,
        )
    }
}

/**
 * A page sheet: grabber, centered title with an optional trailing action, grouped background.
 * Built on Material's modal bottom sheet for gestures and insets, restyled for iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HigSheet(
    title: String,
    onDismissRequest: () -> Unit,
    doneLabel: String? = null,
    onDone: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HigTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.groupedBackground,
        contentColor = colors.label,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        scrimColor = Color.Black.copy(alpha = 0.4f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(colors.tertiaryLabel),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Text(
                text = title,
                style = HigTheme.typography.headline,
                color = colors.label,
                modifier = Modifier.align(Alignment.Center),
            )
            if (doneLabel != null && onDone != null) {
                BarButton(doneLabel, onDone, prominent = true, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), content = content)
    }
}
