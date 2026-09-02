package com.vizuzik.app.audio

import android.media.audiofx.BassBoost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BassBoostProcessor @Inject constructor() : AudioProcessor {

    private var effect: BassBoost? = null

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _strength = MutableStateFlow(0)
    val strength: StateFlow<Int> = _strength.asStateFlow()

    override fun attach(audioSessionId: Int) {
        release()
        runCatching {
            effect = BassBoost(0, audioSessionId).apply {
                enabled = _isEnabled.value
                setStrength(_strength.value.toShort())
            }
        }
    }

    fun setStrength(value: Int) {
        _strength.value = value
        runCatching { effect?.setStrength(value.toShort()) }
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
