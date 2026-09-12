package com.afterfade.dsp

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/**
 * Tempo (BPM) estimation on top of the onset strength envelope of [Transient.kt].
 *
 * The method is the classic one: autocorrelate the mean-removed onset envelope, look only at the
 * lags that correspond to a plausible tempo, and take the strongest one. A beat repeating every
 * `L` frames makes the envelope correlate with itself at lag `L`, so the lag of the peak is the
 * beat period in frames and `60 * sampleRate / (hopLength * L)` is the tempo.
 *
 * Nothing here touches the existing onset functions; this is a consumer of [onsetStrength].
 */

/** Centre of the perceptual tempo prior, in BPM. */
private const val PRIOR_CENTER_BPM = 120.0

/**
 * Width of the prior in octaves (`log2` units). 1.0 means the weight has fallen to `exp(-0.5)`
 * at 60 and 240 BPM — wide enough not to drag a genuine 90 or 160 BPM reading towards 120,
 * narrow enough to break a tie between a lag and its double.
 */
private const val PRIOR_WIDTH_OCTAVES = 1.0

private const val LN2 = 0.6931471805599453

/** Autocorrelation is meaningless once the envelope carries no variation at all. */
private const val ENERGY_EPSILON = 1e-20

/**
 * Estimates the tempo of an onset strength envelope in BPM, or `null` when it cannot be estimated.
 *
 * The envelope is mean-removed (so a constant offset does not swamp the correlation), then
 * autocorrelated for every integer lag between the beat periods of [maxBpm] and [minBpm]. Each
 * lag's correlation is multiplied by a log-Gaussian prior centred on [PRIOR_CENTER_BPM]
 * (`exp(-0.5 * (log2(bpm / 120) / 1.0)^2)`, the same shape librosa's `tempo()` uses) and the
 * strongest weighted lag wins.
 *
 * **Octave ambiguity.** A beat at `L` frames also correlates at `2L`, `3L`, … and often at `L/2`
 * when the material has off-beats, so autocorrelation alone cannot tell 75 from 150 BPM. Two
 * things decide it here: only lags inside `[minBpm, maxBpm]` are considered at all, and among
 * those the prior pulls the answer towards a comfortable walking tempo. A track that really is at
 * 60 or 190 BPM still wins, because its true lag correlates far more strongly than its octaves;
 * the prior only matters when two octaves are nearly tied. Narrow the [minBpm]/[maxBpm] range if
 * you know the material and want the ambiguity removed outright.
 *
 * The winning lag is refined by fitting a parabola through the weighted scores of its two
 * neighbours, which recovers the fractional part of the beat period — at 512-sample hops one lag
 * step is already several BPM wide near 160 BPM, so this matters. The returned BPM is clamped to
 * `[minBpm, maxBpm]`.
 *
 * Returns `null` when [onsetEnvelope] is empty, when it is shorter than one beat period at
 * [minBpm] (nothing to correlate against), when it has no energy at all (silence), or when no lag
 * in range correlates positively.
 *
 * @param onsetEnvelope the envelope from [onsetStrength], one value per frame.
 * @param sampleRate sample rate of the audio the envelope was computed from, in Hz.
 * @param hopLength the hop length passed to [onsetStrength]; it converts frames to seconds.
 * @param minBpm slowest tempo to consider.
 * @param maxBpm fastest tempo to consider.
 */
fun estimateTempoFromEnvelope(
    onsetEnvelope: FloatArray,
    sampleRate: Int,
    hopLength: Int = 512,
    minBpm: Double = 60.0,
    maxBpm: Double = 200.0,
): Double? {
    require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
    require(hopLength > 0) { "hopLength must be positive, got $hopLength" }
    require(minBpm > 0.0) { "minBpm must be positive, got $minBpm" }
    require(maxBpm > minBpm) { "maxBpm ($maxBpm) must be greater than minBpm ($minBpm)" }

    val n = onsetEnvelope.size
    if (n == 0) return null

    // Frames per second, and from it the beat period in frames for a given tempo.
    val framesPerSecond = sampleRate.toDouble() / hopLength
    val slowestLag = 60.0 * framesPerSecond / minBpm
    val fastestLag = 60.0 * framesPerSecond / maxBpm

    val maxLag = ceil(slowestLag).toInt()
    val minLag = maxOf(1, floor(fastestLag).toInt())
    // Fewer frames than one beat period at minBpm: the slowest tempo asked for cannot be seen.
    if (maxLag >= n || minLag > maxLag) return null

    var mean = 0.0
    for (v in onsetEnvelope) mean += v
    mean /= n
    val x = DoubleArray(n) { onsetEnvelope[it] - mean }

    var energy = 0.0
    for (v in x) energy += v * v
    if (energy < ENERGY_EPSILON) return null

    // Weighted autocorrelation over the candidate lags. Unbiased normalisation is deliberately
    // not used: leaving the raw sum in place makes long lags decay naturally, which is a useful
    // extra push away from half-tempo answers.
    val scores = DoubleArray(maxLag - minLag + 1)
    for (lag in minLag..maxLag) {
        var sum = 0.0
        for (t in 0 until n - lag) sum += x[t] * x[t + lag]
        val bpm = 60.0 * framesPerSecond / lag
        val octaves = ln(bpm / PRIOR_CENTER_BPM) / LN2 / PRIOR_WIDTH_OCTAVES
        scores[lag - minLag] = (sum / energy) * exp(-0.5 * octaves * octaves)
    }

    var bestIndex = 0
    for (i in scores.indices) if (scores[i] > scores[bestIndex]) bestIndex = i
    if (scores[bestIndex] <= 0.0) return null

    // Parabolic interpolation around the peak, on the same weighted curve the peak was picked
    // from. The prior varies slowly compared with the correlation peak, so it shifts the refined
    // position by far less than one lag step.
    var refinedLag = (bestIndex + minLag).toDouble()
    if (bestIndex > 0 && bestIndex < scores.size - 1) {
        val left = scores[bestIndex - 1]
        val center = scores[bestIndex]
        val right = scores[bestIndex + 1]
        val denom = left - 2.0 * center + right
        if (denom < 0.0) {
            val delta = 0.5 * (left - right) / denom
            if (delta > -1.0 && delta < 1.0) refinedLag += delta
        }
    }

    return clip(60.0 * framesPerSecond / refinedLag, minBpm, maxBpm)
}

/**
 * Estimates the tempo of [y] in BPM, or `null` when it cannot be estimated.
 *
 * Convenience wrapper: computes the onset strength envelope with [onsetStrength] and hands it to
 * [estimateTempoFromEnvelope], which documents the method, the octave handling and the `null`
 * cases. Compute the envelope yourself and call [estimateTempoFromEnvelope] directly if you also
 * need it for something else (drawing it, or [pickOnsets]) — the envelope is by far the expensive
 * half.
 *
 * (The two are not overloads of one name because both would take a `FloatArray` first and so
 * would have the same signature; the envelope version carries the longer name.)
 *
 * @param y mono audio.
 * @param sampleRate sample rate of [y] in Hz.
 * @param hopLength onset envelope hop length in samples.
 * @param minBpm slowest tempo to consider.
 * @param maxBpm fastest tempo to consider.
 */
fun estimateTempo(
    y: FloatArray,
    sampleRate: Int,
    hopLength: Int = 512,
    minBpm: Double = 60.0,
    maxBpm: Double = 200.0,
): Double? {
    val envelope = onsetStrength(y, sampleRate, hopLength = hopLength)
    return estimateTempoFromEnvelope(envelope, sampleRate, hopLength, minBpm, maxBpm)
}
