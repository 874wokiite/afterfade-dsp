package com.afterfade.dsp

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Seeded pseudo random generator (SplitMix64).
 *
 * The Python engine uses `np.random.default_rng(seed)` (PCG64). Bit compatibility with numpy is
 * explicitly **not** a goal — reproducing PCG64 plus numpy's ziggurat normal sampler would be a
 * large amount of code for no musical benefit. What matters is that the Kotlin engine is
 * self-deterministic: the same fragments and template always produce the same track.
 *
 * SplitMix64 is implemented here rather than using `kotlin.random.Random` so the exact algorithm
 * stays under our control and cannot drift with a stdlib update.
 *
 * The seed conventions are kept identical to Python (transient count / 42 / 7) so the two engines
 * stay conceptually aligned.
 */
class Rng(seed: Long) {

    private var state: Long = seed
    private var spareGaussian: Double = 0.0
    private var hasSpareGaussian = false

    private fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform double in `[0, 1)`. */
    fun nextDouble(): Double = (next() ushr 11) * (1.0 / (1L shl 53))

    /** Uniform int in `[0, bound)`. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        // Rejection sampling keeps the distribution exactly uniform.
        val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound)
        while (true) {
            val bits = (next() ushr 33).toInt()
            if (bits < limit) return bits % bound
        }
    }

    /** Uniform int in `[from, until)` — mirrors `rng.integers(from, until)`. */
    fun nextIntInRange(from: Int, until: Int): Int {
        require(until > from) { "empty range [$from, $until)" }
        return from + nextInt(until - from)
    }

    /** Standard normal sample (Marsaglia polar method, values cached in pairs). */
    fun nextGaussian(): Double {
        if (hasSpareGaussian) {
            hasSpareGaussian = false
            return spareGaussian
        }
        while (true) {
            val u = 2.0 * nextDouble() - 1.0
            val v = 2.0 * nextDouble() - 1.0
            val s = u * u + v * v
            if (s <= 0.0 || s >= 1.0) continue
            val factor = sqrt(-2.0 * ln(s) / s)
            spareGaussian = v * factor
            hasSpareGaussian = true
            return u * factor
        }
    }

    /** `rng.normal(0, sigma, length)` as float32. */
    fun gaussianNoise(length: Int, sigma: Double): FloatArray =
        FloatArray(length) { (nextGaussian() * sigma).toFloat() }

    /** `rng.choice(values)` — one uniformly chosen element. */
    fun choice(values: List<Int>): Int {
        require(values.isNotEmpty()) { "cannot choose from an empty list" }
        return values[nextInt(values.size)]
    }

    /** `rng.choice(values, size=count, replace=False)` — [count] distinct elements. */
    fun sampleWithoutReplacement(values: List<Int>, count: Int): List<Int> {
        require(count <= values.size) { "cannot draw $count from ${values.size} values" }
        val pool = values.toMutableList()
        val picked = ArrayList<Int>(count)
        repeat(count) {
            val i = nextInt(pool.size)
            picked.add(pool[i])
            pool[i] = pool[pool.size - 1]
            pool.removeAt(pool.size - 1)
        }
        return picked
    }

    /** `rng.shuffle(values)` — in-place Fisher-Yates. */
    fun shuffle(values: MutableList<Int>) {
        for (i in values.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = values[i]
            values[i] = values[j]
            values[j] = tmp
        }
    }
}
