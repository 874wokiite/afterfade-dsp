package com.afterfade.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArraysTest {

    @Test
    fun hanningMatchesNumpy() {
        val w = hanning(2048)
        assertEquals(2048, w.size)
        assertEquals(0.0, w[0], 1e-15)
        assertEquals(0.0, w[2047], 1e-15)
        assertEquals(1.0, w.max(), 1e-6)
        for (i in 0 until 1024) assertEquals(w[i], w[2047 - i], 1e-15)
        // np.hanning(2048)[1]
        assertEquals(2.355394838837732e-06, w[1], 1e-18)
    }

    @Test
    fun linspaceMatchesNumpy() {
        val a = linspace(0.0, 10.0, 5, endpoint = true)
        assertTrue(a.contentEquals(doubleArrayOf(0.0, 2.5, 5.0, 7.5, 10.0)))
        val b = linspace(0.0, 1.0, 4, endpoint = false)
        assertTrue(b.contentEquals(doubleArrayOf(0.0, 0.25, 0.5, 0.75)))
    }

    @Test
    fun rfftFreqMatchesNumpy() {
        val f = rfftFreq(2048, 44100)
        assertEquals(1025, f.size)
        assertEquals(0.0, f[0], 0.0)
        assertEquals(44100.0 / 2048.0, f[1], 1e-12)
        assertEquals(22050.0, f[1024], 1e-9)
    }

    @Test
    fun padReflectMatchesNumpy() {
        val x = floatArrayOf(1f, 2f, 3f, 4f)
        // np.pad([1,2,3,4], (2,2), mode="reflect") -> [3,2,1,2,3,4,3,2]
        assertTrue(padReflect(x, 2).contentEquals(floatArrayOf(3f, 2f, 1f, 2f, 3f, 4f, 3f, 2f)))
    }

    @Test
    fun padEdgeMatchesNumpy() {
        val x = floatArrayOf(1f, 2f, 3f)
        assertTrue(padEdge(x, 2).contentEquals(floatArrayOf(1f, 1f, 1f, 2f, 3f, 3f, 3f)))
    }

    @Test
    fun medianMatchesNumpy() {
        assertEquals(3f, median(floatArrayOf(5f, 1f, 3f, 2f, 4f)))
        assertEquals(2.5f, median(floatArrayOf(4f, 1f, 3f, 2f)))
        assertEquals(3f, median(floatArrayOf(9f, 5f, 1f, 3f, 2f, 4f, 9f), 1, 6))
    }

    @Test
    fun rintRoundsHalfToEven() {
        assertEquals(0.0, rint(0.5))
        assertEquals(2.0, rint(1.5))
        assertEquals(2.0, rint(2.5))
        assertEquals(-2.0, rint(-2.5))
        assertEquals(-2.0, rint(-1.5))
        assertEquals(3.0, rint(2.7))
    }

    @Test
    fun peakNormalizeAndAddAt() {
        val normalized = peakNormalized(floatArrayOf(0.5f, -1f, 0.25f), 0.6f)
        assertEquals(0.6f, absMax(normalized), 1e-7f)
        assertTrue(abs(normalized[0] - 0.3f) < 1e-7f)

        val buf = FloatArray(5)
        addAt(buf, floatArrayOf(1f, 2f, 3f), 3)
        assertTrue(buf.contentEquals(floatArrayOf(0f, 0f, 0f, 1f, 2f)))
        addAt(buf, floatArrayOf(1f), 99)
        assertTrue(buf.contentEquals(floatArrayOf(0f, 0f, 0f, 1f, 2f)))
    }
}
