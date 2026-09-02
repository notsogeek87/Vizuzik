package com.vizuzik.app.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point d'entrée unique de la chaîne d'effets, rattachée par [PlaybackService]
 * à chaque changement de session audio ExoPlayer. Les écrans EQ/FX injectent
 * directement les processeurs individuels ([equalizer], [bassBoost], ...)
 * pour piloter leurs contrôles.
 */
@Singleton
class AudioEngine @Inject constructor(
    val equalizer: EqualizerProcessor,
    val bassBoost: BassBoostProcessor,
    val virtualizer: VirtualizerProcessor,
    val reverb: PresetReverbProcessor,
) {
    private val processors: List<AudioProcessor> get() = listOf(equalizer, bassBoost, virtualizer, reverb)

    fun attach(audioSessionId: Int) {
        processors.forEach { it.attach(audioSessionId) }
    }

    fun release() {
        processors.forEach { it.release() }
    }
}
