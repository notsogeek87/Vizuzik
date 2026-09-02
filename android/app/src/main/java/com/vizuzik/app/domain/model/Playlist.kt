package com.vizuzik.app.domain.model

data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val tracks: List<Track> = emptyList(),
) {
    val trackCount: Int get() = tracks.size
}
