package com.vizuzik.app.audio

import android.media.audiofx.PresetReverb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetReverbProcessor @Inject constructor() : AudioProcessor {

    private var effect: PresetReverb? = null

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _preset = MutableStateFlow(ReverbPreset.NONE)
    val preset: StateFlow<ReverbPreset> = _preset.asStateFlow()

    override fun attach(audioSessionId: Int) {
        release()
        runCatching {
            effect = PresetReverb(0, audioSessionId).apply {
                enabled = _isEnabled.value
                preset = _preset.value.presetValue
            }
        }
    }

    fun setPreset(value: ReverbPreset) {
        _preset.value = value
        runCatching { effect?.preset = value.presetValue }
    }

    override fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        runCatching { effect?.enabled = enabled }
    }

    override fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
