package com.vizuzik.app.ui.player

import androidx.lifecycle.ViewModel
import com.vizuzik.app.visualizer.VisualizerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class VisualizerViewModel @Inject constructor(
    private val visualizerEngine: VisualizerEngine,
) : ViewModel() {

    val magnitudes: StateFlow<FloatArray> = visualizerEngine.magnitudes

    fun setActive(active: Boolean) = visualizerEngine.setActive(active)

    override fun onCleared() {
        visualizerEngine.setActive(false)
        super.onCleared()
    }
}
