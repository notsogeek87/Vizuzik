package com.vizuzik.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Charge une pochette via Coil (cache mémoire/disque, décodage économe en
 * mémoire) et retombe sur une icône générique quand elle est absente —
 * requis pour les pochettes manquantes/artistes-albums inconnus.
 */
@Composable
fun ArtworkImage(
    uri: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8,
    fallbackIcon: ImageVector = Icons.Filled.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            SubcomposeAsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { FallbackIcon(fallbackIcon) },
                loading = { FallbackIcon(fallbackIcon) },
            )
        } else {
            FallbackIcon(fallbackIcon)
        }
    }
}

@Composable
private fun FallbackIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxSize(0.4f),
    )
}
