package com.afterfade.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Gain, mixing, clipping, compression and sidechain ducking.
 *
 * ゲイン・ミックス・クリップ・コンプレッサー・サイドチェイン。
 */

/** [y] scaled by [gain]. */
fun gain(y: FloatArray, gain: Double): FloatArray {
    val g = gain.toFloat()
    return FloatArray(y.size) { y[it] * g }
}

/** Sum of [tracks], as long as the longest one. */
fun mix(vararg tracks: FloatArray): FloatArray {
    val out = FloatArray(tracks.maxOfOrNull { it.size } ?: 0)
    for (t in tracks) for (i in t.indices) out[i] += t[i]
    return out
}

/** Clip every sample to ±[ceiling]: harsh digital distortion, unlike [softSaturate]. */
fun hardClip(y: FloatArray, ceiling: Float = 1f): FloatArray {
    require(ceiling > 0f) { "ceiling must be positive, was $ceiling" }
    return FloatArray(y.size) { y[it].coerceIn(-ceiling, ceiling) }
}

/**
 * Duck [y] at every sample position in [triggers]: the gain drops to `1 - depth` at the trigger
 * and climbs back linearly to 1 over [releaseSec]. Overlapping dips keep the deeper one.
 *
 * Pass the kick's positions to make pads and basses breathe with it.
 */
fun sidechainDuck(y: FloatArray, triggers: IntArray, sr: Int, depth: Double, releaseSec: Double): FloatArray {
    require(depth in 0.0..1.0) { "depth must be in [0, 1], was $depth" }
    val gainCurve = FloatArray(y.size) { 1f }
    val len = maxOf(1, (releaseSec * sr).toInt())
    for (t in triggers) {
        for (j in 0 until len) {
            val i = t + j
            if (i < 0) continue
            if (i >= y.size) break
            val g = (1.0 - depth + depth * j / len).toFloat()
            if (g < gainCurve[i]) gainCurve[i] = g
        }
    }
    return applyEnvelope(y, gainCurve)
}

/**
 * Feed-forward peak compressor.
 *
 * Above [thresholdDb] (dBFS) the level is reduced by [ratio]:1. The detector follows the signal
 * with one-pole [attackSec] / [releaseSec] smoothing; [makeupDb] is added afterwards.
 */
fun compressor(
    y: FloatArray,
    sr: Int,
    thresholdDb: Double,
    ratio: Double,
    attackSec: Double = 0.005,
    releaseSec: Double = 0.1,
    makeupDb: Double = 0.0,
): FloatArray {
    require(ratio >= 1.0) { "ratio must be at least 1, was $ratio" }
    val attack = exp(-1.0 / (maxOf(attackSec, 1e-6) * sr))
    val release = exp(-1.0 / (maxOf(releaseSec, 1e-6) * sr))
    val out = FloatArray(y.size)
    var env = 0.0
    for (i in y.indices) {
        val level = abs(y[i].toDouble())
        val coeff = if (level > env) attack else release
        env = coeff * env + (1.0 - coeff) * level
        val envDb = if (env > 1e-9) 20.0 * log10(env) else -180.0
        val over = envDb - thresholdDb
        val reductionDb = if (over > 0.0) over * (1.0 - 1.0 / ratio) else 0.0
        out[i] = (y[i] * 10.0.pow((makeupDb - reductionDb) / 20.0)).toFloat()
    }
    return out
}
