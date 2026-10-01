package com.afterfade.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReverbTest {

    private val sr = 44100

    /** 1発のインパルスが、プリディレイの後に尾を引く。尾は減衰して消える */
    @Test
    fun impulseLeavesADecayingTail() {
        val impulse = FloatArray(sr).also { it[0] = 1f }
        val out = schroederReverb(impulse, sr, wet = 0.5f, feedback = 0.8f, damping = 0.2f, preDelaySeconds = 0.03)
        assertEquals(impulse.size, out.size)
        // 直接音は半分に
        assertEquals(0.5f, out[0], 1e-6f)
        // プリディレイの手前には残響が無い
        assertTrue((1 until (0.03 * sr).toInt() - 1).all { out[it] == 0f }, "no tail before the pre-delay")
        // 0.1 秒付近には残響があり、0.9 秒付近ではそれより小さい
        val early = absMax(out.copyOfRange((0.10 * sr).toInt(), (0.20 * sr).toInt()))
        val late = absMax(out.copyOfRange((0.85 * sr).toInt(), (0.95 * sr).toInt()))
        assertTrue(early > 0f, "there should be a tail")
        assertTrue(late < early, "the tail should decay (early=$early late=$late)")
    }

    @Test
    fun dryWhenWetIsZeroAndDeterministic() {
        val y = FloatArray(4096) { (it % 97) / 97f - 0.5f }
        assertTrue(schroederReverb(y, sr, wet = 0f).contentEquals(y))
        assertTrue(schroederReverb(y, sr).contentEquals(schroederReverb(y, sr)))
        assertEquals(0, schroederReverb(FloatArray(0), sr).size)
    }
}
