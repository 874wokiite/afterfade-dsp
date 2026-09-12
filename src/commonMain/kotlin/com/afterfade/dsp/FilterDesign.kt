package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Filter shape for [butterworth].
 *
 * Band-pass has its own entry point, [butterworthBandpass], because it takes two edge frequencies.
 */
enum class FilterType {
    /** Passes everything below the cutoff. */
    LOWPASS,

    /** Passes everything above the cutoff. */
    HIGHPASS,
}

/**
 * `scipy.signal.butter(order, cutoffHz / (sampleRate / 2), type, output="sos")`.
 *
 * Designs a Butterworth low-pass or high-pass filter for *your* sample rate and returns it in the
 * same second-order-section form as [SosFilters], so the result can go straight into [sosfilt]:
 *
 * ```kotlin
 * val sos = butterworth(4, 12000.0, sr)          // low-pass, 4th order
 * val y = sosfilt(sos, audio.samples)
 * ```
 *
 * The design follows scipy step for step — analog Butterworth prototype poles, frequency pre-warp,
 * bilinear transform, then `zpk2sos` pairing — so the [SosFilters] tables can be reproduced from
 * the parameters in their KDoc.
 *
 * @param order filter order, at least 1. Roll-off is 6 dB per octave per order.
 * @param cutoffHz −3 dB frequency, strictly between 0 and `sampleRate / 2`.
 * @param sampleRate sample rate of the signal the filter will run on, in Hz.
 * @return `ceil(order / 2)` sections of six coefficients `[b0, b1, b2, a0, a1, a2]`.
 */
fun butterworth(
    order: Int,
    cutoffHz: Double,
    sampleRate: Int,
    type: FilterType = FilterType.LOWPASS,
): Array<DoubleArray> {
    require(order >= 1) { "order must be at least 1, was $order" }
    require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    val nyquist = sampleRate / 2.0
    require(cutoffHz > 0.0 && cutoffHz < nyquist) {
        "cutoffHz must be in (0, $nyquist), was $cutoffHz"
    }

    // scipy.signal.iirfilter: digital designs pre-warp against a notional fs = 2.
    val warped = 4.0 * tan(PI * (cutoffHz / nyquist) / 2.0)

    val proto = buttap(order)
    val shifted = when (type) {
        FilterType.LOWPASS -> lp2lp(proto, warped)
        FilterType.HIGHPASS -> lp2hp(proto, warped)
    }
    val digital = bilinear(shifted)
    return zpk2sos(digital)
}

/**
 * `scipy.signal.butter(order, [lowHz, highHz] / (sampleRate / 2), "band", output="sos")`.
 *
 * A band-pass of the given [order] has `2 * order` poles and therefore [order] sections — twice
 * the roll-off count of [butterworth] at the same order.
 *
 * @param order prototype order, at least 1.
 * @param lowHz lower −3 dB edge, strictly above 0.
 * @param highHz upper −3 dB edge, strictly above [lowHz] and below `sampleRate / 2`.
 * @return [order] sections of six coefficients `[b0, b1, b2, a0, a1, a2]`.
 */
fun butterworthBandpass(
    order: Int,
    lowHz: Double,
    highHz: Double,
    sampleRate: Int,
): Array<DoubleArray> {
    require(order >= 1) { "order must be at least 1, was $order" }
    require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    val nyquist = sampleRate / 2.0
    require(lowHz > 0.0 && lowHz < nyquist) { "lowHz must be in (0, $nyquist), was $lowHz" }
    require(highHz > lowHz && highHz < nyquist) {
        "highHz must be in ($lowHz, $nyquist), was $highHz"
    }

    val warpedLow = 4.0 * tan(PI * (lowHz / nyquist) / 2.0)
    val warpedHigh = 4.0 * tan(PI * (highHz / nyquist) / 2.0)
    val bw = warpedHigh - warpedLow
    val wo = sqrt(warpedLow * warpedHigh)

    val digital = bilinear(lp2bp(buttap(order), wo, bw))
    return zpk2sos(digital)
}

// --- complex arithmetic -------------------------------------------------------------------------

private class Cx(val re: Double, val im: Double)

private operator fun Cx.plus(o: Cx) = Cx(re + o.re, im + o.im)

private operator fun Cx.minus(o: Cx) = Cx(re - o.re, im - o.im)

private operator fun Cx.times(o: Cx) = Cx(re * o.re - im * o.im, re * o.im + im * o.re)

private operator fun Cx.times(s: Double) = Cx(re * s, im * s)

private operator fun Cx.div(o: Cx): Cx {
    val d = o.re * o.re + o.im * o.im
    return Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
}

private fun Cx.conj() = Cx(re, -im)

private fun Cx.abs() = hypot(re, im)

private fun Cx.isReal() = im == 0.0

/** Principal square root, the branch `numpy.sqrt` takes for complex input. */
private fun Cx.sqrtC(): Cx {
    if (im == 0.0 && re >= 0.0) return Cx(sqrt(re), 0.0)
    val m = abs()
    val r = sqrt((m + re) / 2.0)
    val i = sqrt((m - re) / 2.0)
    return Cx(r, if (im < 0.0) -i else i)
}

private val ZERO = Cx(0.0, 0.0)
private val ONE = Cx(1.0, 0.0)

/** Zeros, poles and gain of a filter, the `zpk` triple scipy passes around. */
private class Zpk(val z: List<Cx>, val p: List<Cx>, val k: Double)

private fun Zpk.relativeDegree() = p.size - z.size

// --- prototype and frequency transforms ---------------------------------------------------------

/** `scipy.signal.buttap(n)` — poles of the analog Butterworth prototype, no zeros, unit gain. */
private fun buttap(n: Int): Zpk {
    val poles = ArrayList<Cx>(n)
    for (i in 0 until n) {
        val m = -n + 1 + 2 * i
        val angle = PI * m / (2.0 * n)
        poles.add(Cx(-cos(angle), -sin(angle)))
    }
    return Zpk(emptyList(), poles, 1.0)
}

/** `scipy.signal.lp2lp_zpk`. */
private fun lp2lp(f: Zpk, wo: Double): Zpk {
    val degree = f.relativeDegree()
    var gain = f.k
    repeat(degree) { gain *= wo }
    return Zpk(f.z.map { it * wo }, f.p.map { it * wo }, gain)
}

/** `scipy.signal.lp2hp_zpk`. */
private fun lp2hp(f: Zpk, wo: Double): Zpk {
    val degree = f.relativeDegree()
    val woC = Cx(wo, 0.0)
    val zeros = ArrayList<Cx>(f.z.size + degree)
    f.z.forEach { zeros.add(woC / it) }
    repeat(degree) { zeros.add(ZERO) }
    val poles = f.p.map { woC / it }

    var num = ONE
    f.z.forEach { num *= (ZERO - it) }
    var den = ONE
    f.p.forEach { den *= (ZERO - it) }
    return Zpk(zeros, poles, f.k * (num / den).re)
}

/** `scipy.signal.lp2bp_zpk`. */
private fun lp2bp(f: Zpk, wo: Double, bw: Double): Zpk {
    val degree = f.relativeDegree()
    val wo2 = Cx(wo * wo, 0.0)

    fun split(points: List<Cx>): List<Cx> {
        val lp = points.map { it * (bw / 2.0) }
        val roots = lp.map { (it * it - wo2).sqrtC() }
        val out = ArrayList<Cx>(lp.size * 2)
        for (i in lp.indices) out.add(lp[i] + roots[i])
        for (i in lp.indices) out.add(lp[i] - roots[i])
        return out
    }

    val zeros = ArrayList(split(f.z))
    repeat(degree) { zeros.add(ZERO) }
    var gain = f.k
    repeat(degree) { gain *= bw }
    return Zpk(zeros, split(f.p), gain)
}

/** `scipy.signal.bilinear_zpk(z, p, k, fs = 2)`. */
private fun bilinear(f: Zpk): Zpk {
    val degree = f.relativeDegree()
    val fs2 = Cx(4.0, 0.0) // 2 * fs, with fs = 2

    val zeros = ArrayList<Cx>(f.z.size + degree)
    f.z.forEach { zeros.add((fs2 + it) / (fs2 - it)) }
    // Zeros that sat at infinity land on Nyquist.
    repeat(degree) { zeros.add(Cx(-1.0, 0.0)) }
    val poles = f.p.map { (fs2 + it) / (fs2 - it) }

    var num = ONE
    f.z.forEach { num *= (fs2 - it) }
    var den = ONE
    f.p.forEach { den *= (fs2 - it) }
    return Zpk(zeros, poles, f.k * (num / den).re)
}

// --- zpk -> sos ---------------------------------------------------------------------------------

private const val CPLX_TOL = 100.0 * 2.220446049250313e-16

/**
 * `scipy.signal._cplxreal` — splits into one representative per conjugate pair and the real values.
 *
 * The pairs come back sorted by real part then by magnitude of the imaginary part, which is what
 * fixes the order scipy's `zpk2sos` sees and therefore the order of the sections.
 */
private fun cplxreal(values: List<Cx>): List<Cx> {
    if (values.isEmpty()) return emptyList()
    val sorted = values.sortedWith(compareBy({ it.re }, { abs(it.im) }))

    val reals = ArrayList<Cx>()
    val pos = ArrayList<Cx>()
    val neg = ArrayList<Cx>()
    for (v in sorted) {
        when {
            abs(v.im) <= CPLX_TOL * v.abs() -> reals.add(Cx(v.re, 0.0))
            v.im > 0.0 -> pos.add(v)
            else -> neg.add(v)
        }
    }
    check(pos.size == neg.size) { "complex value without a matching conjugate" }

    // Within a run of (approximately) equal real parts, order by |imag|.
    var start = 0
    while (start < pos.size) {
        var stop = start + 1
        while (stop < pos.size && pos[stop].re - pos[stop - 1].re <= CPLX_TOL * pos[stop - 1].abs()) {
            stop++
        }
        if (stop - start > 1) {
            val pSlice = pos.subList(start, stop).sortedBy { abs(it.im) }
            val nSlice = neg.subList(start, stop).sortedBy { abs(it.im) }
            for (i in pSlice.indices) {
                pos[start + i] = pSlice[i]
                neg[start + i] = nSlice[i]
            }
        }
        start = stop
    }

    // Average out the numerical difference between each pair's two halves, as scipy does.
    val paired = ArrayList<Cx>(pos.size + reals.size)
    for (i in pos.indices) {
        val a = pos[i]
        val b = neg[i].conj()
        paired.add(Cx((a.re + b.re) / 2.0, (a.im + b.im) / 2.0))
    }
    paired.addAll(reals)
    return paired
}

/** Index of the pole closest to the unit circle — scipy's `idx_worst` for digital filters. */
private fun idxWorst(p: List<Cx>): Int {
    var best = 0
    var bestVal = abs(1.0 - p[0].abs())
    for (i in 1 until p.size) {
        val v = abs(1.0 - p[i].abs())
        if (v < bestVal) {
            bestVal = v
            best = i
        }
    }
    return best
}

/** `scipy.signal._nearest_real_complex_idx`; [which] is "real", "complex" or "any". */
private fun nearestIdx(from: List<Cx>, to: Cx, which: String): Int {
    val order = from.indices.sortedBy { (from[it] - to).abs() }
    if (which == "any") return order[0]
    val wantReal = which == "real"
    return order.first { from[it].isReal() == wantReal }
}

/** `numpy.poly` for roots that are either real or a conjugate pair: returns real coefficients. */
private fun poly(roots: List<Cx>): DoubleArray {
    var a = mutableListOf(ONE)
    for (root in roots) {
        val next = MutableList(a.size + 1) { ZERO }
        for (i in a.indices) {
            next[i] = next[i] + a[i]
            next[i + 1] = next[i + 1] - a[i] * root
        }
        a = next
    }
    return DoubleArray(a.size) { a[it].re }
}

/** `scipy.signal._single_zpksos` — one section from up to two zeros and two poles. */
private fun singleSos(z: List<Cx>, p: List<Cx>): DoubleArray {
    val sos = DoubleArray(6)
    val b = poly(z)
    val a = poly(p)
    for (i in b.indices) sos[3 - b.size + i] = b[i]
    for (i in a.indices) sos[6 - a.size + i] = a[i]
    return sos
}

/**
 * `scipy.signal.zpk2sos(z, p, k, pairing = "nearest")`.
 *
 * Poles are taken worst-first — closest to the unit circle — and filled in from the last section
 * backwards, so the sections come out ordered least peaky to most peaky. Each pole (pair) takes the
 * nearest remaining zero (pair) with it. The whole gain goes into the first section's numerator.
 */
private fun zpk2sos(f: Zpk): Array<DoubleArray> {
    val zPadded = ArrayList(f.z)
    val pPadded = ArrayList(f.p)
    while (pPadded.size < zPadded.size) pPadded.add(ZERO)
    while (zPadded.size < pPadded.size) zPadded.add(ZERO)
    val nSections = (maxOf(pPadded.size, zPadded.size) + 1) / 2

    val z = ArrayList(cplxreal(zPadded))
    val p = ArrayList(cplxreal(pPadded))

    val sos = Array(nSections) { DoubleArray(6) }
    for (si in nSections - 1 downTo 0) {
        val p1 = p.removeAt(idxWorst(p))

        if (p1.isReal() && p.none { it.isReal() }) {
            // Last real pole: pair it with the nearest real zero, first order padded to second.
            val z1 = z.removeAt(nearestIdx(z, p1, "real"))
            sos[si] = singleSos(listOf(z1, ZERO), listOf(p1, ZERO))
        } else if (p.size + 1 == z.size && !p1.isReal() &&
            z.count { it.isReal() } == 1 && p.none { it.isReal() }
        ) {
            // One real pole and one real zero left over: this pair must take a complex zero.
            val z1 = z.removeAt(nearestIdx(z, p1, "complex"))
            sos[si] = singleSos(listOf(z1, z1.conj()), listOf(p1, p1.conj()))
        } else {
            val p2 = if (p1.isReal()) {
                val realIdx = p.indices.filter { p[it].isReal() }
                p.removeAt(realIdx[idxWorst(realIdx.map { p[it] })])
            } else {
                p1.conj()
            }
            if (z.isNotEmpty()) {
                val z1 = z.removeAt(nearestIdx(z, p1, "any"))
                if (!z1.isReal()) {
                    sos[si] = singleSos(listOf(z1, z1.conj()), listOf(p1, p2))
                } else if (z.isNotEmpty()) {
                    val z2 = z.removeAt(nearestIdx(z, p1, "real"))
                    sos[si] = singleSos(listOf(z1, z2), listOf(p1, p2))
                } else {
                    sos[si] = singleSos(listOf(z1, ZERO), listOf(p1, p2))
                }
            } else {
                sos[si] = singleSos(emptyList(), listOf(p1, p2))
            }
        }
    }

    for (i in 0..2) sos[0][i] *= f.k
    return sos
}
