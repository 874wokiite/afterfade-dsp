package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyTest {

    private val sr = 44100

    /** Frequency of a MIDI note without `pow`: A4 = 440 Hz scaled by an exact semitone ratio. */
    private fun midiHz(midi: Int): Double = 440.0 * semitoneRatio(midi - 69)

    /**
     * One note: fundamental plus two harmonics, with a short fade in and out so the joins between
     * notes do not add a broadband click to the chroma.
     */
    private fun note(midi: Int, seconds: Double, amplitude: Double = 0.5): FloatArray {
        val n = (sr * seconds).toInt()
        val hz = midiHz(midi)
        val fade = minOf(n / 8, sr / 200)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            var v = sin(2.0 * PI * hz * t) +
                0.5 * sin(2.0 * PI * 2 * hz * t) +
                0.25 * sin(2.0 * PI * 3 * hz * t)
            v *= amplitude
            val env = when {
                fade <= 0 -> 1.0
                i < fade -> i / fade.toDouble()
                i >= n - fade -> (n - 1 - i) / fade.toDouble()
                else -> 1.0
            }
            (v * env).toFloat()
        }
    }

    /** Concatenate notes given as `midi to seconds` pairs, each with its own amplitude. */
    private fun melody(vararg notes: Triple<Int, Double, Double>): FloatArray {
        val parts = notes.map { (midi, sec, amp) -> note(midi, sec, amp) }
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    @Test
    fun chromaOfASingleNoteConcentratesOnItsPitchClass() {
        val c = chroma(note(60, 1.0), sr) // C4
        assertEquals(1.0, c[0], 1e-12, "C should be the strongest pitch class")
        for (pc in 1 until 12) {
            if (pc == 7) continue // the third harmonic is a G
            assertTrue(c[pc] < 0.5, "pitch class $pc should be weak, got ${c[pc]}")
        }
    }

    @Test
    fun chromaOfSilenceIsAllZeros() {
        val c = chroma(FloatArray(sr), sr)
        assertEquals(12, c.size)
        for (v in c) assertEquals(0.0, v)
    }

    @Test
    fun cMajorScaleIsIdentifiedAsCMajor() {
        // C D E F G A B C, tonic and dominant held longer and louder so the relative minor
        // (A minor, same seven notes) does not win.
        val y = melody(
            Triple(60, 0.5, 0.8),  // C4, tonic
            Triple(62, 0.25, 0.5),
            Triple(64, 0.25, 0.5),
            Triple(65, 0.25, 0.5),
            Triple(67, 0.5, 0.7),  // G4, dominant
            Triple(69, 0.25, 0.5),
            Triple(71, 0.25, 0.5),
            Triple(72, 0.5, 0.8),  // C5, tonic
        )
        val key = assertNotNull(estimateKey(y, sr))
        assertEquals("C", key.tonic, "got $key")
        assertEquals("major", key.scale, "got $key")
        assertTrue(key.confidence > 0.0, "confidence should be positive, got $key")
    }

    @Test
    fun aNaturalMinorScaleIsIdentifiedAsAMinor() {
        // A B C D E F G A, tonic and dominant emphasised the same way.
        val y = melody(
            Triple(57, 0.5, 0.8),  // A3, tonic
            Triple(59, 0.25, 0.5),
            Triple(60, 0.25, 0.5),
            Triple(62, 0.25, 0.5),
            Triple(64, 0.5, 0.7),  // E4, dominant
            Triple(65, 0.25, 0.5),
            Triple(67, 0.25, 0.5),
            Triple(69, 0.5, 0.8),  // A4, tonic
        )
        val key = assertNotNull(estimateKey(y, sr))
        assertEquals("A", key.tonic, "got $key")
        assertEquals("minor", key.scale, "got $key")
    }

    @Test
    fun gMajorArpeggioIsIdentifiedAsGMajor() {
        // G B D G, four times.
        val bar = arrayOf(
            Triple(55, 0.25, 0.8),
            Triple(59, 0.25, 0.5),
            Triple(62, 0.25, 0.5),
            Triple(67, 0.25, 0.6),
        )
        val y = melody(*Array(4) { bar }.flatMap { it.asList() }.toTypedArray())
        val key = assertNotNull(estimateKey(y, sr))
        assertEquals("G", key.tonic, "got $key")
        assertEquals("major", key.scale, "got $key")
    }

    @Test
    fun silenceHasNoKey() {
        assertNull(estimateKey(FloatArray(sr), sr))
        assertNull(estimateKey(FloatArray(0), sr))
    }

    @Test
    fun estimateKeyOutputFeedsPitchShiftToKey() {
        val reference = melody(
            Triple(60, 0.5, 0.8),
            Triple(64, 0.25, 0.5),
            Triple(67, 0.5, 0.7),
            Triple(72, 0.5, 0.8),
        )
        val key = assertNotNull(estimateKey(reference, sr))
        val other = note(58, 1.0) // A#3, off the key
        val shifted = pitchShiftToKey(other, sr, key.tonic, key.scale)
        assertEquals(other.size, shifted.size)
        assertTrue(shifted.any { it != 0f }, "shifted audio should not be silent")
    }
}
