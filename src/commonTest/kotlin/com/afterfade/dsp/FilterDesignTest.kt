package com.afterfade.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilterDesignTest {

    private val sr = SosFilters.DESIGN_SAMPLE_RATE

    /** Asserts every coefficient matches within a relative tolerance of 1e-9. */
    private fun assertSosEquals(expected: Array<DoubleArray>, actual: Array<DoubleArray>, what: String) {
        assertEquals(expected.size, actual.size, "$what: section count")
        var maxDiff = 0.0
        for (s in expected.indices) {
            assertEquals(6, actual[s].size, "$what: section $s length")
            for (c in 0 until 6) {
                val e = expected[s][c]
                val a = actual[s][c]
                val diff = abs(e - a)
                if (diff > maxDiff) maxDiff = diff
                val tol = 1e-9 * maxOf(abs(e), 1e-6)
                assertTrue(diff <= tol, "$what: section $s coefficient $c, expected $e, got $a")
            }
        }
        println("$what: max absolute difference $maxDiff")
    }

    // --- the SosFilters tables, reproduced from the parameters in their KDoc ---------------------

    @Test
    fun reproducesLowpass800() {
        // butter(4, 800 / (44100/2), "low")
        assertSosEquals(
            SosFilters.LOWPASS_800_HZ,
            butterworth(4, 800.0, sr, FilterType.LOWPASS),
            "LOWPASS_800_HZ",
        )
    }

    @Test
    fun reproducesLowpass12000() {
        // butter(2, 12000 / (44100/2), "low")
        assertSosEquals(
            SosFilters.LOWPASS_12000_HZ,
            butterworth(2, 12000.0, sr, FilterType.LOWPASS),
            "LOWPASS_12000_HZ",
        )
    }

    @Test
    fun reproducesBandpass200To4000() {
        // butter(2, [200, 4000] / (44100/2), "band")
        assertSosEquals(
            SosFilters.BANDPASS_200_4000_HZ,
            butterworthBandpass(2, 200.0, 4000.0, sr),
            "BANDPASS_200_4000_HZ",
        )
    }

    @Test
    fun reproducesHighpass6000() {
        // butter(2, 6000 / (44100/2), "high")
        assertSosEquals(
            SosFilters.HIGHPASS_6000_HZ,
            butterworth(2, 6000.0, sr, FilterType.HIGHPASS),
            "HIGHPASS_6000_HZ",
        )
    }

    // --- shapes -----------------------------------------------------------------------------

    @Test
    fun sectionCountFollowsOrder() {
        for (order in 1..8) {
            assertEquals((order + 1) / 2, butterworth(order, 1000.0, sr).size, "lowpass order $order")
            assertEquals(
                (order + 1) / 2,
                butterworth(order, 1000.0, sr, FilterType.HIGHPASS).size,
                "highpass order $order",
            )
            assertEquals(order, butterworthBandpass(order, 500.0, 2000.0, sr).size, "bandpass order $order")
        }
        // a0 is always normalised to 1
        for (section in butterworth(5, 3000.0, sr)) assertEquals(1.0, section[3], 0.0)
    }

    // --- response ---------------------------------------------------------------------------

    /** Amplitude of the [hz] component of [x], by projection onto a sine and a cosine. */
    private fun amplitudeAt(x: DoubleArray, hz: Double, from: Int): Double {
        var re = 0.0
        var im = 0.0
        val w = 2.0 * PI * hz / sr
        for (i in from until x.size) {
            re += x[i] * cos(w * i)
            im += x[i] * sin(w * i)
        }
        val n = x.size - from
        return 2.0 * sqrt(re * re + im * im) / n
    }

    private fun twoTone(): DoubleArray =
        DoubleArray(sr) { sin(2.0 * PI * 200.0 * it / sr) + sin(2.0 * PI * 8000.0 * it / sr) }

    private fun db(out: Double, input: Double) = 20.0 * log10(out / input)

    @Test
    fun designedLowpassRemovesTheHighTone() {
        val x = twoTone()
        val y = sosfilt(butterworth(4, 1000.0, sr, FilterType.LOWPASS), x)
        val tail = sr / 10 // skip 100 ms of transient; the rest is a whole number of periods

        val low = db(amplitudeAt(y, 200.0, tail), amplitudeAt(x, 200.0, tail))
        val high = db(amplitudeAt(y, 8000.0, tail), amplitudeAt(x, 8000.0, tail))
        println("lowpass 1 kHz: 200 Hz $low dB, 8 kHz $high dB")

        assertTrue(abs(low) < 1.0, "200 Hz should pass within 1 dB, was $low dB")
        assertTrue(high < -40.0, "8 kHz should drop by more than 40 dB, was $high dB")
    }

    @Test
    fun designedHighpassRemovesTheLowTone() {
        val x = twoTone()
        val y = sosfilt(butterworth(4, 1000.0, sr, FilterType.HIGHPASS), x)
        val tail = sr / 10

        val low = db(amplitudeAt(y, 200.0, tail), amplitudeAt(x, 200.0, tail))
        val high = db(amplitudeAt(y, 8000.0, tail), amplitudeAt(x, 8000.0, tail))
        println("highpass 1 kHz: 200 Hz $low dB, 8 kHz $high dB")

        assertTrue(low < -40.0, "200 Hz should drop by more than 40 dB, was $low dB")
        assertTrue(abs(high) < 1.0, "8 kHz should pass within 1 dB, was $high dB")
    }

    @Test
    fun designedBandpassKeepsOnlyTheBand() {
        val x = twoTone()
        val y = sosfilt(butterworthBandpass(4, 2000.0, 12000.0, sr), x)
        val tail = sr / 5

        val low = db(amplitudeAt(y, 200.0, tail), amplitudeAt(x, 200.0, tail))
        val high = db(amplitudeAt(y, 8000.0, tail), amplitudeAt(x, 8000.0, tail))
        println("bandpass 2-12 kHz: 200 Hz $low dB, 8 kHz $high dB")

        assertTrue(low < -40.0, "200 Hz should drop by more than 40 dB, was $low dB")
        assertTrue(abs(high) < 1.0, "8 kHz should pass within 1 dB, was $high dB")
    }

    /** Designing for another rate gives the same shape, not the same numbers. */
    @Test
    fun designedForAnotherSampleRate() {
        val rate = 48000
        val sos = butterworth(4, 1000.0, rate, FilterType.LOWPASS)
        val x = DoubleArray(rate) { sin(2.0 * PI * 8000.0 * it / rate) }
        val y = sosfilt(sos, x)
        var peak = 0.0
        for (i in rate / 10 until rate) peak = maxOf(peak, abs(y[i]))
        assertTrue(20.0 * log10(peak) < -40.0, "8 kHz at 48 kHz should drop by more than 40 dB")
    }

    // --- argument checking ----------------------------------------------------------------------

    @Test
    fun rejectsBadArguments() {
        assertFailsWith<IllegalArgumentException> { butterworth(0, 1000.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(-1, 1000.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(2, 0.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(2, -100.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(2, sr / 2.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(2, sr.toDouble(), sr) }
        assertFailsWith<IllegalArgumentException> { butterworth(2, 1000.0, 0) }

        assertFailsWith<IllegalArgumentException> { butterworthBandpass(0, 200.0, 4000.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworthBandpass(2, 0.0, 4000.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworthBandpass(2, 4000.0, 200.0, sr) }
        assertFailsWith<IllegalArgumentException> { butterworthBandpass(2, 200.0, sr / 2.0, sr) }
    }
}
