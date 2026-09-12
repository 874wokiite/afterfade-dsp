package com.afterfade.dsp

import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectsTest {

    private val sr = 44100

    @Test
    fun tapeWarbleHandlesEmptyInput() {
        assertEquals(0, tapeWarble(FloatArray(0), sr).size)
    }

    @Test
    fun softSaturateCompressesPeaks() {
        val loud = FloatArray(1000) { if (it % 2 == 0) 2.0f else -2.0f }
        val out = softSaturate(loud, drive = 0.7)
        assertTrue(absMax(out) < 1.0f, "tanh output must stay inside unit range")
        assertEquals(tanh(2.0 * 1.7).toFloat(), out[0], 1e-6f)
    }

    @Test
    fun vinylNoiseIsDeterministic() {
        assertTrue(
            vinylNoise(4096, sr).contentEquals(vinylNoise(4096, sr)),
            "the seeded RNG must produce the same noise every time",
        )
    }
}
