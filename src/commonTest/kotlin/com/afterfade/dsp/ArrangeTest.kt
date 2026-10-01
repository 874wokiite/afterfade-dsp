package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArrangeTest {

    private val sr = 44100

    @Test
    fun fadeRampsInAndOut() {
        val y = fade(FloatArray(1000) { 1f }, 1000, inSec = 0.1, outSec = 0.2)
        assertEquals(0f, y[0])
        assertEquals(0.5f, y[50], 1e-6f)
        assertEquals(1f, y[500])
        assertEquals(0.5f, y[900], 1e-6f)
        assertEquals(0.005f, y[999], 1e-6f)
    }

    @Test
    fun reverseFlips() {
        assertContentEquals(floatArrayOf(3f, 2f, 1f), reverse(floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun feedbackDelayRepeatsAndDecays() {
        val impulse = FloatArray(1000).also { it[0] = 1f }
        val y = feedbackDelay(impulse, 1000, delaySec = 0.1, feedback = 0.5, wet = 1.0)
        assertEquals(1f, y[0])
        assertEquals(1f, y[100])
        assertEquals(0.5f, y[200], 1e-6f)
        assertEquals(0.25f, y[300], 1e-6f)
        assertEquals(0f, y[150])
    }

    @Test
    fun lowpassSweepOpensUp() {
        // White noise through a lowpass sweeping 200 Hz -> 10 kHz gets brighter over time.
        val noise = Rng(5).gaussianNoise(sr * 2, 0.3)
        val y = filterSweep(noise, sr, 200.0, 10000.0)
        val early = spectralCentroid(y.copyOfRange(0, sr / 4), sr)
        val late = spectralCentroid(y.copyOfRange(sr * 7 / 4, sr * 2), sr)
        assertTrue(late > early * 3, "centroid should rise (early=$early late=$late)")
    }

    @Test
    fun highpassSweepRemovesLows() {
        val bass = oscillator(60.0, sr, sr)
        val y = filterSweep(bass, sr, 2000.0, 4000.0, FilterType.HIGHPASS)
        assertTrue(absMax(y.copyOfRange(sr / 2, sr)) < 0.01f)
    }

    @Test
    fun pulseDutyCycle() {
        val quarter = (0 until 1000).count { pulse(it / 1000.0, 0.25) > 0 }
        assertEquals(250, quarter)
        val wave = oscillator(441.0, sr, sr, Waveform.PULSE, duty = 0.25)
        assertEquals(0.25, wave.count { it > 0f }.toDouble() / sr, 0.01)
    }
}
