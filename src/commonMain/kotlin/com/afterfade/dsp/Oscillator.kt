package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase-accumulating oscillators and frequency curves — the raw material for synthesised drums,
 * basses and leads.
 *
 * Every oscillator takes a per-sample frequency array, so a pitch drop (kick), a glide (808,
 * portamento lead) and a fixed note all go through the same code. Which curve and which numbers
 * make "a kick" is a recipe and is left to the caller.
 *
 * 位相を積算するオシレーターと周波数カーブ。音色の数値はレシピなので呼び出し側に任せる。
 */

/** Oscillator shapes. */
enum class Waveform {
    SINE,

    /** Band-limited with PolyBLEP so high notes do not alias. */
    SAW,

    /** Naive [pulse] wave: the hard-edged, aliasing chip sound. The duty cycle is a parameter. */
    PULSE,
}

/**
 * Frequency of MIDI note [midi] in Hz, A4 (69) = 440 Hz.
 *
 * Built on [semitoneRatio], so the same note gives the same bits on every platform.
 */
fun midiToHz(midi: Int): Double = 440.0 * semitoneRatio(midi - 69)

/**
 * Oscillator following the per-sample frequency curve [freqHz], one output sample per entry.
 *
 * [phase] is the starting phase in cycles (0 until 1). [duty] is the high fraction of each
 * cycle for [Waveform.PULSE] (0.5 = square) and is ignored by the other shapes. Amplitude is ±1.
 */
fun oscillator(
    freqHz: DoubleArray,
    sr: Int,
    wave: Waveform = Waveform.SINE,
    phase: Double = 0.0,
    duty: Double = 0.5,
): FloatArray {
    require(sr > 0) { "sr must be positive, was $sr" }
    val out = FloatArray(freqHz.size)
    var p = phase - floor(phase)
    for (i in freqHz.indices) {
        val dt = freqHz[i] / sr
        out[i] = when (wave) {
            Waveform.SINE -> sin(2.0 * PI * p)
            Waveform.SAW -> 2.0 * p - 1.0 - polyBlep(p, dt)
            Waveform.PULSE -> pulse(p, duty)
        }.toFloat()
        p += dt
        p -= floor(p)
    }
    return out
}

/** [oscillator] at the fixed frequency [freqHz] for [length] samples. */
fun oscillator(
    freqHz: Double,
    length: Int,
    sr: Int,
    wave: Waveform = Waveform.SINE,
    phase: Double = 0.0,
    duty: Double = 0.5,
): FloatArray = oscillator(DoubleArray(length) { freqHz }, sr, wave, phase, duty)

/**
 * Detuned stack of [voices] saws around [freqHz], normalised so the peak stays near ±1.
 *
 * [detune] is the outermost voice's offset as a fraction of the frequency (0.01 = ±1 %); the
 * other voices spread evenly between. It is a ratio rather than cents so no `pow` is involved.
 * Start phases are drawn from [seed], so the same call gives the same samples.
 */
fun supersaw(
    freqHz: DoubleArray,
    sr: Int,
    voices: Int = 7,
    detune: Double = 0.01,
    seed: Long = 0L,
): FloatArray {
    require(voices >= 1) { "voices must be at least 1, was $voices" }
    val rng = Rng(seed)
    val out = FloatArray(freqHz.size)
    for (v in 0 until voices) {
        val spread = if (voices == 1) 0.0 else -1.0 + 2.0 * v / (voices - 1)
        val ratio = 1.0 + detune * spread
        val voice = oscillator(DoubleArray(freqHz.size) { freqHz[it] * ratio }, sr, Waveform.SAW, rng.nextDouble())
        for (i in out.indices) out[i] += voice[i]
    }
    // Uncorrelated voices add roughly as sqrt(voices); dividing by that keeps the level steady
    // while the stack still sounds wider as voices are added.
    val norm = (1.0 / sqrt(voices.toDouble())).toFloat()
    for (i in out.indices) out[i] *= norm
    return out
}

/**
 * Exponential frequency drop from [startHz] towards [endHz]: `end + (start - end) * e^(-rate·t)`.
 *
 * The body of a synthesised kick or tom is a sine following this curve.
 */
fun expSweep(startHz: Double, endHz: Double, ratePerSec: Double, length: Int, sr: Int): DoubleArray =
    DoubleArray(length) { endHz + (startHz - endHz) * exp(-ratePerSec * it / sr) }

/**
 * Hold [fromHz], then slide linearly to [toHz] over [glideSec] starting at sample [startAt],
 * then hold [toHz]. A linear slide in Hz keeps the curve free of `pow`/`log`.
 */
fun glide(fromHz: Double, toHz: Double, glideSec: Double, length: Int, sr: Int, startAt: Int = 0): DoubleArray {
    val glideLen = maxOf(1, (glideSec * sr).toInt())
    return DoubleArray(length) {
        when {
            it < startAt -> fromHz
            it >= startAt + glideLen -> toHz
            else -> fromHz + (toHz - fromHz) * (it - startAt) / glideLen
        }
    }
}

/** Two-sample polynomial correction around the saw's discontinuity at phase 0. */
private fun polyBlep(p: Double, dt: Double): Double = when {
    dt <= 0.0 -> 0.0
    p < dt -> {
        val t = p / dt
        t + t - t * t - 1.0
    }
    p > 1.0 - dt -> {
        val t = (p - 1.0) / dt
        t * t + t + t + 1.0
    }
    else -> 0.0
}
