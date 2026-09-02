package com.vizuzik.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.vizuzik.app.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    @Query("INSERT OR REPLACE INTO recently_played (trackId, playedAt) VALUES (:trackId, :playedAt)")
    suspend fun recordPlayed(trackId: String, playedAt: Long)

    @Query(
        """
        SELECT tracks.* FROM tracks
        INNER JOIN recently_played ON tracks.id = recently_played.trackId
        ORDER BY recently_played.playedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("DELETE FROM recently_played WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun pruneOrphans()
}
