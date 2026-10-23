package `in`.geekofia.ripster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A preview image from a source page, or nothing when there's no URL. The URL comes from page
 * metadata and is already https-validated; Coil loads it read-only. A failed or slow load just
 * shows the surface-coloured placeholder, so a broken image never disrupts the layout.
 */
@Composable
fun RemoteThumbnail(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentScale = ContentScale.Crop,
    )
}

/** A small, fixed-size variant for list rows (e.g. a queue card). */
@Composable
fun ThumbnailTile(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentScale = ContentScale.Crop,
    )
}
