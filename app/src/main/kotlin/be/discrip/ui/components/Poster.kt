package be.discrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.theme.HigTheme
import coil3.compose.AsyncImage

/** Film poster at 2:3 with rounded corners; shows a film glyph while loading or without image. */
@Composable
fun Poster(url: String?, width: Dp, modifier: Modifier = Modifier) {
    val colors = HigTheme.colors
    Box(
        modifier
            .size(width = width, height = width * 1.5f)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.tertiaryFill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(HigIcons.Film, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(width * 0.45f))
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
