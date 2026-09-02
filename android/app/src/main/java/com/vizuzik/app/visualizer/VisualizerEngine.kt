package com.vizuzik.app.visualizer

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyseur de spectre simple basé sur [android.media.audiofx.Visualizer].
 * Nécessite la permission RECORD_AUDIO (demandée uniquement à l'ouverture de
 * l'écran visualiseur) : si elle est refusée, [attach] échoue silencieusement
 * et [magnitudes] reste vide — le lecteur reste pleinement fonctionnel.
 */
@Singleton
class VisualizerEngine @Inject constructor() {

    private var visualizer: Visualizer? = null

    private val _magnitudes = MutableStateFlow(FloatArray(BAR_COUNT))
    val magnitudes: StateFlow<FloatArray> = _magnitudes.asStateFlow()

    fun attach(audioSessionId: Int) {
        release()
        runCatching {
            val maxCaptureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = maxCaptureSize.coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            fft ?: return
                            _magnitudes.value = fftMagnitudesToBars(fft, BAR_COUNT)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                // La capture ne démarre qu'à l'ouverture effective de l'écran
                // visualiseur (voir setActive), pour ne pas consommer de CPU/
                // batterie en continu pendant une lecture normale.
                enabled = false
            }
        }
    }

    /** Démarre/arrête la capture FFT. À appeler depuis l'écran visualiseur (visible/invisible). */
    fun setActive(active: Boolean) {
        runCatching { visualizer?.enabled = active }
        if (!active) _magnitudes.value = FloatArray(BAR_COUNT)
    }

    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        _magnitudes.value = FloatArray(BAR_COUNT)
    }

    companion object {
        const val BAR_COUNT = 24
    }
}

/**
 * Convertit la trame FFT brute (format [android.media.audiofx.Visualizer])
 * en [barCount] amplitudes normalisées entre 0 et 1. Fonction pure — testée
 * sans dépendance Android.
 */
fun fftMagnitudesToBars(fft: ByteArray, barCount: Int): FloatArray {
    val n = fft.size / 2
    val bars = FloatArray(barCount)
    if (n <= 0) return bars

    val magnitudes = FloatArray(n)
    magnitudes[0] = kotlin.math.abs(fft[0].toInt()).toFloat()
    for (i in 1 until n) {
        val re = fft[2 * i].toInt()
        val im = if (2 * i + 1 < fft.size) fft[2 * i + 1].toInt() else 0
        magnitudes[i] = sqrt((re * re + im * im).toFloat())
    }

    val bucketSize = (n / barCount).coerceAtLeast(1)
    for (b in 0 until barCount) {
        val start = b * bucketSize
        val end = (start + bucketSize).coerceAtMost(n)
        if (start >= end) continue
        var sum = 0f
        for (i in start until end) sum += magnitudes[i]
        bars[b] = (sum / (end - start) / 128f).coerceIn(0f, 1f)
    }
    return bars
}
