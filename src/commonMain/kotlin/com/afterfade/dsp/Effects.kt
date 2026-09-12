package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Port of `music_engine/audio/effects.py` — tape warble, vinyl noise and soft saturation.
 *
 * The lo-fi master chain that combines them (order and amounts) is a recipe and lives in the app.
 *
 * Note on precision: numpy (NEP 50) keeps a float32 array float32 when it is multiplied by a plain
 * Python float, so `soft_saturate` and the final peak normalisation are genuinely float32
 * arithmetic. The index maths in `tape_warble` is float64 because `np.arange(n) / sr` is. Both are
 * reproduced literally below.
 */

/** Vinyl noise seed, matching `np.random.default_rng(42)` in the Python engine. */
private const val VINYL_SEED = 42L

/** `tape_warble` — wow/flutter by modulating a fractional read position. */
fun tapeWarble(y: FloatArray, sr: Int, depth: Double = 0.002, rateHz: Double = 0.5): FloatArray {
    val n = y.size
    if (n == 0) return FloatArray(0)
    val out = FloatArray(n)
    val amount = depth * sr
    val omega = 2.0 * PI * rateHz

    for (i in 0 until n) {
        // np.arange(n) / sr first, then the sine argument — the operation order matters for the
        // last bits and this stage feeds a saturator.
        val mod = amount * sin(omega * (i.toDouble() / sr))
        val index = clip(i + mod, 0.0, (n - 1).toDouble())
        val floorIdx = index.toInt()
        val frac = index - floorIdx
        val ceilIdx = minOf(floorIdx + 1, n - 1)
        out[i] = (y[floorIdx] * (1.0 - frac) + y[ceilIdx] * frac).toFloat()
    }
    return out
}

/**
 * `vinyl_noise` — band-passed hiss/crackle.
 *
 * [raw] is a test seam: pass pre-generated noise for deterministic comparison.
 * When null the engine's own seeded RNG is used.
 *
 * [seed] picks which noise comes out. The same seed gives the same samples on every platform, and
 * the default (42) is the seed the Python engine used, so calls that leave it alone are unchanged.
 */
fun vinylNoise(
    length: Int,
    sr: Int,
    amplitude: Double = 0.005,
    raw: FloatArray? = null,
    seed: Long = VINYL_SEED,
): FloatArray {
    if (length <= 0) return FloatArray(0)
    val noise = raw ?: Rng(seed).gaussianNoise(length, amplitude)
    require(noise.size == length) { "vinyl noise length ${noise.size} != $length" }
    return sosfilt(SosFilters.BANDPASS_200_4000_HZ, noise)
}

/** `soft_saturate` — tanh soft clipping. The multiply happens in float32, like numpy. */
fun softSaturate(y: FloatArray, drive: Double = 0.7): FloatArray {
    val gain = (1.0 + drive).toFloat()
    return FloatArray(y.size) { tanh((y[it] * gain).toDouble()).toFloat() }
}
