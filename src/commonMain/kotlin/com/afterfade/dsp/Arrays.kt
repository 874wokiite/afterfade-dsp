package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * FloatArray/DoubleArray utilities mirroring the numpy helpers used by the Python engine.
 *
 * Every function here is a deliberate port of a specific numpy call — the formulas follow
 * numpy's own implementation (not just its documented behaviour) so that the Kotlin engine
 * reproduces the reference output bit-for-bit where numpy works in float64.
 */

/** `np.hanning(n)` — symmetric Hann window (float64). */
fun hanning(n: Int): DoubleArray {
    if (n < 1) return DoubleArray(0)
    if (n == 1) return doubleArrayOf(1.0)
    // numpy: n = arange(1-M, M, 2); 0.5 + 0.5 * cos(pi * n / (M - 1))
    val denom = (n - 1).toDouble()
    return DoubleArray(n) { i ->
        val k = (2 * i - n + 1).toDouble()
        0.5 + 0.5 * cos(PI * k / denom)
    }
}

/** `np.linspace(start, stop, num, endpoint=...)` (float64). */
fun linspace(start: Double, stop: Double, num: Int, endpoint: Boolean = true): DoubleArray {
    if (num <= 0) return DoubleArray(0)
    if (num == 1) return doubleArrayOf(start)
    val div = if (endpoint) (num - 1) else num
    val step = (stop - start) / div
    val out = DoubleArray(num) { start + it * step }
    if (endpoint) out[num - 1] = stop
    return out
}

/** `np.fft.rfftfreq(n, 1.0 / sr)`. */
fun rfftFreq(n: Int, sr: Int): DoubleArray {
    val value = 1.0 / (n * (1.0 / sr))
    return DoubleArray(n / 2 + 1) { it * value }
}

/** Index folding used by `np.pad(..., mode="reflect")` (edge sample is not repeated). */
private fun reflectIndex(index: Int, n: Int): Int {
    if (n <= 1) return 0
    val period = 2 * n - 2
    var k = index % period
    if (k < 0) k += period
    return if (k >= n) period - k else k
}

/** `np.pad(x, (pad, pad), mode="reflect")`. */
fun padReflect(x: FloatArray, pad: Int): FloatArray {
    if (pad <= 0) return x.copyOf()
    return FloatArray(x.size + 2 * pad) { x[reflectIndex(it - pad, x.size)] }
}

/** `np.pad(x, (pad, pad), mode="reflect")` for float64 input. */
fun padReflect(x: DoubleArray, pad: Int): DoubleArray {
    if (pad <= 0) return x.copyOf()
    return DoubleArray(x.size + 2 * pad) { x[reflectIndex(it - pad, x.size)] }
}

/** `np.pad(x, (pad, pad), mode="edge")`. */
fun padEdge(x: FloatArray, pad: Int): FloatArray {
    if (pad <= 0) return x.copyOf()
    val n = x.size
    return FloatArray(n + 2 * pad) { x[(it - pad).coerceIn(0, n - 1)] }
}

/** `np.median(x[from until to])` — float32 arithmetic, matching numpy's float32 path. */
fun median(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
    val count = to - from
    require(count > 0) { "median of empty range" }
    val slice = x.copyOfRange(from, to)
    slice.sort()
    val mid = count / 2
    return if (count % 2 == 1) slice[mid] else ((slice[mid - 1] + slice[mid]) / 2f)
}

/** `np.median(list)` for float64 values. */
fun median(values: DoubleArray): Double {
    require(values.isNotEmpty()) { "median of empty array" }
    val slice = values.copyOf()
    slice.sort()
    val mid = slice.size / 2
    return if (slice.size % 2 == 1) slice[mid] else (slice[mid - 1] + slice[mid]) / 2.0
}

/** `np.abs(x).max()`, 0 for an empty array. */
fun absMax(x: FloatArray): Float {
    var peak = 0f
    for (v in x) {
        val a = abs(v)
        if (a > peak) peak = a
    }
    return peak
}

/** Scale so that the absolute peak equals [target]. No-op for silence. Returns a new array. */
fun peakNormalized(x: FloatArray, target: Float): FloatArray {
    val peak = absMax(x)
    if (peak <= 0f) return x.copyOf()
    val gain = target / peak
    return FloatArray(x.size) { x[it] * gain }
}

/** `np.clip(v, lo, hi)`. */
fun clip(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

/**
 * Round half to even — Python's `round()` / `np.round()`.
 *
 * The engine quantises MIDI notes and frame counts with these, so half-up rounding would
 * silently pick a different note or frame length at exact `.5` boundaries.
 */
fun rint(x: Double): Double {
    val f = floor(x)
    val diff = x - f
    return when {
        diff > 0.5 -> f + 1.0
        diff < 0.5 -> f
        else -> if (f.toLong() % 2L == 0L) f else f + 1.0
    }
}

/** `templates.lofi._add_at` — mix [sound] into [buf] at [pos], clipping at the buffer end. */
fun addAt(buf: FloatArray, sound: FloatArray, pos: Int) {
    if (pos < 0 || pos >= buf.size) return
    val end = minOf(pos + sound.size, buf.size)
    for (i in pos until end) buf[i] += sound[i - pos]
}
