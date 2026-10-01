package com.afterfade.dsp

import kotlin.math.exp

/**
 * Amplitude envelopes. Each returns a gain curve of the requested length; multiply it into a
 * signal with [applyEnvelope].
 *
 * 振幅エンベロープ。長さ分のゲイン曲線を返し、[applyEnvelope] で信号に掛ける。
 */

/**
 * Linear ADSR over [length] samples. The note is held until the release, which occupies the last
 * [releaseSec] of the buffer; attack and decay are cut short if the buffer is too short for them.
 */
fun adsr(
    length: Int,
    sr: Int,
    attackSec: Double,
    decaySec: Double,
    sustain: Double,
    releaseSec: Double,
): FloatArray {
    val a = (attackSec * sr).toInt()
    val d = (decaySec * sr).toInt()
    val r = minOf((releaseSec * sr).toInt(), length)
    val releaseStart = length - r
    // Level reached just before the release, so a release that starts mid-attack does not jump.
    fun held(i: Int): Double = when {
        i < a -> i.toDouble() / a
        i < a + d -> 1.0 - (1.0 - sustain) * (i - a) / d
        else -> sustain
    }
    val releaseFrom = held(releaseStart)
    return FloatArray(length) {
        if (it < releaseStart) held(it).toFloat()
        else (releaseFrom * (length - it) / r).toFloat()
    }
}

/** `e^(-rate·t)` over [length] samples: the decay of a plucked or struck sound. */
fun expDecay(length: Int, sr: Int, ratePerSec: Double): FloatArray =
    FloatArray(length) { exp(-ratePerSec * it / sr).toFloat() }

/**
 * [y] multiplied sample by sample by [envelope]. The result has [y]'s length; samples beyond the
 * end of [envelope] are silenced.
 */
fun applyEnvelope(y: FloatArray, envelope: FloatArray): FloatArray =
    FloatArray(y.size) { if (it < envelope.size) y[it] * envelope[it] else 0f }
