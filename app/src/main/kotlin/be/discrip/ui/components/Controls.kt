package be.discrip.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.discrip.ui.theme.HigTheme
import kotlin.math.roundToInt

/** UISwitch: 51 x 31 dp, green when on, springy thumb. */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HigTheme.colors
    val haptics = rememberHaptics()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "switch",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            haptics.toggle(it)
            onCheckedChange(it)
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                drawRoundRect(
                    color = lerp(colors.switchTrackOff, colors.green, progress.coerceIn(0f, 1f)),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
            .then(toggle),
    ) {
        Box(
            Modifier
                .offset { IntOffset(((2 + 20 * progress) * density).roundToInt(), (2 * density).roundToInt()) }
                .size(27.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** UISegmentedControl with a sliding thumb. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HigTheme.colors
    val haptics = rememberHaptics()
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "segment",
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.tertiaryFill)
            .padding(2.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val separatorColor = colors.separator
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val segment = size.width / options.size
                    for (i in 1 until options.size) {
                        val adjacent = i == selectedIndex || i - 1 == selectedIndex
                        if (!adjacent) {
                            val x = segment * i
                            drawLine(separatorColor, Offset(x, size.height * 0.25f), Offset(x, size.height * 0.75f), strokeWidth = 1.dp.toPx())
                        }
                    }
                },
        )
        Box(
            Modifier
                .offset { IntOffset((segmentWidth.toPx() * position).roundToInt(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(elevation = if (colors.isDark) 0.dp else 2.dp, shape = RoundedCornerShape(7.dp))
                .background(colors.segmentSelected, RoundedCornerShape(7.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!selected) {
                                haptics.selection()
                                onSelect(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = HigTheme.typography.footnote.copy(
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** UIStepper: a pill with − and + halves. */
@Composable
fun Stepper(
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    val colors = HigTheme.colors
    val haptics = rememberHaptics()
    Row(
        modifier
            .size(width = 94.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tertiaryFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperHalf("−", canDecrement) {
            haptics.selection()
            onDecrement()
        }
        Box(Modifier.width(1.dp).height(18.dp).background(colors.separator))
        StepperHalf("+", canIncrement) {
            haptics.selection()
            onIncrement()
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StepperHalf(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = HigTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (pressed) colors.fill else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = HigTheme.typography.title3, color = if (enabled) colors.label else colors.tertiaryLabel)
    }
}

/** UIActivityIndicatorView (medium): eight spokes fading around the circle. */
@Composable
fun ActivityIndicator(modifier: Modifier = Modifier, color: Color = HigTheme.colors.secondaryLabel) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerStep",
    )
    Canvas(modifier.size(20.dp).rotate(step.toInt() * 45f)) {
        val radius = size.minDimension / 2
        val stroke = radius * 0.22f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0 - 90.0)
            val inner = radius * 0.5f
            val outer = radius - stroke / 2
            val start = Offset(center.x + inner * kotlin.math.cos(angle).toFloat(), center.y + inner * kotlin.math.sin(angle).toFloat())
            val end = Offset(center.x + outer * kotlin.math.cos(angle).toFloat(), center.y + outer * kotlin.math.sin(angle).toFloat())
            val alpha = 1f - ((8 - i) % 8) / 8f * 0.85f
            drawLine(color.copy(alpha = color.alpha * alpha), start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** A filled grey circle with a white cross, as in a UITextField clear button. */
@Composable
fun ClearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HigTheme.colors
    val background = colors.tertiaryLabel
    val cross = if (colors.isDark) Color.Black else Color.White
    Box(
        modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            drawCircle(background)
            val d = size.minDimension * 0.17f
            val c = center
            drawLine(cross, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(cross, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Fills the parent width; handy inside sections for custom content with standard padding. */
@Composable
fun SectionContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

/** UIProgressView (default style): a 4 dp rounded track that fills with the accent color. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = HigTheme.colors.accent) {
    val colors = HigTheme.colors
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "progress")
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(colors.tertiaryFill, cornerRadius = radius)
        if (shown > 0f) drawRoundRect(color, size = size.copy(width = size.width * shown), cornerRadius = radius)
    }
}
