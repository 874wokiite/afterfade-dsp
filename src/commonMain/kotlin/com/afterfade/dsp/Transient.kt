package com.afterfade.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of `music_engine/audio/transient.py` — pulling short percussive events out of ambient audio.
 *
 * Note on precision: `_onset_strength` works in float64 (numpy promotes the float32 frame when it
 * multiplies by the float64 Hann window), but `_pick_onsets` works in **float32** under NEP 50,
 * because `onset_env.max() + 1e-8` keeps the float32 dtype. Onset picking is a chain of threshold
 * comparisons, so that distinction is reproduced literally here — computing it in double would
 * occasionally add or drop an onset.
 */

/** One detected event: [audio] is the excerpt, already faded out at the tail. */
class TransientEvent(
    val onsetSec: Double,
    val offsetSec: Double,
    val audio: FloatArray,
    val fragmentIndex: Int = 0,
) {
    fun withFragmentIndex(index: Int) = TransientEvent(onsetSec, offsetSec, audio, index)
}

/** `_mel_frequencies` — mel-spaced frequency edges (n_mels + 2 of them). */
fun melFrequencies(nMels: Int, fmin: Double, fmax: Double): DoubleArray {
    val melMin = 2595.0 * log10(1.0 + fmin / 700.0)
    val melMax = 2595.0 * log10(1.0 + fmax / 700.0)
    val mels = linspace(melMin, melMax, nMels + 2, endpoint = true)
    return DoubleArray(mels.size) { 700.0 * (10.0.pow(mels[it] / 2595.0) - 1.0) }
}

/**
 * `_mel_filterbank` — triangular mel filters, `n_mels` rows of `n_fft/2 + 1` bins.
 *
 * Rows are [FloatArray] because numpy stores the bank as float32; the matrix product against the
 * float64 magnitude spectrum then accumulates in double, which is what numpy does too.
 */
fun melFilterbank(sr: Int, nFft: Int, nMels: Int = 64): Array<FloatArray> {
    val fmax = sr / 2.0
    val freqs = rfftFreq(nFft, sr)
    val edges = melFrequencies(nMels, 0.0, fmax)

    return Array(nMels) { m ->
        val lo = edges[m]
        val center = edges[m + 1]
        val hi = edges[m + 2]
        val upDenom = center - lo + 1e-10
        val downDenom = hi - center + 1e-10
        FloatArray(freqs.size) { k ->
            val up = (freqs[k] - lo) / upDenom
            val down = (hi - freqs[k]) / downDenom
            maxOf(0.0, minOf(up, down)).toFloat()
        }
    }
}

private var cachedMelFbKey: Long = 0L
private var cachedMelFb: Array<FloatArray>? = null

private fun getCachedMelFilterbank(sr: Int, nFft: Int, nMels: Int = 64): Array<FloatArray> {
    val key = sr.toLong() shl 32 or (nFft.toLong() shl 16) or nMels.toLong()
    cachedMelFb?.let { if (cachedMelFbKey == key) return it }
    val fb = melFilterbank(sr, nFft, nMels)
    cachedMelFbKey = key
    cachedMelFb = fb
    return fb
}

/** `_onset_strength` — mel-weighted spectral flux. */
fun onsetStrength(y: FloatArray, sr: Int, hopLength: Int = 512, nFft: Int = 2048): FloatArray {
    val melFb = getCachedMelFilterbank(sr, nFft)
    val nMels = melFb.size

    val padLen = nFft / 2
    val yPadded = padReflect(y, padLen)

    val nFrames = 1 + (yPadded.size - nFft) / hopLength
    if (nFrames <= 0) return FloatArray(0)

    val window = hanning(nFft)
    val plan = RealFftPlan(nFft)
    val frame = DoubleArray(nFft)
    val specRe = DoubleArray(plan.bins)
    val specIm = DoubleArray(plan.bins)
    val mag = DoubleArray(plan.bins)

    val onsetEnv = FloatArray(nFrames)
    var prevMel: DoubleArray? = null

    for (i in 0 until nFrames) {
        val start = i * hopLength
        for (k in 0 until nFft) frame[k] = yPadded[start + k] * window[k]
        plan.rfft(frame, specRe, specIm)
        for (k in 0 until plan.bins) mag[k] = sqrt(specRe[k] * specRe[k] + specIm[k] * specIm[k])

        val melMag = DoubleArray(nMels)
        for (m in 0 until nMels) {
            val row = melFb[m]
            var sum = 0.0
            for (k in 0 until plan.bins) sum += row[k] * mag[k]
            melMag[m] = sum
        }

        val prev = prevMel
        if (prev != null) {
            var flux = 0.0
            for (m in 0 until nMels) {
                val diff = melMag[m] - prev[m]
                if (diff > 0.0) flux += diff
            }
            onsetEnv[i] = flux.toFloat()
        }
        prevMel = melMag
    }

    return onsetEnv
}

/** `_pick_onsets` — adaptive local-median threshold plus a local-maximum test. Float32 throughout. */
fun pickOnsets(onsetEnv: FloatArray, delta: Float = 0.3f, medianWindow: Int = 7): IntArray {
    if (onsetEnv.isEmpty()) return IntArray(0)
    var maxValue = onsetEnv[0]
    for (v in onsetEnv) if (v > maxValue) maxValue = v
    if (maxValue < 1e-8f) return IntArray(0)

    val scale = maxValue + 1e-8f
    val env = FloatArray(onsetEnv.size) { onsetEnv[it] / scale }

    val pad = medianWindow / 2
    val padded = padEdge(env, pad)
    val deltaHalf = (delta.toDouble() * 0.5).toFloat()
    val threshold = FloatArray(env.size) { median(padded, it, it + medianWindow) + deltaHalf }

    val onsets = ArrayList<Int>()
    for (i in 1 until env.size - 1) {
        if (env[i] > threshold[i] && env[i] > env[i - 1] && env[i] >= env[i + 1]) onsets.add(i)
    }
    return onsets.toIntArray()
}

/** RMS envelope frame size used by the end detectors and the ambient-level estimate. */
private const val ENVELOPE_FRAME_MS = 10.0

/** How far past an onset the event's own peak is looked for. */
private const val ATTACK_WINDOW_MS = 300.0

/** Window before an onset that stands for "what was already here". */
private const val PRE_ONSET_WINDOW_MS = 200.0

/**
 * How far above the pre-onset level an onset has to jump to be treated as an utterance rather
 * than ambient texture. 18 dB — measured on real かけら, ambient onsets sit at 2–5x their local
 * level while a voice starting in a room jumps 18x or more.
 */
private const val SUSTAIN_PROMINENCE = 8.0

/** A sustained event is over once it falls back to this multiple of the level it interrupted. */
private const val SUSTAIN_RELEASE_MARGIN = 2.0

/**
 * How long the level has to stay down before a sustained event is called over. Long enough to sit
 * through the closure of a geminate consonant (the っ in 「やった」 is ~140 ms of near-silence).
 */
private const val SUSTAIN_RELEASE_HOLD_MS = 150.0

/** Frame-wise RMS of `y[start until start + nFrames * frame]`. */
private fun rmsEnvelope(y: FloatArray, start: Int, nFrames: Int, frame: Int): DoubleArray {
    val out = DoubleArray(nFrames)
    for (f in 0 until nFrames) {
        var sum = 0.0
        val base = start + f * frame
        for (k in 0 until frame) {
            val v = y[base + k].toDouble()
            sum += v * v
        }
        out[f] = sqrt(sum / frame)
    }
    return out
}

/** RMS of `y[from until to]`, 0 for an empty range. */
private fun rmsOf(y: FloatArray, from: Int, to: Int): Double {
    if (to <= from) return 0.0
    var sum = 0.0
    for (i in from until to) {
        val v = y[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / (to - from))
}

/** Nearest-rank percentile of a copy of [values]. */
private fun percentileOf(values: DoubleArray, fraction: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.copyOf()
    sorted.sort()
    val idx = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
    return sorted[idx]
}

/**
 * End of a *sustained* event — one loud enough that its own syllables raise onsets of their own.
 *
 * [findEventEnd] cannot be used here: it measures decay against a fixed -30 dB below the peak,
 * which on any real recording sits below the room tone and so never fires. This measures against
 * [releaseRms] — the level the event interrupted — and requires the level to stay down for
 * [SUSTAIN_RELEASE_HOLD_MS] so that a pause inside a phrase does not end it.
 *
 * Returns the first frame that begins the quiet run, not the end of the run, so the event is cut
 * where it actually stopped sounding.
 */
internal fun findSustainedEventEnd(
    y: FloatArray,
    start: Int,
    limit: Int,
    sr: Int,
    releaseRms: Double,
    decayDb: Double = -30.0,
): Int {
    val frame = maxOf(1, (sr * ENVELOPE_FRAME_MS / 1000).toInt())
    val nFrames = if (limit > start) (limit - start) / frame else 0
    if (nFrames < 2) return limit

    val rms = rmsEnvelope(y, start, nFrames, frame)
    val attackFrames = minOf(nFrames, maxOf(1, (ATTACK_WINDOW_MS / ENVELOPE_FRAME_MS).toInt()))
    var peakIdx = 0
    for (f in 1 until attackFrames) if (rms[f] > rms[peakIdx]) peakIdx = f
    if (rms[peakIdx] < 1e-8) return minOf(start + frame, limit)

    // Never demand more than a 6 dB drop, so a noisy release still resolves.
    val threshold = minOf(
        maxOf(rms[peakIdx] * 10.0.pow(decayDb / 20.0), releaseRms),
        rms[peakIdx] * 0.5,
    )
    val holdFrames = maxOf(1, (SUSTAIN_RELEASE_HOLD_MS / ENVELOPE_FRAME_MS).toInt())

    var f = peakIdx + 1
    while (f < nFrames) {
        if (rms[f] >= threshold) {
            f++
            continue
        }
        var g = f
        val holdEnd = minOf(f + holdFrames, nFrames)
        while (g < holdEnd && rms[g] < threshold) g++
        if (g >= holdEnd) return minOf(start + f * frame, limit)
        f = g + 1
    }
    return limit
}

/**
 * `_find_event_end` — where the event's RMS envelope has decayed by [decayDb] from its peak.
 *
 * Returns [limit] when the event never decays inside the window.
 */
fun findEventEnd(
    y: FloatArray,
    start: Int,
    limit: Int,
    sr: Int,
    decayDb: Double = -30.0,
    frameMs: Double = 10.0,
): Int {
    val windowLength = limit - start
    val frame = maxOf(1, (sr * frameMs / 1000).toInt())
    val nFrames = if (windowLength > 0) windowLength / frame else 0
    if (nFrames < 2) return limit

    val rms = DoubleArray(nFrames)
    for (f in 0 until nFrames) {
        var sum = 0.0
        val base = start + f * frame
        for (k in 0 until frame) {
            val v = y[base + k].toDouble()
            sum += v * v
        }
        rms[f] = sqrt(sum / frame)
    }

    var peakIdx = 0
    for (f in 1 until nFrames) if (rms[f] > rms[peakIdx]) peakIdx = f
    if (rms[peakIdx] < 1e-8) return minOf(start + frame, limit)

    val threshold = rms[peakIdx] * 10.0.pow(decayDb / 20.0)
    for (f in peakIdx + 1 until nFrames) {
        if (rms[f] < threshold) return minOf(start + f * frame, limit)
    }
    return limit
}

/**
 * `detect_transients` — every onset becomes an event running until its energy has decayed, the
 * next onset arrives, or [maxDurationMs] elapses, whichever comes first.
 *
 * Onsets that jump [SUSTAIN_PROMINENCE] above the level they interrupt are the exception. A spoken
 * phrase raises an onset on every mora, so cutting at the next onset turns 「やったぜー」 into
 * 「やっ」; such an onset instead runs to [sustainMaxDurationMs] or until it falls quiet, and the
 * onsets it swallows do not start events of their own. Ambient texture never clears the bar, so
 * percussive material keeps its previous slicing exactly.
 */
fun detectTransients(
    y: FloatArray,
    sr: Int,
    hopLength: Int = 512,
    threshold: Float = 0.3f,
    minDurationMs: Double = 30.0,
    maxDurationMs: Double = 2000.0,
    decayDb: Double = -30.0,
    sustainMaxDurationMs: Double = 3000.0,
): List<TransientEvent> {
    val onsetEnv = onsetStrength(y, sr, hopLength = hopLength)
    val onsetFrames = pickOnsets(onsetEnv, delta = threshold)
    // onset_frames * hop_length is an int array in numpy; the division promotes to float64.
    val onsetTimes = DoubleArray(onsetFrames.size) { (onsetFrames[it] * hopLength).toDouble() / sr }

    val minSamples = (sr * minDurationMs / 1000).toInt()
    val maxSamples = (sr * maxDurationMs / 1000).toInt()
    val sustainMaxSamples = (sr * sustainMaxDurationMs / 1000).toInt()

    val envFrame = maxOf(1, (sr * ENVELOPE_FRAME_MS / 1000).toInt())
    val envelope = rmsEnvelope(y, 0, y.size / envFrame, envFrame)
    // The quiet tenth of the clip stands in for the room, for onsets with nothing before them.
    val ambientRms = percentileOf(envelope, 0.1)
    val attackFrames = maxOf(1, (ATTACK_WINDOW_MS / ENVELOPE_FRAME_MS).toInt())
    val preOnsetSamples = (sr * PRE_ONSET_WINDOW_MS / 1000).toInt()

    val events = ArrayList<TransientEvent>()
    var i = 0
    while (i < onsetTimes.size) {
        val onsetSec = onsetTimes[i]
        val start = (onsetSec * sr).toInt()
        val nextStart = if (i + 1 < onsetTimes.size) (onsetTimes[i + 1] * sr).toInt() else y.size

        val preFrom = maxOf(0, start - preOnsetSamples)
        // Too little run-up to measure (an onset in the first few ms) — fall back to the room.
        val preRms = if (start - preFrom > sr / 100) rmsOf(y, preFrom, start) else 0.0
        val baseline = maxOf(preRms, ambientRms)
        var attackPeak = 0.0
        val firstFrame = start / envFrame
        for (f in firstFrame until minOf(firstFrame + attackFrames, envelope.size)) {
            if (envelope[f] > attackPeak) attackPeak = envelope[f]
        }
        val sustained = attackPeak > baseline * SUSTAIN_PROMINENCE

        val end = if (sustained) {
            val limit = minOf(start + sustainMaxSamples, y.size)
            findSustainedEventEnd(y, start, limit, sr, baseline * SUSTAIN_RELEASE_MARGIN, decayDb)
        } else {
            val limit = minOf(start + maxSamples, nextStart, y.size)
            findEventEnd(y, start, limit, sr, decayDb = decayDb)
        }.let { minOf(maxOf(it, start + minSamples), y.size) }

        // Only a sustained event absorbs the onsets inside it; leaving this off the percussive path
        // keeps its output identical to before.
        i = if (sustained) {
            var j = i + 1
            while (j < onsetTimes.size && (onsetTimes[j] * sr).toInt() < end) j++
            j
        } else {
            i + 1
        }

        if (end <= start) continue

        val segment = y.copyOfRange(start, end)
        if (segment.size < minSamples) continue

        val fadeLen = minOf((segment.size * 0.1).toInt(), (sr * 0.05).toInt())
        if (fadeLen > 0) {
            val ramp = linspace(1.0, 0.0, fadeLen, endpoint = true)
            val offset = segment.size - fadeLen
            for (k in 0 until fadeLen) segment[offset + k] = (segment[offset + k] * ramp[k]).toFloat()
        }

        events.add(
            TransientEvent(
                onsetSec = onsetSec,
                offsetSec = onsetSec + segment.size.toDouble() / sr,
                audio = segment,
            ),
        )
    }
    return events
}

/** `extract_bed` — the sustained ambient layer (rain, crowd, wind) via a 4th order 800 Hz low-pass. */
fun extractBed(y: FloatArray, sr: Int): FloatArray {
    require(sr == SosFilters.DESIGN_SAMPLE_RATE) {
        "extractBed coefficients are fixed for ${SosFilters.DESIGN_SAMPLE_RATE} Hz, got $sr"
    }
    return sosfilt(SosFilters.LOWPASS_800_HZ, y)
}

/** Peak level of [y], used by the tests and the generator's normalisation steps. */
internal fun peakOf(y: FloatArray): Float {
    var peak = 0f
    for (v in y) {
        val a = abs(v)
        if (a > peak) peak = a
    }
    return peak
}
