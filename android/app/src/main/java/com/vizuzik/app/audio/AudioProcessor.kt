package com.vizuzik.app.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Un effet audio réel, adossé à une des API [android.media.audiofx]
 * (Equalizer, BassBoost, Virtualizer, PresetReverb) et attaché à la session
 * audio d'ExoPlayer. Aucun de ces effets ne simule quoi que ce soit en
 * manipulant le volume : ce sont les mêmes API DSP système que Winamp/tout
 * lecteur Android utilise.
 */
interface AudioProcessor {
    val isEnabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)

    /** Rattache l'effet à la session audio courante d'ExoPlayer (change à chaque lecture). */
    fun attach(audioSessionId: Int)
    fun release()
}

data class EqualizerBand(
    val index: Int,
    val centerFreqHz: Int,
    val minLevelMillibel: Int,
    val maxLevelMillibel: Int,
    val currentLevelMillibel: Int,
)

enum class ReverbPreset(val presetValue: Short, val label: String) {
    NONE(0, "Aucun"),
    SMALL_ROOM(1, "Petite pièce"),
    MEDIUM_ROOM(2, "Pièce moyenne"),
    LARGE_ROOM(3, "Grande pièce"),
    MEDIUM_HALL(4, "Salle moyenne"),
    LARGE_HALL(5, "Grande salle"),
    PLATE(6, "Plaque"),
}
