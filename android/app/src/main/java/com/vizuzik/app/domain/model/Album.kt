package com.vizuzik.app.domain.model

data class Album(
    val id: String,
    val sourceType: MusicSourceType,
    val title: String,
    val artist: String,
    val artistId: String,
    val artworkUri: String?,
    val year: Int,
    val trackCount: Int,
)
