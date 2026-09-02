package com.vizuzik.app.audio

import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqualizerProcessor @Inject constructor() : AudioProcessor {

    private var equalizer: Equalizer? = null

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _bands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val bands: StateFlow<List<EqualizerBand>> = _bands.asStateFlow()

    override fun attach(audioSessionId: Int) {
        release()
        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            eq.enabled = _isEnabled.value
            val range = eq.bandLevelRange
            _bands.value = (0 until eq.numberOfBands).map { i ->
                val band = i.toShort()
                EqualizerBand(
                    index = i,
                    centerFreqHz = eq.getCenterFreq(band) / 1000,
                    minLevelMillibel = range[0].toInt(),
                    maxLevelMillibel = range[1].toInt(),
                    currentLevelMillibel = eq.getBandLevel(band).toInt(),
                )
            }
        }
    }

    fun setBandLevel(bandIndex: Int, levelMillibel: Int) {
        runCatching { equalizer?.setBandLevel(bandIndex.toShort(), levelMillibel.toShort()) }
        _bands.update { bands -> bands.map { if (it.index == bandIndex) it.copy(currentLevelMillibel = levelMillibel) else it } }
    }

    override fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        runCatching { equalizer?.enabled = enabled }
    }

    override fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
