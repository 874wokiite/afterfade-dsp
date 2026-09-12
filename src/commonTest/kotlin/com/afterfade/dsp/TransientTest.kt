package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransientTest {

    @Test
    fun findEventEndStopsAtTheDecayThreshold() {
        // A 100 ms burst followed by silence must end shortly after the burst.
        val sr = 44100
        val y = FloatArray(sr)
        for (i in 0 until sr / 10) y[i] = (0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sr)).toFloat()
        val end = findEventEnd(y, 0, y.size, sr)
        assertTrue(end in (sr / 10)..(sr / 10 + sr / 100), "expected end near 0.1s, got ${end / sr.toDouble()}s")
    }

    @Test
    fun findEventEndReturnsLimitForShortWindows() {
        val y = FloatArray(100) { 0.1f }
        assertEquals(50, findEventEnd(y, 0, 50, 44100), "a window shorter than 2 frames returns the limit")
    }

    @Test
    fun pickOnsetsReturnsNothingForSilence() {
        assertEquals(0, pickOnsets(FloatArray(200)).size)
        assertEquals(0, pickOnsets(FloatArray(0)).size)
    }

    /**
     * A spoken phrase over quiet room tone: mora bursts, a geminate closure, a long final vowel —
     * the shape of 「やったぜー」. Runs 1.00 s to 2.14 s inside a 5 s clip.
     */
    private fun spokenPhrase(sr: Int = 44100): FloatArray {
        val rng = com.afterfade.dsp.Rng(1234L)
        val y = rng.gaussianNoise(sr * 5, 0.004)
        fun mora(t0: Double, dur: Double, f0: Double, amp: Double) {
            val i0 = (t0 * sr).toInt()
            val n = (dur * sr).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = minOf(1.0, t / 0.02) * kotlin.math.exp(-t * 1.2)
                var sig = 0.0
                for (k in 1..4) sig += kotlin.math.sin(2.0 * kotlin.math.PI * f0 * k * t) / k
                y[i0 + i] += (amp * env * sig).toFloat()
            }
        }
        mora(1.00, 0.16, 180.0, 0.30) // や
        mora(1.30, 0.16, 190.0, 0.32) // た  (0.14 s closure before it — the っ)
        mora(1.46, 0.18, 175.0, 0.30) // ぜ
        mora(1.64, 0.50, 165.0, 0.28) // ー
        return y
    }

    @Test
    fun aSpokenPhraseComesOutAsOneEventNotOneSyllable() {
        val sr = 44100
        val events = detectTransients(spokenPhrase(sr), sr)
        val phrase = events.filter { it.onsetSec > 0.9 && it.onsetSec < 1.2 }
        assertEquals(1, phrase.size, "the phrase must not be split across its mora onsets")

        val durationSec = phrase[0].audio.size.toDouble() / sr
        // The voice runs 1.00 s -> 2.14 s. Anything under ~0.9 s means it was cut mid-phrase.
        assertTrue(durationSec > 0.9, "phrase was cut short at ${durationSec}s")
        assertTrue(durationSec < 1.5, "phrase ran past the end of the voice: ${durationSec}s")

        // and no mora inside the phrase started an event of its own
        assertTrue(
            events.none { it.onsetSec > 1.05 && it.onsetSec < 2.1 },
            "onsets inside the phrase must be absorbed, got ${events.map { it.onsetSec }}",
        )
    }

    @Test
    fun quietTextureIsStillSlicedAtEveryOnset() {
        // Clicks 200 ms apart at a level close to the surrounding noise: no onset is prominent
        // enough for the sustained path, so each one stays its own short event.
        val sr = 44100
        val rng = com.afterfade.dsp.Rng(7L)
        val y = rng.gaussianNoise(sr * 3, 0.02)
        for (c in 1..8) {
            val i0 = (c * 0.3 * sr).toInt()
            for (i in 0 until sr / 50) {
                val t = i.toDouble() / sr
                y[i0 + i] += (0.06 * kotlin.math.exp(-t * 60.0) * kotlin.math.sin(2.0 * kotlin.math.PI * 900.0 * t)).toFloat()
            }
        }
        val events = detectTransients(y, sr)
        assertTrue(events.size >= 6, "expected the clicks to stay separate, got ${events.size} events")
        val longest = events.maxOf { it.audio.size }.toDouble() / sr
        assertTrue(longest < 0.5, "texture events should stay short, longest was ${longest}s")
    }

    @Test
    fun melFrequenciesAreMonotonic() {
        val edges = melFrequencies(64, 0.0, 22050.0)
        assertEquals(66, edges.size)
        assertEquals(0.0, edges[0], 1e-9)
        assertEquals(22050.0, edges[65], 1e-6)
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1], "mel edges must increase")
    }
}
