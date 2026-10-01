package com.afterfade.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SynthTest {

    private val sr = 44100

    // ---- Oscillator ----

    @Test
    fun midiToHzHitsA4AndOctaves() {
        assertEquals(440.0, midiToHz(69))
        assertEquals(880.0, midiToHz(81))
        assertEquals(220.0, midiToHz(57))
        assertEquals(261.6255653005986, midiToHz(60), 1e-9)
    }

    @Test
    fun sineHasTheRequestedPitch() {
        val y = oscillator(441.0, sr, sr)
        assertEquals(441.0, estimatePitch(y, sr)!!, 1.0)
        assertTrue(absMax(y) <= 1f)
    }

    @Test
    fun sawHasTheRequestedPitchAndStaysBounded() {
        val y = oscillator(220.0, sr, sr, Waveform.SAW)
        assertEquals(220.0, estimatePitch(y, sr)!!, 1.0)
        assertTrue(absMax(y) <= 1.01f, "PolyBLEP must not overshoot noticeably, was ${absMax(y)}")
    }

    @Test
    fun supersawIsDeterministicAndBounded() {
        val f = DoubleArray(sr) { 110.0 }
        val a = supersaw(f, sr, seed = 3)
        assertContentEquals(a, supersaw(f, sr, seed = 3))
        assertTrue(absMax(a) < 3f)
        assertTrue(!a.contentEquals(supersaw(f, sr, seed = 4)), "another seed must change the phases")
    }

    @Test
    fun expSweepStartsAtStartAndApproachesEnd() {
        val c = expSweep(160.0, 45.0, 30.0, sr, sr)
        assertEquals(160.0, c[0])
        assertEquals(45.0, c.last(), 1e-6)
        assertTrue((1 until c.size).all { c[it] <= c[it - 1] })
    }

    @Test
    fun glideHoldsSlidesAndHolds() {
        val c = glide(100.0, 200.0, 0.01, 2000, sr, startAt = 500)
        assertEquals(100.0, c[499])
        assertEquals(150.0, c[500 + 441 / 2], 0.2)
        assertEquals(200.0, c[1999])
    }

    // ---- Envelope ----

    @Test
    fun adsrShape() {
        val e = adsr(1000, 1000, attackSec = 0.1, decaySec = 0.1, sustain = 0.5, releaseSec = 0.2)
        assertEquals(0f, e[0])
        assertEquals(1f, e[100])
        assertEquals(0.5f, e[500])
        assertEquals(0.25f, e[900], 1e-6f)
        assertEquals(0.0025f, e[999], 1e-6f)
    }

    @Test
    fun adsrReleaseDuringAttackDoesNotJump() {
        val e = adsr(100, 1000, attackSec = 0.2, decaySec = 0.0, sustain = 1.0, releaseSec = 0.05)
        assertTrue(abs(e[50] - e[49]) < 0.05f)
    }

    @Test
    fun expDecayAndApplyEnvelope() {
        val e = expDecay(sr, sr, 2.0)
        assertEquals(1f, e[0])
        assertEquals(0.36788f, e[sr / 2], 1e-4f)
        val y = applyEnvelope(FloatArray(10) { 2f }, FloatArray(5) { 0.5f })
        assertContentEquals(FloatArray(5) { 1f } + FloatArray(5), y)
    }

    // ---- Sequencer ----

    @Test
    fun stepPositionsStraightAndSwung() {
        // 120 BPM, 16ths: one step = 0.125 s = 5512.5 samples
        assertContentEquals(intArrayOf(0, 5512, 11025, 16537), stepPositions(120.0, sr, 4))
        val swung = stepPositions(120.0, sr, 4, swing = 2.0 / 3.0)
        assertEquals(7350, swung[1])
        assertEquals(11025, swung[2])
    }

    @Test
    fun parsePatternIgnoresSeparators() {
        assertContentEquals(
            booleanArrayOf(true, false, false, false, true, false, true, false),
            parsePattern("x... | X-x."),
        )
        assertFailsWith<IllegalArgumentException> { parsePattern("x.o.") }
    }

    // ---- Dynamics ----

    @Test
    fun gainMixAndHardClip() {
        assertContentEquals(floatArrayOf(1f, -1f), gain(floatArrayOf(0.5f, -0.5f), 2.0))
        assertContentEquals(floatArrayOf(2f, 2f, 1f), mix(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f)))
        assertContentEquals(floatArrayOf(0.5f, -0.5f, 0.2f), hardClip(floatArrayOf(3f, -3f, 0.2f), 0.5f))
    }

    @Test
    fun sidechainDuckDipsAndRecovers() {
        val y = sidechainDuck(FloatArray(400) { 1f }, intArrayOf(100), 1000, depth = 0.8, releaseSec = 0.1)
        assertEquals(1f, y[99])
        assertEquals(0.2f, y[100], 1e-6f)
        assertEquals(0.6f, y[150], 1e-6f)
        assertEquals(1f, y[200])
    }

    @Test
    fun compressorReducesLoudButNotQuiet() {
        val loud = oscillator(200.0, sr, sr)
        val quiet = gain(loud, 0.01)
        val c = compressor(loud, sr, thresholdDb = -20.0, ratio = 4.0)
        assertTrue(absMax(c.copyOfRange(sr / 2, sr)) < 0.4f)
        val q = compressor(quiet, sr, thresholdDb = -20.0, ratio = 4.0)
        assertContentEquals(quiet, q)
    }
}
