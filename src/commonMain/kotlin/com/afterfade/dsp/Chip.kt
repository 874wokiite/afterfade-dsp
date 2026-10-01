package com.afterfade.dsp

import kotlin.math.floor

/**
 * Chip-style waveforms and the lo-fi digital stages: pulse and stepped triangle waves, the 15-bit
 * LFSR noise of console sound chips, bit depth reduction and sample-rate reduction.
 *
 * Moved unchanged from the Afterfade app's 8bit cassette. The waveforms use only comparisons,
 * multiplication and `floor`, so they give the same bits on every platform.
 *
 * 8bit カセットから移した波形と、量子化・間引き。乗除算と `floor` だけなので決定的。
 */

/** 1周期のうち [duty] の割合だけ高い側にいるパルス波。0.5 で矩形波。 */
fun pulse(phase: Double, duty: Double): Double = if (phase < duty) 1.0 else -1.0

/**
 * 三角波。実機の三角チャンネルは 4bit（16段）の階段なので、そこに寄せて量子化する。
 * 階段が無いと素の三角波になって「丸い」音になり、8bit の芯が抜ける。
 */
fun steppedTriangle(phase: Double): Double {
    val tri = if (phase < 0.5) 4.0 * phase - 1.0 else 3.0 - 4.0 * phase
    return floor((tri + 1.0) * 7.5) / 7.5 - 1.0
}

/**
 * 実機のノイズチャンネルと同じ 15bit LFSR。
 *
 * [short] を立てると帰還タップが bit6 になり、93 段で一周する金属的な音になる
 * （実機の "mode 1"）。ハイハットはこちら、スネアは通常の長周期。
 * [hold] は1つの値を何サンプル保つか。1 なら白色に近く、増やすほど低く籠る。
 */
fun lfsrNoise(length: Int, short: Boolean, hold: Int = 1, seed: Int = 1, holdTo: Int = hold): FloatArray {
    require(hold >= 1 && holdTo >= 1) { "hold must be >= 1, was $hold -> $holdTo" }
    val out = FloatArray(length)
    var reg = seed and 0x7FFF
    if (reg == 0) reg = 1
    var current = 0f
    var nextStep = 0
    for (i in 0 until length) {
        if (i >= nextStep) {
            val tap = if (short) 6 else 1
            val feedback = (reg and 1) xor ((reg shr tap) and 1)
            reg = (reg shr 1) or (feedback shl 14)
            current = if (reg and 1 == 1) 1f else -1f
            // [hold] から [holdTo] へ直線で速度を落とす。実機のスネアは高い速度で叩いて低い速度へ落とす
            val progress = if (length <= 1) 0.0 else i.toDouble() / (length - 1)
            nextStep = i + hold + ((holdTo - hold) * progress).toInt()
        }
        out[i] = current
    }
    return out
}

/** 振幅を 2^([bits]-1) 段に量子化する。[rint] なので丸めの向きがプラットフォームに依らない。 */
fun bitCrush(y: FloatArray, bits: Int): FloatArray {
    require(bits in 2..24) { "bits must be in 2..24, was $bits" }
    val levels = (1 shl (bits - 1)).toDouble()
    return FloatArray(y.size) { (rint(y[it] * levels) / levels).toFloat() }
}

/** [factor] サンプルごとに値を保持する。標本化周波数を 1/[factor] に落としたのと同じ音になる。 */
fun sampleHold(y: FloatArray, factor: Int): FloatArray {
    require(factor >= 1) { "factor must be >= 1, was $factor" }
    if (factor == 1) return y.copyOf()
    return FloatArray(y.size) { y[it - it % factor] }
}
