package com.vizuzik.app.domain.model

data class Artist(
    val id: String,
    val sourceType: MusicSourceType,
    val name: String,
    val artworkUri: String?,
    val albumCount: Int,
    val trackCount: Int,
)
