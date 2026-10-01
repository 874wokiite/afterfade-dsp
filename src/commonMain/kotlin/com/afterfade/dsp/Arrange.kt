package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Stages for arranging a track: fades, reversal, echo and filter sweeps for intros, build-ups
 * and transitions.
 *
 * 曲を組むための段: フェード、逆再生、エコー、フィルタースイープ。
 */

/** [y] with a linear fade-in over [inSec] and a linear fade-out over [outSec]. */
fun fade(y: FloatArray, sr: Int, inSec: Double = 0.0, outSec: Double = 0.0): FloatArray {
    val n = y.size
    val a = minOf(n, (inSec * sr).toInt())
    val r = minOf(n, (outSec * sr).toInt())
    return FloatArray(n) {
        var g = 1.0
        if (it < a) g *= it.toDouble() / a
        if (it >= n - r) g *= (n - it).toDouble() / r
        (y[it] * g).toFloat()
    }
}

/** [y] played backwards: a reversed cymbal or noise swell leading into a downbeat. */
fun reverse(y: FloatArray): FloatArray = FloatArray(y.size) { y[y.size - 1 - it] }

/**
 * Feedback echo. Each repeat comes [delaySec] after the previous one at [feedback] times its
 * level; [wet] sets how much of the echo is mixed with the dry signal. The output keeps
 * [y]'s length, so leave room at the end for the tail.
 */
fun feedbackDelay(y: FloatArray, sr: Int, delaySec: Double, feedback: Double = 0.4, wet: Double = 0.3): FloatArray {
    require(feedback in 0.0..0.98) { "feedback must be within 0..0.98, was $feedback" }
    val d = maxOf(1, (delaySec * sr).toInt())
    val line = FloatArray(y.size)
    for (i in y.indices) {
        val back = if (i >= d) line[i - d] else 0f
        line[i] = y[i] + (back * feedback).toFloat()
    }
    // line holds dry + echoes; the echo alone is line delayed once.
    return FloatArray(y.size) { y[it] + ((if (it >= d) line[it - d] else 0f) * wet).toFloat() }
}

/**
 * Two-pole filter whose cutoff glides exponentially from [fromHz] to [toHz] across the whole
 * of [y]: a lowpass opening up for an intro, a highpass rising into a drop.
 *
 * Topology-preserving state-variable filter, so the sweep stays stable and click-free.
 * [resonance] is Q; 0.707 is flat, higher values whistle at the cutoff.
 */
fun filterSweep(
    y: FloatArray,
    sr: Int,
    fromHz: Double,
    toHz: Double,
    type: FilterType = FilterType.LOWPASS,
    resonance: Double = 0.707,
): FloatArray {
    val nyquist = sr / 2.0
    require(fromHz > 0.0 && fromHz < nyquist && toHz > 0.0 && toHz < nyquist) {
        "cutoffs must be in (0, $nyquist), were $fromHz and $toHz"
    }
    require(resonance > 0.0) { "resonance must be positive, was $resonance" }
    val n = y.size
    val logRatio = ln(toHz / fromHz)
    val k = 1.0 / resonance
    var ic1 = 0.0
    var ic2 = 0.0
    return FloatArray(n) { i ->
        val cutoff = fromHz * exp(logRatio * i / maxOf(1, n - 1))
        val g = tan(PI * cutoff / sr)
        val a1 = 1.0 / (1.0 + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2
        val v0 = y[i].toDouble()
        val v3 = v0 - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        when (type) {
            FilterType.LOWPASS -> v2
            FilterType.HIGHPASS -> v0 - k * v1 - v2
        }.toFloat()
    }
}
