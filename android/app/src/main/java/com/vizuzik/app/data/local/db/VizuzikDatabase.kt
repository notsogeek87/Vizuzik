package com.vizuzik.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vizuzik.app.data.local.db.dao.LibraryDao
import com.vizuzik.app.data.local.db.dao.PlaylistDao
import com.vizuzik.app.data.local.db.dao.RecentlyPlayedDao
import com.vizuzik.app.data.local.db.entity.PlaylistEntity
import com.vizuzik.app.data.local.db.entity.PlaylistTrackCrossRef
import com.vizuzik.app.data.local.db.entity.RecentlyPlayedEntity
import com.vizuzik.app.data.local.db.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        RecentlyPlayedEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VizuzikDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun playlistDao(): PlaylistDao
}
