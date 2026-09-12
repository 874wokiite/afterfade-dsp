package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

class ResampleTest {

    @Test
    fun resampleToUsesRoundedLength() {
        val x = FloatArray(48000) { kotlin.math.sin(0.01 * it).toFloat() }
        assertEquals(44100, resampleTo(x, 48000, 44100).size)
        assertEquals(48000, resampleTo(FloatArray(44100), 44100, 48000).size)
    }
}
