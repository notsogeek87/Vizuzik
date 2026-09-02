package com.vizuzik.app.domain.model

import android.net.Uri

data class Artist(
    val id: String,
    val sourceType: MusicSourceType,
    val name: String,
    val artworkUri: Uri?,
    val albumCount: Int,
    val trackCount: Int,
)
