package com.afterfade.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempoTest {

    private val sr = 44100

    /**
     * A click track: short decaying noise bursts on every beat. Deterministic — the noise comes
     * from the library's seeded [Rng], so the same call gives the same audio on every target.
     */
    private fun clickTrack(bpm: Double, seconds: Double, seed: Long = 2024L): FloatArray {
        val rng = Rng(seed)
        val y = FloatArray((seconds * sr).toInt())
        val burstLength = sr / 50 // 20 ms
        val period = 60.0 / bpm
        var beat = 0
        while (true) {
            val start = (beat * period * sr).toInt()
            if (start + burstLength > y.size) break
            val noise = rng.gaussianNoise(burstLength, 0.5)
            for (i in 0 until burstLength) {
                val decay = exp(-12.0 * i / burstLength)
                y[start + i] += (noise[i] * decay).toFloat()
            }
            beat++
        }
        return y
    }

    private fun assertBpmNear(expected: Double, actual: Double?, tolerance: Double = 2.0) {
        assertNotNull(actual, "expected about $expected BPM, got null")
        assertTrue(
            abs(actual - expected) <= tolerance,
            "expected $expected BPM within $tolerance, got $actual",
        )
    }

    @Test
    fun estimatesAOneHundredAndTwentyBpmClickTrack() {
        assertBpmNear(120.0, estimateTempo(clickTrack(120.0, 8.0), sr))
    }

    @Test
    fun estimatesANinetyBpmClickTrack() {
        assertBpmNear(90.0, estimateTempo(clickTrack(90.0, 8.0), sr))
    }

    @Test
    fun estimatesAOneHundredAndSixtyBpmClickTrack() {
        assertBpmNear(160.0, estimateTempo(clickTrack(160.0, 8.0), sr))
    }

    @Test
    fun silenceHasNoTempo() {
        assertNull(estimateTempo(FloatArray(sr * 8), sr))
        assertNull(estimateTempoFromEnvelope(FloatArray(600), sr))
    }

    @Test
    fun tooShortInputHasNoTempo() {
        assertNull(estimateTempo(FloatArray(100) { 0.1f }, sr), "100 samples is under one frame")
        assertNull(estimateTempoFromEnvelope(FloatArray(0), sr), "an empty envelope")
        // A 10 frame envelope cannot hold a beat period at 60 BPM (86 frames at 512 hops).
        assertNull(estimateTempoFromEnvelope(FloatArray(10) { it.toFloat() }, sr))
    }

    @Test
    fun theEnvelopeAndAudioEntryPointsAgree() {
        val y = clickTrack(120.0, 8.0)
        val fromAudio = estimateTempo(y, sr)
        val fromEnvelope = estimateTempoFromEnvelope(onsetStrength(y, sr, hopLength = 512), sr)
        assertNotNull(fromAudio)
        assertEquals(fromAudio, fromEnvelope, "the wrapper must not change the answer")
    }
}
