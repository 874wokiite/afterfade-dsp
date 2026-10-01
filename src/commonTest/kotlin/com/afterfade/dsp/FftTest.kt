package com.afterfade.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun irfftIgnoresDcAndNyquistImaginaryPartsLikeNumpy() {
        // Verified against numpy: np.fft.irfft is unchanged when imag(DC) / imag(Nyquist) are zeroed.
        val re = doubleArrayOf(1.0, 2.0, 0.5, -1.0, 2.0)
        val withImag = doubleArrayOf(3.0, -1.0, 0.2, 0.7, 5.0)
        val zeroed = doubleArrayOf(0.0, -1.0, 0.2, 0.7, 0.0)
        val a = Fft.irfft(re, withImag, 8)
        val b = Fft.irfft(re, zeroed, 8)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-15)
        // numpy reference for this exact input
        val expected = doubleArrayOf(0.75, 0.40836309, 0.675, -0.55229708, 0.25, -0.75836309, -0.175, 0.40229708)
        for (i in expected.indices) assertEquals(expected[i], a[i], 1e-8)
    }

    /**
     * The blocked stage order must not change a single bit: Afterfade's golden checksums (and the
     * same-cycle-same-song promise) depend on it. Compared against the textbook loop, kept here,
     * at sizes below, at and above the cache block.
     */
    @Test
    fun radix2MatchesTheTextbookTransformBitForBit() {
        for (n in listOf(1, 2, 8, 1024, 4096, 8192, 1 shl 16)) {
            val re = DoubleArray(n) { kotlin.math.sin(0.37 * it) + 0.25 * kotlin.math.cos(1.9 * it) }
            val im = DoubleArray(n) { 0.1 * kotlin.math.sin(2.3 * it) }
            val expectedRe = re.copyOf()
            val expectedIm = im.copyOf()
            textbookRadix2(expectedRe, expectedIm)
            Radix2Fft(n).forward(re, im)
            for (i in 0 until n) {
                assertEquals(expectedRe[i].toRawBits(), re[i].toRawBits(), "re[$i] differs at n=$n")
                assertEquals(expectedIm[i].toRawBits(), im[i].toRawBits(), "im[$i] differs at n=$n")
            }
        }
    }

    /** The transform as it was written before the stages were blocked. */
    private fun textbookRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 1) return
        var levels = 0
        while ((1 shl levels) < n) levels++
        val cosTable = DoubleArray(n / 2) { kotlin.math.cos(2.0 * kotlin.math.PI * it / n) }
        val sinTable = DoubleArray(n / 2) { kotlin.math.sin(2.0 * kotlin.math.PI * it / n) }
        for (i in 0 until n) {
            var j = 0
            var v = i
            repeat(levels) { j = (j shl 1) or (v and 1); v = v shr 1 }
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val tableStep = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + halfSize) {
                    val l = j + halfSize
                    val tpre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    j++
                    k += tableStep
                }
                i += size
            }
            size *= 2
        }
    }

    @Test
    fun bluesteinRoundTripsForAwkwardLengths() {
        for (n in listOf(1, 3, 5, 17, 1023, 4097)) {
            val x = DoubleArray(n) { kotlin.math.sin(0.37 * it) + 0.25 * kotlin.math.cos(1.9 * it) }
            val spectrum = Fft.rfft(x)
            val back = Fft.irfft(spectrum.re, spectrum.im, n)
            var worst = 0.0
            for (i in 0 until n) worst = maxOf(worst, abs(back[i] - x[i]))
            assertTrue(worst < 1e-10, "round trip failed for n=$n (max error $worst)")
        }
    }
}
