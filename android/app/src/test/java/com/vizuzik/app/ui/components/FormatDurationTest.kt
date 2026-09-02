package com.vizuzik.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {

    @Test
    fun `formats seconds under a minute`() {
        assertEquals("0:09", formatDuration(9_000))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("3:05", formatDuration(185_000))
    }

    @Test
    fun `formats hours for very long tracks`() {
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun `never returns negative for a zero or negative duration`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-500))
    }
}
