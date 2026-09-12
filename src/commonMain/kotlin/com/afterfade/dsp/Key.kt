package com.afterfade.dsp

import kotlin.math.sqrt

/**
 * Musical key estimation: a chroma (pitch-class) profile plus Krumhansl-Schmuckler key finding.
 *
 * This is the missing half of [pitchShiftToKey]: that function moves audio onto a key the caller
 * already knows, and [estimateKey] is how the caller finds out what key a recording is in. The
 * [KeyEstimate.tonic] and [KeyEstimate.scale] strings are spelled exactly the way
 * [pitchShiftToKey] and [nearestScaleSemitones] accept them, so an estimate can be passed straight
 * through:
 *
 * ```kotlin
 * val key = estimateKey(reference, sr)
 * val tuned = if (key != null) pitchShiftToKey(other, sr, key.tonic, key.scale) else other
 * ```
 */

/** Lowest frequency that contributes to [chroma]. Below this a bin is mostly rumble and DC leakage. */
private const val CHROMA_MIN_HZ = 60.0

/** Highest frequency that contributes to [chroma]. Above this the bins are mostly noise and air. */
private const val CHROMA_MAX_HZ = 5000.0

/**
 * Krumhansl-Kessler major key profile — the averaged "probe tone" ratings from
 * Krumhansl & Kessler (1982), *Tracing the dynamic changes in perceived tonal organization in a
 * spatial representation of musical keys*, Psychological Review 89(4), 334-368. Index 0 is the
 * tonic, index 1 the minor second, and so on.
 */
private val KK_MAJOR = doubleArrayOf(
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
)

/** Krumhansl-Kessler minor key profile, from the same study. Index 0 is the tonic. */
private val KK_MINOR = doubleArrayOf(
    6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
)

/**
 * The key [estimateKey] settled on.
 *
 * @property tonic one of [CHROMATIC_NOTES] (sharps, never flats), ready for [pitchShiftToKey].
 * @property scale `"major"` or `"minor"`, ready for [pitchShiftToKey] / [nearestScaleSemitones].
 * @property confidence how far ahead the winner was: the best correlation minus the best of the
 *   other 23 candidates. Both correlations are Pearson coefficients in `-1..1`, so this margin is
 *   in `0..2`, but in practice a clear key lands around `0.05..0.4` and anything below about `0.02`
 *   means the runner-up (usually the relative major/minor, which shares every note) is just as
 *   good a fit. It is a margin, not a probability.
 */
data class KeyEstimate(val tonic: String, val scale: String, val confidence: Double)

/**
 * 12-bin pitch-class energy profile ("chroma"), summed over every frame of [y].
 *
 * Each frame of [nFft] samples is windowed with [hanning] and transformed with [RealFftPlan]; the
 * magnitude of every bin between 60 Hz and 5 kHz is added to the pitch class of that bin's centre
 * frequency. Frames advance by [hopLength]. Input shorter than [nFft] is zero-padded to one frame.
 * The result is scaled so that the largest bin is `1.0`; silence (and input with no energy in the
 * band) gives twelve zeros rather than a division by zero.
 *
 * Index 0 is C, index 1 C#, and so on — the same order as [CHROMATIC_NOTES].
 *
 * Mapping a bin to a pitch class needs `log2` of a frequency ratio ([hzToMidi]). The library's
 * "no `pow`/`log`" rule is about values that feed the audio path, where one last-bit difference
 * between the JVM and libm would change the sound; here the logarithm is immediately rounded to
 * an integer pitch class, and a bin would have to sit within one ulp of the quarter-tone boundary
 * between two notes for a rounding difference to be visible at all. Chroma is analysis output,
 * never a sample value, so the rule does not apply.
 *
 * @param y mono audio.
 * @param sampleRate sample rate of [y] in Hz.
 * @param nFft frame size in samples; larger resolves low notes better, 4096 at 44.1 kHz gives
 *   about 10.8 Hz per bin.
 * @param hopLength distance between successive frames in samples.
 */
fun chroma(y: FloatArray, sampleRate: Int, nFft: Int = 4096, hopLength: Int = 2048): DoubleArray {
    require(nFft > 1) { "nFft must be greater than 1, got $nFft" }
    require(hopLength > 0) { "hopLength must be positive, got $hopLength" }
    require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }

    val out = DoubleArray(12)
    if (y.isEmpty()) return out

    // Pre-compute the pitch class of every bin; it only depends on nFft and the sample rate.
    val plan = RealFftPlan(nFft)
    val bins = plan.bins
    val binClass = IntArray(bins) { -1 }
    val binHz = sampleRate.toDouble() / nFft
    for (k in 1 until bins) {
        val hz = k * binHz
        if (hz < CHROMA_MIN_HZ || hz > CHROMA_MAX_HZ) continue
        val midi = rint(hzToMidi(hz)).toInt()
        binClass[k] = ((midi % 12) + 12) % 12
    }

    val window = hanning(nFft)
    val frame = DoubleArray(nFft)
    val re = DoubleArray(bins)
    val im = DoubleArray(bins)

    var start = 0
    while (start == 0 || start <= y.size - nFft) {
        for (k in 0 until nFft) {
            val i = start + k
            frame[k] = if (i < y.size) y[i] * window[k] else 0.0
        }
        plan.rfft(frame, re, im)
        for (k in 1 until bins) {
            val pc = binClass[k]
            if (pc < 0) continue
            out[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        start += hopLength
    }

    var peak = 0.0
    for (v in out) if (v > peak) peak = v
    if (peak <= 0.0) return DoubleArray(12)
    for (i in out.indices) out[i] /= peak
    return out
}

/**
 * Estimate the musical key of [y] with the Krumhansl-Schmuckler algorithm.
 *
 * The [chroma] vector is correlated (Pearson) with all 24 rotations of the Krumhansl-Kessler
 * major and minor profiles; the rotation that correlates best names the key, and
 * [KeyEstimate.confidence] is how far it beat the runner-up. Returns `null` for silence, for input
 * with no energy between 60 Hz and 5 kHz, and for a perfectly flat chroma (white noise, a full
 * chromatic cluster), where no key fits better than any other.
 *
 * **Limitation — relative keys.** A major key and its relative minor (C major and A minor) use the
 * same seven notes, so the only thing separating them is how much weight lands on each degree.
 * Material that merely runs through the scale can tip either way; material that leans on its tonic
 * and dominant — longer, louder, more often, or harmonised — is identified reliably. When
 * [KeyEstimate.confidence] is small, the runner-up is almost always the relative key.
 *
 * @param y mono audio; a few seconds is plenty.
 * @param sampleRate sample rate of [y] in Hz.
 */
fun estimateKey(y: FloatArray, sampleRate: Int): KeyEstimate? {
    val c = chroma(y, sampleRate)

    var sum = 0.0
    for (v in c) sum += v
    if (sum <= 0.0) return null

    val mean = sum / 12.0
    var variance = 0.0
    for (v in c) {
        val d = v - mean
        variance += d * d
    }
    if (variance <= 0.0) return null
    val chromaNorm = sqrt(variance)

    var bestScore = Double.NEGATIVE_INFINITY
    var secondScore = Double.NEGATIVE_INFINITY
    var bestTonic = 0
    var bestMajor = true

    for (major in booleanArrayOf(true, false)) {
        val profile = if (major) KK_MAJOR else KK_MINOR
        var profileSum = 0.0
        for (v in profile) profileSum += v
        val profileMean = profileSum / 12.0
        var profileVar = 0.0
        for (v in profile) {
            val d = v - profileMean
            profileVar += d * d
        }
        val profileNorm = sqrt(profileVar)

        for (tonic in 0 until 12) {
            var cov = 0.0
            for (pc in 0 until 12) {
                cov += (c[pc] - mean) * (profile[((pc - tonic) % 12 + 12) % 12] - profileMean)
            }
            val score = cov / (chromaNorm * profileNorm)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestTonic = tonic
                bestMajor = major
            } else if (score > secondScore) {
                secondScore = score
            }
        }
    }

    val margin = if (secondScore == Double.NEGATIVE_INFINITY) 0.0 else bestScore - secondScore
    return KeyEstimate(CHROMATIC_NOTES[bestTonic], if (bestMajor) "major" else "minor", margin)
}
