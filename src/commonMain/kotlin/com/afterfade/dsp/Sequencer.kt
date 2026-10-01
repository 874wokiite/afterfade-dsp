package com.afterfade.dsp

/**
 * Step-grid timing: where each step of a pattern lands in samples, with swing.
 *
 * ステップのグリッドをサンプル位置に変換する。スウィング付き。
 */

/**
 * Sample positions of [steps] grid steps at [bpm], with [stepsPerBeat] steps per beat
 * (4 = sixteenth notes).
 *
 * [swing] says where the second step of each pair sits inside the pair: 0.5 is straight, about
 * 0.58 is a lazy hip-hop swing, 2/3 is a full triplet shuffle.
 */
fun stepPositions(bpm: Double, sr: Int, steps: Int, stepsPerBeat: Int = 4, swing: Double = 0.5): IntArray {
    require(bpm > 0.0) { "bpm must be positive, was $bpm" }
    require(swing in 0.0..1.0) { "swing must be in [0, 1], was $swing" }
    val step = 60.0 / bpm / stepsPerBeat * sr
    return IntArray(steps) {
        val pairStart = (it / 2) * 2 * step
        (if (it % 2 == 0) pairStart else pairStart + 2 * step * swing).toInt()
    }
}

/**
 * Pattern string to hits: `x` or `X` is a hit, `.` or `-` a rest. Spaces and `|` are ignored, so
 * bars can be written as `"x... x... | x.x. x..."`.
 */
fun parsePattern(pattern: String): BooleanArray {
    val steps = pattern.filter { it != ' ' && it != '|' }
    return BooleanArray(steps.length) {
        when (val c = steps[it]) {
            'x', 'X' -> true
            '.', '-' -> false
            else -> throw IllegalArgumentException("unexpected '$c' in pattern \"$pattern\"")
        }
    }
}
