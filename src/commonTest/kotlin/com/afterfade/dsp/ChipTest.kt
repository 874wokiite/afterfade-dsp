package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Moved with the functions from the app's `ChipTest`. */
class ChipTest {
    @Test
    fun steppedTriangleHasSixteenLevels() {
        val levels = (0 until 4096).map { steppedTriangle(it / 4096.0) }.distinct()
        assertEquals(16, levels.size)
        assertEquals(-1.0, levels.min(), 1e-12)
        assertEquals(1.0, levels.max(), 1e-12)
    }

    @Test
    fun lfsrIsDeterministicAndNotConstant() {
        val a = lfsrNoise(2048, short = false)
        val b = lfsrNoise(2048, short = false)
        assertTrue(a.contentEquals(b))
        assertTrue(a.distinct().size == 2, "LFSR output is 1-bit: exactly two values")
        // 短周期モードは種によって片側に寄った93段の輪に入る（実機もそう）。均衡を見るのは長周期だけ
        assertTrue(a.count { it > 0 } in 768..1280, "long-mode 1-bit noise should be roughly balanced")
    }

    /** 短周期モードは93段で一周する（実機のノイズチャンネルの mode 1） */
    @Test
    fun shortModeLfsrRepeatsEvery93Steps() {
        val a = lfsrNoise(93 * 4, short = true)
        for (i in 93 until a.size) assertEquals(a[i - 93], a[i], "period at $i")
        val long = lfsrNoise(93 * 4, short = false)
        assertTrue((93 until long.size).any { long[it - 93] != long[it] }, "long mode must not have period 93")
    }

    @Test
    fun bitCrushSnapsToTheGrid() {
        val y = FloatArray(200) { (it - 100) / 100f }
        val out = bitCrush(y, bits = 4)
        val step = 1f / 8f
        for (v in out) {
            val k = v / step
            assertEquals(k, kotlin.math.round(k), 1e-6f, "value $v is not on the 1/8 grid")
        }
        assertEquals(y.size, out.size)
    }

    @Test
    fun sampleHoldRepeatsEveryFactorSamples() {
        val y = FloatArray(16) { it.toFloat() }
        val out = sampleHold(y, 4)
        assertEquals(listOf(0f, 0f, 0f, 0f, 4f, 4f, 4f, 4f), out.take(8))
        assertTrue(sampleHold(y, 1).contentEquals(y))
    }

}
