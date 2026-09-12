package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of `music_engine/audio/pitch.py` — YIN pitch detection and phase-vocoder pitch shifting.
 */

val CHROMATIC_NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private val FLAT_TO_SHARP = mapOf("Db" to "C#", "Eb" to "D#", "Gb" to "F#", "Ab" to "G#", "Bb" to "A#")

private val SCALE_INTERVALS = mapOf(
    "major" to listOf(0, 2, 4, 5, 7, 9, 11),
    "minor" to listOf(0, 2, 3, 5, 7, 8, 10),
)

internal fun hzToMidi(hz: Double): Double = 69.0 + 12.0 * log2(hz / 440.0)

/**
 * `_yin_pitch` — fundamental frequency of a single frame, or null when unpitched.
 *
 * The Python loop mutates its own loop variable while walking down the local minimum, which Python
 * ignores for iteration purposes but which does affect `best_tau`; the while loop below reproduces
 * that behaviour, including the immediate `break`.
 */
fun yinPitch(y: DoubleArray, sr: Int, fmin: Double = 65.0, fmax: Double = 2093.0): Double? {
    val tauMin = maxOf(2, (sr / fmax).toInt())
    val tauMax = minOf(y.size / 2, (sr / fmin).toInt())
    if (tauMax <= tauMin) return null

    val n = y.size
    val diff = DoubleArray(tauMax)
    for (tau in 1 until tauMax) {
        var sum = 0.0
        for (i in 0 until n - tau) {
            val d = y[i] - y[i + tau]
            sum += d * d
        }
        diff[tau] = sum
    }

    val cmndf = DoubleArray(tauMax) { 1.0 }
    var runningSum = 0.0
    for (tau in 1 until tauMax) {
        runningSum += diff[tau]
        if (runningSum > 0) cmndf[tau] = diff[tau] * tau / runningSum
    }

    val threshold = 0.15
    var bestTau = -1
    var tau = tauMin
    while (tau < tauMax) {
        if (cmndf[tau] < threshold) {
            while (tau + 1 < tauMax && cmndf[tau + 1] < cmndf[tau]) tau++
            bestTau = tau
            break
        }
        tau++
    }

    if (bestTau < 0) {
        var argmin = tauMin
        for (t in tauMin until tauMax) if (cmndf[t] < cmndf[argmin]) argmin = t
        bestTau = argmin
        if (cmndf[bestTau] > 0.5) return null
    }

    var refinedTau = bestTau.toDouble()
    if (bestTau > 0 && bestTau < tauMax - 1) {
        val a = cmndf[bestTau - 1]
        val b = cmndf[bestTau]
        val c = cmndf[bestTau + 1]
        val denom = 2 * (a - 2 * b + c)
        if (abs(denom) > 1e-10) refinedTau = bestTau + (a - c) / denom
    }

    val f0 = sr / refinedTau
    if (f0 < fmin || f0 > fmax) return null
    return f0
}

/** `estimate_pitch` — median voiced F0 over 50 ms frames, or null when unpitched. */
fun estimatePitch(y: FloatArray, sr: Int): Double? {
    val frameLen = minOf(y.size, (sr * 0.05).toInt())
    if (frameLen < 2) return null
    val hop = frameLen / 2
    if (hop < 1) return null

    val voiced = ArrayList<Double>()
    var start = 0
    while (start <= y.size - frameLen) {
        val frame = DoubleArray(frameLen) { y[start + it].toDouble() }
        yinPitch(frame, sr)?.let { voiced.add(it) }
        start += hop
    }

    if (voiced.isEmpty()) return null
    return median(voiced.toDoubleArray())
}

/** `hz_to_note` — (note name, octave). Rounding is half-to-even, matching Python's `round()`. */
fun hzToNote(hz: Double): Pair<String, Int> {
    val midi = rint(hzToMidi(hz)).toInt()
    val noteIdx = ((midi % 12) + 12) % 12
    val octave = floorDiv(midi, 12) - 1
    return CHROMATIC_NOTES[noteIdx] to octave
}

/** `nearest_scale_semitones` — how far to shift [hz] to land on the nearest note of the key. */
fun nearestScaleSemitones(hz: Double, key: String, scale: String = "major"): Int {
    val midi = hzToMidi(hz)
    val normalizedKey = FLAT_TO_SHARP[key] ?: key
    val keyIdx = CHROMATIC_NOTES.indexOf(normalizedKey)
    require(keyIdx >= 0) { "unknown key: $key" }
    val intervals = SCALE_INTERVALS[scale] ?: throw IllegalArgumentException("unknown scale: $scale")

    var bestShift = 0
    var bestDist = 999.0
    for (delta in -6..6) {
        val candidateMidi = rint(midi).toInt() + delta
        val relative = ((candidateMidi - keyIdx) % 12 + 12) % 12
        if (relative in intervals) {
            val dist = abs(delta).toDouble()
            if (dist < bestDist) {
                bestDist = dist
                bestShift = delta
            }
        }
    }
    return bestShift
}

/** `_phase_vocoder` — time-stretch by [rate] while preserving pitch. */
fun phaseVocoder(y: FloatArray, rate: Double, nFft: Int, hop: Int): FloatArray {
    val window = hanning(nFft)
    val yPadded = padReflect(DoubleArray(y.size) { y[it].toDouble() }, nFft / 2)

    val nFrames = 1 + (yPadded.size - nFft) / hop
    if (nFrames <= 0) return FloatArray(0)

    val plan = RealFftPlan(nFft)
    val bins = plan.bins
    val stftRe = Array(nFrames) { DoubleArray(bins) }
    val stftIm = Array(nFrames) { DoubleArray(bins) }
    val frame = DoubleArray(nFft)
    for (i in 0 until nFrames) {
        val start = i * hop
        for (k in 0 until nFft) frame[k] = yPadded[start + k] * window[k]
        plan.rfft(frame, stftRe[i], stftIm[i])
    }

    val nOutFrames = ceil(nFrames / rate).toInt()
    if (nOutFrames <= 0) return FloatArray(0)

    // np.linspace(0, pi * hop, bins, endpoint=False)
    val phaseAdvanceStep = PI * hop / bins
    val phaseAdvance = DoubleArray(bins) { it * phaseAdvanceStep }

    val phaseAcc = DoubleArray(bins) { atan2(stftIm[0][it], stftRe[0][it]) }
    val out = DoubleArray(nOutFrames * hop + nFft)
    val mag = DoubleArray(bins)
    val outRe = DoubleArray(bins)
    val outIm = DoubleArray(bins)
    val synth = DoubleArray(nFft)
    val twoPi = 2.0 * PI

    for (i in 0 until nOutFrames) {
        val srcPos = i * rate
        val srcIdx = srcPos.toInt()
        val frac = srcPos - srcIdx

        when {
            srcIdx + 1 < nFrames -> for (k in 0 until bins) {
                val a = sqrt(stftRe[srcIdx][k] * stftRe[srcIdx][k] + stftIm[srcIdx][k] * stftIm[srcIdx][k])
                val b = sqrt(
                    stftRe[srcIdx + 1][k] * stftRe[srcIdx + 1][k] +
                        stftIm[srcIdx + 1][k] * stftIm[srcIdx + 1][k],
                )
                mag[k] = (1 - frac) * a + frac * b
            }
            srcIdx < nFrames -> for (k in 0 until bins) {
                mag[k] = sqrt(stftRe[srcIdx][k] * stftRe[srcIdx][k] + stftIm[srcIdx][k] * stftIm[srcIdx][k])
            }
            else -> break
        }

        for (k in 0 until bins) {
            outRe[k] = mag[k] * cos(phaseAcc[k])
            outIm[k] = mag[k] * sin(phaseAcc[k])
        }

        // irfft here deliberately drops the imaginary part of the DC and Nyquist bins, exactly
        // as np.fft.irfft does — phase_acc leaves non-zero values in both.
        plan.irfft(outRe, outIm, synth)
        val start = i * hop
        for (k in 0 until nFft) out[start + k] += synth[k] * window[k]

        if (srcIdx + 1 < nFrames) {
            for (k in 0 until bins) {
                val next = atan2(stftIm[srcIdx + 1][k], stftRe[srcIdx + 1][k])
                val cur = atan2(stftIm[srcIdx][k], stftRe[srcIdx][k])
                var dp = next - cur - phaseAdvance[k]
                dp -= twoPi * rint(dp / twoPi)
                phaseAcc[k] += phaseAdvance[k] + dp
            }
        } else {
            for (k in 0 until bins) phaseAcc[k] += phaseAdvance[k]
        }
    }

    val trimmed = out.copyOfRange(nFft / 2, out.size)
    val expectedLen = rint(y.size / rate).toInt()
    val finalLen = if (trimmed.size >= expectedLen) expectedLen else trimmed.size
    return FloatArray(finalLen) { trimmed[it].toFloat() }
}

/**
 * 2^(n/12) を n = 0..11 で正しく丸めた値。17桁でラウンドトリップする。
 *
 * `2.0.pow(n / 12.0)` は libm の実装次第で最下位1ビット違う。JVM の `Math.pow` は
 * 1 ulp の誤差を許すintrinsicで、Kotlin/Native が使う libm とは結果が一致しない
 * 周波数がある。同じサイクルが Android と iOS で違う音になるということなので、
 * テーブル引き + 2の冪のスケーリングに置き換えて pow を経路から外す。
 * 掛け算と2の冪は IEEE-754 が結果を一意に決めるので、どこで走らせても同じ値になる。
 */
private val SEMITONE_RATIO = doubleArrayOf(
    1.0,
    1.0594630943592953,
    1.122462048309373,
    1.189207115002721,
    1.2599210498948732,
    1.3348398541700344,
    1.4142135623730951,
    1.4983070768766815,
    1.5874010519681996,
    1.681792830507429,
    1.7817974362806785,
    1.887748625363387,
)

/**
 * Frequency ratio for [semitones] semitones, i.e. `2^(semitones / 12)` without calling `pow`.
 *
 * Ratios within one octave come from a correctly rounded table; octaves are applied by
 * multiplying or dividing by 2, which IEEE-754 defines exactly. `pow` is not required to be
 * correctly rounded and its last bit differs between the JVM and libm, so this is the only way
 * to get the same pitch on every platform.
 *
 * 半音 [semitones] 分の周波数比。オクターブは2倍・1/2倍で運ぶので誤差が出ない。
 */
fun semitoneRatio(semitones: Int): Double {
    val octave = semitones.floorDiv(12)
    var ratio = SEMITONE_RATIO[semitones.mod(12)]
    repeat(if (octave >= 0) octave else -octave) {
        ratio = if (octave >= 0) ratio * 2.0 else ratio / 2.0
    }
    return ratio
}

/**
 * `_pitch_shift` — shift by [nSteps] semitones: time-stretch by `ratio`, then play the stretched
 * audio back in the original span, which speeds it up by `ratio` and so raises the pitch by
 * [nSteps]. The result is always exactly `y.size` samples long.
 *
 * The reference implementation this was ported from resampled to `stretchedLength * ratio` rather
 * than back to `y.size`, then cut the result to length. That moved the pitch the *opposite* way to
 * [nSteps] — so accents landed off the template's key rather than on it — and it threw away
 * `1 - 1/ratio²` of the tail on the way up (half a phrase at +6 semitones) while padding the tail
 * with silence on the way down. Inaudible on a 150 ms hit, fatal on a two-second phrase.
 */
fun pitchShift(y: FloatArray, sr: Int, nSteps: Int, nFft: Int = 2048): FloatArray {
    if (nSteps == 0 || y.size < 2) return y
    val ratio = semitoneRatio(nSteps)
    val hop = nFft / 4
    val stretched = phaseVocoder(y, 1.0 / ratio, nFft, hop)
    if (stretched.size < 2) return y

    val out = resample(stretched, y.size)
    // The vocoder's overlap-add leaves a fixed ~1.5x gain, which would make shifted accents louder
    // than the ones that happened to need no shift. Put the level back where it started.
    val before = peakOf(y)
    val after = peakOf(out)
    if (before <= 0f || after <= 0f) return out
    val gain = before / after
    return FloatArray(out.size) { out[it] * gain }
}

/** `pitch_shift_to_key` — detect the pitch and move it onto the target key/scale. */
fun pitchShiftToKey(y: FloatArray, sr: Int, key: String, scale: String = "major"): FloatArray {
    val f0 = estimatePitch(y, sr) ?: return y
    val shift = nearestScaleSemitones(f0, key, scale)
    if (shift == 0) return y
    return pitchShift(y, sr, shift)
}

private fun floorDiv(a: Int, b: Int): Int {
    val q = a / b
    return if ((a % b != 0) && ((a xor b) < 0)) q - 1 else q
}
