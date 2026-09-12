package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PitchTest {

    private val sr = 44100

    @Test
    fun estimatePitchReturnsNullForTooShortInput() {
        assertNull(estimatePitch(FloatArray(4), sr))
    }

    @Test
    fun nearestScaleSemitonesMatchesPython() {
        // meta.json -> nearest_scale_semitones_tone.shifts
        val f0 = 466.1760174361286
        val expected = mapOf(
            ("C" to "major") to -1,
            ("A" to "minor") to -1,
            ("Eb" to "major") to 0,
            ("G" to "major") to -1,
            ("D" to "minor") to 0,
        )
        for ((keyScale, shift) in expected) {
            val (key, scale) = keyScale
            assertEquals(shift, nearestScaleSemitones(f0, key, scale), "shift for $key $scale")
        }
    }

    @Test
    fun pitchShiftMovesThePitchInTheDirectionAsked() {
        // A 440 Hz tone shifted up 12 semitones must come back near 880 Hz, not 220 Hz.
        val y = FloatArray(sr) { (0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * it / sr)).toFloat() }
        val up = estimatePitch(pitchShift(y, sr, 12), sr)
        val down = estimatePitch(pitchShift(y, sr, -12), sr)
        assertNotNull(up)
        assertNotNull(down)
        assertTrue(up in 800.0..960.0, "+12 semitones should land near 880 Hz, got $up")
        assertTrue(down in 200.0..240.0, "-12 semitones should land near 220 Hz, got $down")
    }

    @Test
    fun pitchShiftKeepsTheWholeAccentAtEveryShift() {
        // A marker burst 1.70 s into a 2 s accent has to survive every shift the key correction
        // can ask for (-6..+6); the old implementation dropped it from +3 upwards.
        val n = sr * 2
        for (steps in -6..6) {
            if (steps == 0) continue
            val y = FloatArray(n)
            for (i in (sr * 17 / 10) until (sr * 18 / 10)) {
                y[i] = (0.8 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sr)).toFloat()
            }
            val out = pitchShift(y, sr, steps)
            assertEquals(n, out.size, "shift by $steps changed the accent length")
            var peak = 0f
            for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            assertTrue(peak > 0.3f, "shift by $steps lost the tail of the accent (peak $peak)")
        }
    }

    @Test
    fun nearestScaleSemitonesIsZeroForInScaleNotes() {
        // A440 is the 6th degree of C major and the root of A minor — no shift needed either way.
        assertEquals(0, nearestScaleSemitones(440.0, "C", "major"))
        assertEquals(0, nearestScaleSemitones(440.0, "A", "minor"))
        // Flat key names are normalised through FLAT_TO_SHARP.
        assertEquals(nearestScaleSemitones(440.0, "D#", "major"), nearestScaleSemitones(440.0, "Eb", "major"))
    }
}
