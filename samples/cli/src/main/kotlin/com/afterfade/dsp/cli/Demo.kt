package com.afterfade.dsp.cli

import com.afterfade.dsp.FilterType
import com.afterfade.dsp.Rng
import com.afterfade.dsp.addAt
import com.afterfade.dsp.butterworth
import com.afterfade.dsp.butterworthBandpass
import com.afterfade.dsp.peakNormalized
import com.afterfade.dsp.semitoneRatio
import com.afterfade.dsp.softSaturate
import com.afterfade.dsp.sosfilt
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A rendered mono track. */
class Track(val samples: FloatArray, val sampleRate: Int)

/**
 * The demo track the README hero image is drawn from: eight seconds at 44.1 kHz, built entirely
 * from library parts, and identical every time because every random number comes from [Rng].
 *
 * Three layers: a low, filtered noise bed that breathes; a kick and clap pattern at 96 BPM with
 * two off-beat hits per bar; and a sustained D minor pad (D-F-A) with a short melody over it.
 */
object Demo {

    const val SAMPLE_RATE = 44100
    const val SECONDS = 8.0
    const val BPM = 96.0

    /** Kicks, in beats inside a four-beat bar. 1.5 and 3.25 are the off-beats. */
    private val KICK_BEATS = doubleArrayOf(0.0, 1.5, 2.0, 3.25)

    /** Claps on the backbeat. */
    private val CLAP_BEATS = doubleArrayOf(1.0, 3.0)

    /** D minor triad plus the octave, as semitones away from A4 = 440 Hz. */
    private val PAD_SEMITONES = intArrayOf(-19, -16, -12, -7) // D3, F3, A3, D4

    /** A short melody inside the same triad: A4, F4, D4, A4 — one note per half bar. */
    private val MELODY_SEMITONES = intArrayOf(0, -4, -7, 0)

    fun render(): Track {
        val sr = SAMPLE_RATE
        val n = (sr * SECONDS).toInt()
        val mix = FloatArray(n)

        bed(mix, sr, n)
        pad(mix, sr, n)
        melody(mix, sr, n)
        drums(mix, sr, n)

        val saturated = softSaturate(mix, drive = 0.2)
        return Track(peakNormalized(saturated, 0.9f), sr)
    }

    // --- layers ----------------------------------------------------------------------------

    /** Low-passed gaussian noise, swelling twice over the eight seconds. */
    private fun bed(mix: FloatArray, sr: Int, n: Int) {
        val noise = Rng(1729L).gaussianNoise(n, 0.6)
        val low = sosfilt(butterworth(4, 620.0, sr, FilterType.LOWPASS), noise)
        // A touch of air on top so the spectrogram is not empty above 1 kHz.
        val air = sosfilt(butterworthBandpass(2, 2600.0, 9000.0, sr), Rng(2024L).gaussianNoise(n, 0.25))
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val swell = 0.55 + 0.45 * sin(2.0 * PI * 0.125 * t - PI / 2.0)
            mix[i] += (low[i] * 0.55 * swell + air[i] * 0.055 * swell).toFloat()
        }
    }

    /** The sustained D minor triad, entering note by note. */
    private fun pad(mix: FloatArray, sr: Int, n: Int) {
        val starts = doubleArrayOf(0.0, 0.0, 1.25, 2.5) // the top voice arrives last
        for ((voice, semitones) in PAD_SEMITONES.withIndex()) {
            val startSec = starts[voice]
            val startSample = (startSec * sr).toInt()
            val lengthSec = SECONDS - startSec
            val tone = tone(
                sr = sr,
                freq = 440.0 * semitoneRatio(semitones),
                seconds = lengthSec,
                amplitude = 0.20 - voice * 0.02,
                attackSec = 0.8,
                releaseSec = 1.6,
            )
            addAt(mix, tone, startSample)
        }
    }

    /** Four sustained melody notes, one every two beats from the second bar on. */
    private fun melody(mix: FloatArray, sr: Int, n: Int) {
        val beat = 60.0 / BPM
        var at = 4.0 * beat // one bar in
        for ((i, semitones) in MELODY_SEMITONES.withIndex()) {
            val lengthSec = 1.6 * beat
            if (at * sr >= n) break
            val tone = tone(
                sr = sr,
                freq = 440.0 * semitoneRatio(semitones),
                seconds = lengthSec,
                amplitude = 0.16,
                attackSec = 0.06,
                releaseSec = 0.5,
            )
            addAt(mix, tone, (at * sr).toInt())
            at += 2.0 * beat * (if (i == 1) 1.5 else 1.0) // one held note, so it is not a metronome
        }
    }

    /** Kicks and claps, bar after bar, until the track runs out. */
    private fun drums(mix: FloatArray, sr: Int, n: Int) {
        val beat = 60.0 / BPM
        val bar = 4.0 * beat
        val kick = kick(sr)
        var barIndex = 0
        while (barIndex * bar < SECONDS) {
            val barStart = barIndex * bar
            for (b in KICK_BEATS) {
                val at = ((barStart + b * beat) * sr).toInt()
                if (at < n) addAt(mix, kick, at)
            }
            for ((i, b) in CLAP_BEATS.withIndex()) {
                val at = ((barStart + b * beat) * sr).toInt()
                // A different seed per hit so no two claps are bit-identical, but always the same.
                if (at < n) addAt(mix, clap(sr, seed = 500L + barIndex * 10L + i), at)
            }
            barIndex++
        }
    }

    // --- voices ----------------------------------------------------------------------------

    /** Sine with three harmonics and a raised-cosine attack/release — the pad and melody voice. */
    private fun tone(
        sr: Int,
        freq: Double,
        seconds: Double,
        amplitude: Double,
        attackSec: Double,
        releaseSec: Double,
    ): FloatArray {
        val len = (seconds * sr).toInt()
        val out = FloatArray(len)
        val partials = doubleArrayOf(1.0, 0.32, 0.14, 0.06)
        val attack = (attackSec * sr).toInt().coerceAtLeast(1)
        val release = (releaseSec * sr).toInt().coerceAtLeast(1)
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            var v = 0.0
            for ((h, gain) in partials.withIndex()) {
                val f = freq * (h + 1)
                if (f >= sr / 2.0) break
                v += gain * sin(2.0 * PI * f * t)
            }
            // Slow vibrato keeps the tone from sounding like a test signal.
            v *= 1.0 + 0.04 * sin(2.0 * PI * 4.7 * t)
            var env = 1.0
            if (i < attack) env *= 0.5 - 0.5 * kotlin.math.cos(PI * i / attack)
            val fromEnd = len - 1 - i
            if (fromEnd < release) env *= 0.5 - 0.5 * kotlin.math.cos(PI * fromEnd / release)
            out[i] = (v * env * amplitude).toFloat()
        }
        return out
    }

    /** Short decaying sine sweeping 138 Hz down to 48 Hz, with a click so the onset is crisp. */
    private fun kick(sr: Int): FloatArray {
        val len = (0.30 * sr).toInt()
        val out = FloatArray(len)
        val click = sosfilt(butterworthBandpass(2, 1200.0, 6000.0, sr), Rng(99L).gaussianNoise(len, 1.0))
        var phase = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            val f = 48.0 + 90.0 * exp(-t / 0.035)
            phase += 2.0 * PI * f / sr
            val body = sin(phase) * exp(-t / 0.085) * (1.0 - exp(-t / 0.002))
            val tick = click[i] * exp(-t / 0.006) * 0.30
            out[i] = ((body * 0.85 + tick) * 0.9).toFloat()
        }
        return out
    }

    /** Band-passed noise burst with a fast decay — the backbeat. */
    private fun clap(sr: Int, seed: Long): FloatArray {
        val len = (0.20 * sr).toInt()
        val noise = Rng(seed).gaussianNoise(len, 1.0)
        val band = sosfilt(butterworthBandpass(2, 900.0, 5600.0, sr), noise)
        val out = FloatArray(len)
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            // Two quick repeats before the tail, the way a hand clap actually arrives.
            val env = exp(-t / 0.045) + 0.5 * exp(-((t - 0.011) / 0.020).let { it * it })
            out[i] = (band[i] * env * 0.22).toFloat()
        }
        return out
    }
}
