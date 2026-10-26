package be.discrip.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * iOS semantic system colors (UIKit values for iOS 17), light and dark. Names follow UIKit so the
 * HIG documentation maps one to one.
 */
@Immutable
data class HigColors(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val tertiaryGroupedBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    val separator: Color,
    val opaqueSeparator: Color,
    val fill: Color,
    val secondaryFill: Color,
    val tertiaryFill: Color,
    val cellHighlight: Color,
    val chrome: Color,
    val switchTrackOff: Color,
    val segmentSelected: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val indigo: Color,
    val purple: Color,
    val teal: Color,
    val pink: Color,
    val gray: Color,
) {
    companion object {
        val Light = HigColors(
            isDark = false,
            accent = Color(0xFF007AFF),
            background = Color(0xFFFFFFFF),
            groupedBackground = Color(0xFFF2F2F7),
            secondaryGroupedBackground = Color(0xFFFFFFFF),
            tertiaryGroupedBackground = Color(0xFFF2F2F7),
            label = Color(0xFF000000),
            secondaryLabel = Color(0x993C3C43),
            tertiaryLabel = Color(0x4D3C3C43),
            quaternaryLabel = Color(0x2E3C3C43),
            separator = Color(0x4A3C3C43),
            opaqueSeparator = Color(0xFFC6C6C8),
            fill = Color(0x33787880),
            secondaryFill = Color(0x29787880),
            tertiaryFill = Color(0x1F767680),
            cellHighlight = Color(0xFFD1D1D6),
            chrome = Color(0xF2F9F9F9),
            switchTrackOff = Color(0xFFE9E9EA),
            segmentSelected = Color(0xFFFFFFFF),
            green = Color(0xFF34C759),
            red = Color(0xFFFF3B30),
            orange = Color(0xFFFF9500),
            yellow = Color(0xFFFFCC00),
            indigo = Color(0xFF5856D6),
            purple = Color(0xFFAF52DE),
            teal = Color(0xFF30B0C7),
            pink = Color(0xFFFF2D55),
            gray = Color(0xFF8E8E93),
        )

        val Dark = HigColors(
            isDark = true,
            accent = Color(0xFF0A84FF),
            background = Color(0xFF000000),
            groupedBackground = Color(0xFF000000),
            secondaryGroupedBackground = Color(0xFF1C1C1E),
            tertiaryGroupedBackground = Color(0xFF2C2C2E),
            label = Color(0xFFFFFFFF),
            secondaryLabel = Color(0x99EBEBF5),
            tertiaryLabel = Color(0x4DEBEBF5),
            quaternaryLabel = Color(0x29EBEBF5),
            separator = Color(0x99545458),
            opaqueSeparator = Color(0xFF38383A),
            fill = Color(0x5C787880),
            secondaryFill = Color(0x52787880),
            tertiaryFill = Color(0x3D767680),
            cellHighlight = Color(0xFF3A3A3C),
            chrome = Color(0xF0161616),
            switchTrackOff = Color(0xFF39393D),
            segmentSelected = Color(0xFF636366),
            green = Color(0xFF30D158),
            red = Color(0xFFFF453A),
            orange = Color(0xFFFF9F0A),
            yellow = Color(0xFFFFD60A),
            indigo = Color(0xFF5E5CE6),
            purple = Color(0xFFBF5AF2),
            teal = Color(0xFF40C8E0),
            pink = Color(0xFFFF375F),
            gray = Color(0xFF8E8E93),
        )
    }
}
