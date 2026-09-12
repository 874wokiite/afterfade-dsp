package com.afterfade.dsp

import kotlin.math.sqrt

/**
 * Single-number descriptions of a buffer: loudness, noisiness and brightness.
 *
 * Each function takes a whole buffer and returns one value, which is what a meter, a threshold or
 * a "is this speech or noise" decision needs. For a value per frame, call them on slices.
 */

/**
 * Root mean square level of [y] — the usual loudness measure for a buffer.
 *
 * Full-scale square wave is `1`, silence and an empty array are `0`. The sum is accumulated in
 * float64 so long buffers do not lose precision, and the result is returned as float32.
 */
fun rms(y: FloatArray): Float {
    if (y.isEmpty()) return 0f
    var sum = 0.0
    for (v in y) {
        val d = v.toDouble()
        sum += d * d
    }
    return sqrt(sum / y.size).toFloat()
}

/**
 * Zero crossing rate of [y] — the fraction of adjacent sample pairs whose sign differs, in `[0, 1]`.
 *
 * High for noise and unvoiced consonants, low for a sustained tone; multiply by the sample rate to
 * read it as crossings per second. Zero is counted as non-negative, so a constant signal (silence
 * included) gives `0`; fewer than two samples also gives `0`.
 */
fun zeroCrossingRate(y: FloatArray): Float {
    if (y.size < 2) return 0f
    var crossings = 0
    for (i in 0 until y.size - 1) {
        if ((y[i] < 0f) != (y[i + 1] < 0f)) crossings++
    }
    return crossings.toFloat() / (y.size - 1)
}

/**
 * Spectral centroid of [y] in Hz — the magnitude-weighted mean frequency, i.e. the "brightness"
 * of the sound.
 *
 * One [nFft]-point FFT of the start of [y], windowed with [hanning]; shorter input is zero padded.
 * Returns `0.0` for silence (and for an empty buffer), where the weighted mean is undefined.
 */
fun spectralCentroid(y: FloatArray, sr: Int, nFft: Int = 2048): Double {
    require(nFft > 0) { "nFft must be positive, got $nFft" }
    if (y.isEmpty()) return 0.0

    val window = hanning(nFft)
    val frame = DoubleArray(nFft) { if (it < y.size) y[it].toDouble() * window[it] else 0.0 }
    val spec = Fft.rfft(frame)
    val freqs = rfftFreq(nFft, sr)

    var weighted = 0.0
    var total = 0.0
    for (k in 0 until spec.size) {
        val mag = sqrt(spec.re[k] * spec.re[k] + spec.im[k] * spec.im[k])
        weighted += freqs[k] * mag
        total += mag
    }
    if (total <= 0.0) return 0.0
    return weighted / total
}
