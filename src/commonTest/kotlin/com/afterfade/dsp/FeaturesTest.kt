package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class FeaturesTest {

    private val sr = 44100

    @Test
    fun rmsOfAFullScaleSquareWaveIsOne() {
        val square = FloatArray(1000) { if (it % 2 == 0) 1f else -1f }
        assertEquals(1f, rms(square), 1e-6f)
    }

    @Test
    fun rmsOfSilenceIsZero() {
        assertEquals(0f, rms(FloatArray(1000)))
        assertEquals(0f, rms(FloatArray(0)))
    }

    @Test
    fun zeroCrossingRateOfA100HzSquareWave() {
        // 100 Hz means 200 sign changes per second of audio.
        val square = FloatArray(sr) { if (sin(2.0 * PI * 100.0 * it / sr) >= 0.0) 1f else -1f }
        assertEquals(200f / sr, zeroCrossingRate(square), 1e-4f)
    }

    @Test
    fun zeroCrossingRateOfSilenceIsZero() {
        assertEquals(0f, zeroCrossingRate(FloatArray(1000)))
        assertEquals(0f, zeroCrossingRate(FloatArray(1)))
    }

    @Test
    fun spectralCentroidOfASineSitsOnItsFrequency() {
        val tone = FloatArray(sr) { (0.5 * sin(2.0 * PI * 1000.0 * it / sr)).toFloat() }
        assertEquals(1000.0, spectralCentroid(tone, sr), 50.0)
    }

    @Test
    fun spectralCentroidOfSilenceIsZero() {
        assertEquals(0.0, spectralCentroid(FloatArray(4096), sr))
        assertEquals(0.0, spectralCentroid(FloatArray(0), sr))
    }
}
