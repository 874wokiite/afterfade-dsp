package com.afterfade.dsp

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT primitives matching `numpy.fft` semantics.
 *
 * Power-of-two sizes go through an iterative radix-2 Cooley-Tukey transform; every other size
 * goes through Bluestein's chirp-z algorithm (needed because `scipy.signal.resample` takes the
 * FFT of the whole signal, whose length is arbitrary).
 *
 * All transforms use the numpy sign convention: forward is `sum x[j] * exp(-2i*pi*j*k/n)`.
 */

/** Complex spectrum as split real/imaginary arrays. */
class Spectrum(val re: DoubleArray, val im: DoubleArray) {
    val size: Int get() = re.size
}

/** Reusable power-of-two FFT plan (twiddle factors are computed once). */
class Radix2Fft(val n: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "Radix2Fft size must be a power of two, got $n" }
        val half = n / 2
        cosTable = DoubleArray(half) { cos(2.0 * PI * it / n) }
        sinTable = DoubleArray(half) { sin(2.0 * PI * it / n) }
    }

    /**
     * In-place forward DFT (negative exponent, unnormalized).
     *
     * The result is bit-for-bit the textbook iterative radix-2 transform (bit-reverse, then one pass
     * per stage). Only the *order* in which butterflies run differs: the stages that fit inside a
     * [CACHE_BLOCK]-sized block are run block by block, so a long transform walks the arrays once
     * for those stages instead of once per stage. A butterfly's inputs come from its own block in
     * the previous stage, so every butterfly still sees exactly the same operands and the same
     * twiddle, and the rounding is unchanged. Bluestein on a whole track (2^21 points) is where
     * this matters.
     */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == n && im.size == n) { "expected arrays of length $n" }
        if (n == 1) return

        bitReverse(re, im)

        val block = minOf(n, CACHE_BLOCK)
        var start = 0
        while (start < n) {
            var size = 2
            while (size <= block) {
                stage(re, im, size, start, start + block)
                size *= 2
            }
            start += block
        }
        var size = block * 2
        while (size <= n) {
            stage(re, im, size, 0, n)
            size *= 2
        }
    }

    /** In-place inverse DFT (positive exponent), **unnormalized** — divide by [n] yourself. */
    fun inverse(re: DoubleArray, im: DoubleArray) = forward(im, re)

    /** One radix-2 stage of butterfly width [size], over the blocks in `from until to`. */
    private fun stage(re: DoubleArray, im: DoubleArray, size: Int, from: Int, to: Int) {
        val halfSize = size / 2
        val tableStep = n / size
        // Each operand is read once into a local. The expressions are the textbook ones term for
        // term (`re[j] += t` is `re[j] = re[j] + t`), so the rounding is identical; this only saves
        // the repeated array and field loads, which a debuggable ART does not hoist on its own.
        val cosT = cosTable
        val sinT = sinTable
        var i = from
        while (i < to) {
            var j = i
            var k = 0
            val end = i + halfSize
            while (j < end) {
                val l = j + halfSize
                val c = cosT[k]
                val s = sinT[k]
                val xr = re[l]
                val xi = im[l]
                val tpre = xr * c + xi * s
                val tpim = -xr * s + xi * c
                val yr = re[j]
                val yi = im[j]
                re[l] = yr - tpre
                im[l] = yi - tpim
                re[j] = yr + tpre
                im[j] = yi + tpim
                j++
                k += tableStep
            }
            i += size
        }
    }

    /**
     * The bit-reversal permutation, walking the reversed index incrementally instead of reversing
     * every index bit by bit (log2 n steps each). Same permutation, a fraction of the work.
     */
    private fun bitReverse(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 0 until n - 1) {
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
        }
    }

    private companion object {
        /** 4096 complex points = 64 KB of re + im, which stays in L1/L2 on phones and desktops. */
        const val CACHE_BLOCK = 4096
    }
}

object Fft {

    /**
     * Plans by size, replaced wholesale on every insert (copy-on-write) and never mutated in place.
     *
     * Callers run transforms from several threads at once (Afterfade pitch-shifts accents in
     * parallel), and a plain `HashMap` written concurrently can corrupt itself. Two threads that
     * miss the same size at once each build a plan and one insert is lost — harmless, since the
     * tables are identical — but no reader ever sees a half-written map.
     */
    @Volatile
    private var planCache: Map<Int, Radix2Fft> = emptyMap()

    /**
     * Largest plan kept for the life of the process. STFT frames and the resample of a short clip
     * reuse their plans constantly, so those stay. Bluestein over a whole track needs 2^21 points,
     * whose twiddle tables alone are 16 MB; keeping that around for good would hold it on a phone
     * heap long after the one transform that needed it. Larger plans are built per call (one
     * Bluestein reuses its plan for all three of its transforms) and left to the GC.
     */
    private const val MAX_CACHED_PLAN = 1 shl 19

    internal fun cachedPlan(n: Int): Radix2Fft {
        planCache[n]?.let { return it }
        val plan = Radix2Fft(n)
        if (n <= MAX_CACHED_PLAN) planCache = planCache + (n to plan)
        return plan
    }

    /** In-place forward DFT for any length (radix-2 when possible, Bluestein otherwise). */
    fun forward(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(im.size == n) { "re/im length mismatch" }
        if (n == 0) return
        if (n and (n - 1) == 0) cachedPlan(n).forward(re, im) else bluestein(re, im)
    }

    /** In-place inverse DFT for any length, **unnormalized**. */
    fun inverse(re: DoubleArray, im: DoubleArray) = forward(im, re)

    /** `np.fft.rfft(x)` — returns bins `0..n/2`. */
    fun rfft(x: DoubleArray): Spectrum = rfftInPlace(x.copyOf())

    /** `np.fft.rfft(x)` for a float32 signal (widened to float64 first, like numpy does). */
    fun rfft(x: FloatArray): Spectrum = rfftInPlace(DoubleArray(x.size) { x[it].toDouble() })

    /** [rfft] that transforms [re] itself — the caller hands over a copy it no longer needs. */
    private fun rfftInPlace(re: DoubleArray): Spectrum {
        val n = re.size
        val im = DoubleArray(n)
        forward(re, im)
        val bins = n / 2 + 1
        return Spectrum(re.copyOf(bins), im.copyOf(bins))
    }

    /**
     * `np.fft.irfft(spectrum, n)`.
     *
     * [re]/[im] may be shorter than `n/2 + 1`; missing bins are treated as zero (numpy pads).
     * Matching numpy exactly, the imaginary part of the DC bin — and of the Nyquist bin when [n]
     * is even — is **ignored**, because the half-complex layout has no room to store them.
     * The phase vocoder relies on this: it writes non-zero imaginary parts into both.
     */
    fun irfft(re: DoubleArray, im: DoubleArray, n: Int): DoubleArray {
        require(n > 0) { "irfft length must be positive" }
        require(re.size == im.size) { "re/im length mismatch" }
        val bins = n / 2 + 1
        val available = minOf(re.size, bins)

        val fullRe = DoubleArray(n)
        val fullIm = DoubleArray(n)
        if (available > 0) fullRe[0] = re[0] // imaginary part of DC dropped

        val lastPaired = if (n % 2 == 0) (n / 2 - 1) else ((n - 1) / 2)
        for (k in 1..minOf(lastPaired, available - 1)) {
            fullRe[k] = re[k]
            fullIm[k] = im[k]
            fullRe[n - k] = re[k]
            fullIm[n - k] = -im[k]
        }
        if (n % 2 == 0 && available > n / 2) {
            fullRe[n / 2] = re[n / 2] // imaginary part of Nyquist dropped
        }

        inverse(fullRe, fullIm)
        val scale = 1.0 / n
        for (i in 0 until n) fullRe[i] *= scale
        return fullRe
    }

    /**
     * Bluestein's chirp-z algorithm — DFT of an arbitrary length expressed as a convolution
     * that a power-of-two FFT can evaluate.
     */
    private fun bluestein(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var m = 1
        while (m < 2 * n - 1) {
            m = m shl 1
            require(m > 0) { "signal too long for Bluestein FFT: $n" }
        }

        // exp(-i*pi*k^2/n); k^2 is reduced mod 2n first to keep the angle small and accurate.
        val cosT = DoubleArray(n)
        val sinT = DoubleArray(n)
        for (i in 0 until n) {
            val j = (i.toLong() * i % (2L * n)).toInt()
            val angle = PI * j / n
            cosT[i] = cos(angle)
            sinT[i] = sin(angle)
        }

        val aRe = DoubleArray(m)
        val aIm = DoubleArray(m)
        for (i in 0 until n) {
            aRe[i] = re[i] * cosT[i] + im[i] * sinT[i]
            aIm[i] = -re[i] * sinT[i] + im[i] * cosT[i]
        }

        val bRe = DoubleArray(m)
        val bIm = DoubleArray(m)
        bRe[0] = cosT[0]
        bIm[0] = sinT[0]
        for (i in 1 until n) {
            bRe[i] = cosT[i]
            bRe[m - i] = cosT[i]
            bIm[i] = sinT[i]
            bIm[m - i] = sinT[i]
        }

        val plan = cachedPlan(m)
        plan.forward(aRe, aIm)
        plan.forward(bRe, bIm)
        for (i in 0 until m) {
            val tr = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            aIm[i] = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = tr
        }
        plan.inverse(aRe, aIm)
        val scale = 1.0 / m
        for (i in 0 until m) {
            aRe[i] *= scale
            aIm[i] *= scale
        }

        for (i in 0 until n) {
            re[i] = aRe[i] * cosT[i] + aIm[i] * sinT[i]
            im[i] = -aRe[i] * sinT[i] + aIm[i] * cosT[i]
        }
    }
}

/**
 * Real FFT helper for a fixed power-of-two frame size — reuses one plan across every frame of an
 * STFT loop, which is the engine's hottest path (`_onset_strength`, `_phase_vocoder`).
 */
class RealFftPlan(val n: Int) {
    private val plan = Fft.cachedPlan(n)
    private val workRe = DoubleArray(n)
    private val workIm = DoubleArray(n)

    /** Number of bins produced by [rfft] (`n/2 + 1`). */
    val bins: Int = n / 2 + 1

    /** `np.fft.rfft(frame)` writing into [outRe]/[outIm] (each of length [bins]). */
    fun rfft(frame: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        require(frame.size == n) { "frame length ${frame.size} != $n" }
        frame.copyInto(workRe)
        workIm.fill(0.0)
        plan.forward(workRe, workIm)
        workRe.copyInto(outRe, 0, 0, bins)
        workIm.copyInto(outIm, 0, 0, bins)
    }

    /** `np.fft.irfft(spectrum, n=n)` writing into [out] (length [n]). See [Fft.irfft] for the DC/Nyquist rule. */
    fun irfft(re: DoubleArray, im: DoubleArray, out: DoubleArray) {
        require(out.size == n) { "out length ${out.size} != $n" }
        workRe.fill(0.0)
        workIm.fill(0.0)
        workRe[0] = re[0]
        for (k in 1 until n / 2) {
            workRe[k] = re[k]
            workIm[k] = im[k]
            workRe[n - k] = re[k]
            workIm[n - k] = -im[k]
        }
        workRe[n / 2] = re[n / 2]
        plan.inverse(workRe, workIm)
        val scale = 1.0 / n
        for (i in 0 until n) out[i] = workRe[i] * scale
    }
}
