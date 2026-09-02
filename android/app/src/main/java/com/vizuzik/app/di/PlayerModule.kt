package com.vizuzik.app.di

import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.player.MusicPlayerRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Toute l'UI injecte [MusicPlayer] sans qualifier et reçoit [MusicPlayerRouter],
 * qui délègue en interne à la lecture locale (Media3) ou à Deezer selon la
 * source active — voir [com.vizuzik.app.player.PlaybackSourceController].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    abstract fun bindMusicPlayer(impl: MusicPlayerRouter): MusicPlayer
}
