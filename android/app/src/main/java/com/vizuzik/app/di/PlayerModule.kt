package com.vizuzik.app.di

import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.player.Media3MusicPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    abstract fun bindMusicPlayer(impl: Media3MusicPlayer): MusicPlayer
}
