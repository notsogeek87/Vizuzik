package com.vizuzik.app.domain.model

import android.net.Uri

data class Album(
    val id: String,
    val sourceType: MusicSourceType,
    val title: String,
    val artist: String,
    val artistId: String,
    val artworkUri: Uri?,
    val year: Int,
    val trackCount: Int,
)
