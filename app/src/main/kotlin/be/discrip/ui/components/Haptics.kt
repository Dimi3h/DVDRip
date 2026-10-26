package be.discrip.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Subtle haptics in the places iOS uses them: toggles, segment changes, success and failure. */
class Haptics(private val feedback: HapticFeedback) {
    fun toggle(on: Boolean) =
        feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    fun selection() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    fun success() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun failure() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
