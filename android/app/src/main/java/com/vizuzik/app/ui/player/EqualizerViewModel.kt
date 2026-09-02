package com.vizuzik.app.ui.player

import androidx.lifecycle.ViewModel
import com.vizuzik.app.audio.AudioEngine
import com.vizuzik.app.audio.EqualizerBand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
) : ViewModel() {

    val bands: StateFlow<List<EqualizerBand>> = audioEngine.equalizer.bands
    val equalizerEnabled: StateFlow<Boolean> = audioEngine.equalizer.isEnabled

    val bassBoostEnabled: StateFlow<Boolean> = audioEngine.bassBoost.isEnabled
    val bassBoostStrength: StateFlow<Int> = audioEngine.bassBoost.strength

    val virtualizerEnabled: StateFlow<Boolean> = audioEngine.virtualizer.isEnabled
    val virtualizerStrength: StateFlow<Int> = audioEngine.virtualizer.strength

    fun setEqualizerEnabled(enabled: Boolean) = audioEngine.equalizer.setEnabled(enabled)
    fun setBandLevel(index: Int, levelMillibel: Int) = audioEngine.equalizer.setBandLevel(index, levelMillibel)

    fun setBassBoostEnabled(enabled: Boolean) = audioEngine.bassBoost.setEnabled(enabled)
    fun setBassBoostStrength(value: Int) = audioEngine.bassBoost.setStrength(value)

    fun setVirtualizerEnabled(enabled: Boolean) = audioEngine.virtualizer.setEnabled(enabled)
    fun setVirtualizerStrength(value: Int) = audioEngine.virtualizer.setStrength(value)
}
