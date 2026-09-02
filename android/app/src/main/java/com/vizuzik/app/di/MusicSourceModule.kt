package com.vizuzik.app.di

import com.vizuzik.app.data.repository.MusicRepositoryImpl
import com.vizuzik.app.data.repository.PlaylistRepositoryImpl
import com.vizuzik.app.data.source.LocalMusicSource
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.repository.PlaylistRepository
import com.vizuzik.app.domain.source.MusicSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * V0.1 ne lie que la source locale. Une future source Deezer se
 * brancherait ici (ex. avec un [javax.inject.Qualifier] par source et un
 * [MusicRepository] agrégeant plusieurs [MusicSource]) sans changer le
 * reste de l'app.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MusicSourceModule {

    @Binds
    abstract fun bindMusicSource(impl: LocalMusicSource): MusicSource

    @Binds
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository

    @Binds
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository
}
