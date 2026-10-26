package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.discrip.ui.theme.HigTheme

/** Large prominent button (`.borderedProminent`, large): 50 dp high, accent fill. */
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = HigTheme.colors.accent,
) {
    val colors = HigTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .alpha(if (pressed) 0.75f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) color else colors.tertiaryFill)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            ActivityIndicator(Modifier.size(22.dp), color = Color.White)
        } else {
            Text(
                text = text,
                style = HigTheme.typography.headline,
                color = if (enabled) Color.White else colors.tertiaryLabel,
            )
        }
    }
}

/** Text button for navigation bars: accent text, dims while pressed. */
@Composable
fun BarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val colors = HigTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = HigTheme.typography.body.copy(fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal),
            color = when {
                !enabled -> colors.tertiaryLabel
                pressed -> colors.accent.copy(alpha = 0.3f)
                else -> colors.accent
            },
        )
    }
}
