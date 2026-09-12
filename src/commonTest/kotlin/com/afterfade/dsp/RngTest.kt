package com.afterfade.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RngTest {

    @Test
    fun sameSeedProducesSameSequence() {
        val a = Rng(42).gaussianNoise(1000, 0.003)
        val b = Rng(42).gaussianNoise(1000, 0.003)
        assertTrue(a.contentEquals(b))
        assertTrue(!a.contentEquals(Rng(43).gaussianNoise(1000, 0.003)))
    }

    @Test
    fun uniformIntegersCoverTheRangeEvenly() {
        val rng = Rng(7)
        val counts = IntArray(5)
        repeat(50_000) { counts[rng.nextInt(5)]++ }
        for (c in counts) assertTrue(abs(c - 10_000) < 700, "bucket count $c is skewed")

        val range = Rng(1)
        repeat(1000) {
            val v = range.nextIntInRange(-2, 3)
            assertTrue(v in -2..2, "value $v outside [-2, 2]")
        }
    }

    @Test
    fun sampleWithoutReplacementReturnsDistinctValues() {
        val pool = (0 until 20).toList()
        val picked = Rng(3).sampleWithoutReplacement(pool, 8)
        assertEquals(8, picked.size)
        assertEquals(8, picked.toSet().size)
        assertTrue(picked.all { it in pool })
    }

    @Test
    fun shufflePermutesWithoutLosingElements() {
        val values = (0 until 32).toMutableList()
        Rng(9).shuffle(values)
        assertEquals((0 until 32).toSet(), values.toSet())
        assertTrue(values != (0 until 32).toList(), "shuffle left the list untouched")
    }
}
