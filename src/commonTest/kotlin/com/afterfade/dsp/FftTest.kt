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
