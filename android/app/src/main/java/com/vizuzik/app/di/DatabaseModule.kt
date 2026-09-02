package com.vizuzik.app.di

import android.content.Context
import androidx.room.Room
import com.vizuzik.app.data.local.db.VizuzikDatabase
import com.vizuzik.app.data.local.db.dao.LibraryDao
import com.vizuzik.app.data.local.db.dao.PlaylistDao
import com.vizuzik.app.data.local.db.dao.RecentlyPlayedDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VizuzikDatabase =
        Room.databaseBuilder(context, VizuzikDatabase::class.java, "vizuzik.db").build()

    @Provides
    fun provideLibraryDao(database: VizuzikDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideRecentlyPlayedDao(database: VizuzikDatabase): RecentlyPlayedDao = database.recentlyPlayedDao()

    @Provides
    fun providePlaylistDao(database: VizuzikDatabase): PlaylistDao = database.playlistDao()
}
