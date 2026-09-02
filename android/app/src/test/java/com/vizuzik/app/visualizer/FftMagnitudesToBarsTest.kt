package com.vizuzik.app.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftMagnitudesToBarsTest {

    @Test
    fun `returns silence bars for an all-zero fft frame`() {
        val bars = fftMagnitudesToBars(ByteArray(256), barCount = 8)

        assertEquals(8, bars.size)
        assertTrue(bars.all { it == 0f })
    }

    @Test
    fun `values are always normalized between 0 and 1`() {
        val fft = ByteArray(256) { (it % 256 - 128).toByte() }

        val bars = fftMagnitudesToBars(fft, barCount = 16)

        assertTrue(bars.all { it in 0f..1f })
    }

    @Test
    fun `an empty fft frame yields silence instead of crashing`() {
        val bars = fftMagnitudesToBars(ByteArray(0), barCount = 4)

        assertEquals(4, bars.size)
        assertTrue(bars.all { it == 0f })
    }
}
