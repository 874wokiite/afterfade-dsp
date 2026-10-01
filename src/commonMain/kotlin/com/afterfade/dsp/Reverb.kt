package com.afterfade.dsp

/**
 * Schroeder 型のリバーブ。並列の帰還コム 4 本を足して、直列のオールパス 2 本に通す。
 *
 * 部屋を真似るためではなく「霞ませる」ためのもので、Dreamcore カセットのマスターが使う。
 * 遅延は 44.1kHz での実機の定番（Freeverb 系の素数長）をそのまま秒に直して持ち、
 * サンプリングレートが違えば比例で伸ばす。乱数は使わないので同じ入力なら同じ出力。
 *
 * `pow` / `exp` は使わない。減衰は [feedback] の掛け算だけで決まる。
 */
private val COMB_DELAYS_SECONDS = doubleArrayOf(1557.0 / 44100, 1617.0 / 44100, 1491.0 / 44100, 1422.0 / 44100)
private val ALLPASS_DELAYS_SECONDS = doubleArrayOf(225.0 / 44100, 556.0 / 44100)
private const val ALLPASS_GAIN = 0.5f

/**
 * [y] にリバーブをかけて返す（長さは変えない。尻尾は切り捨てる）。
 *
 * [wet] は 0..1 で残響の混ぜ方（0 なら [y] のコピー）。[feedback] は 0..0.98 でコムの帰還量、
 * 大きいほど長く残る。[damping] は帰還路の 1 極ローパスで、大きいほど高域が先に消える。
 * [preDelaySeconds] は残響が返り始めるまでの間。
 */
fun schroederReverb(
    y: FloatArray,
    sr: Int,
    wet: Float = 0.4f,
    feedback: Float = 0.84f,
    damping: Float = 0.3f,
    preDelaySeconds: Double = 0.03,
): FloatArray {
    require(wet in 0f..1f) { "wet must be within 0..1, was $wet" }
    require(feedback in 0f..0.98f) { "feedback must be within 0..0.98, was $feedback" }
    require(damping in 0f..1f) { "damping must be within 0..1, was $damping" }
    val n = y.size
    if (n == 0 || wet <= 0f) return y.copyOf()

    // 4 本のコムを同じ入力で回して足す
    val combSum = FloatArray(n)
    for (delaySeconds in COMB_DELAYS_SECONDS) {
        val delay = maxOf(1, (delaySeconds * sr).toInt())
        val buffer = FloatArray(delay)
        var index = 0
        var lowpassState = 0f
        for (i in 0 until n) {
            val delayed = buffer[index]
            // 帰還路のローパス。damping が大きいほど前の値を残す（高域が消える）
            lowpassState = delayed * (1f - damping) + lowpassState * damping
            buffer[index] = y[i] + lowpassState * feedback
            combSum[i] += delayed
            index = if (index + 1 == delay) 0 else index + 1
        }
    }

    // 直列のオールパスで反射を密にする
    var signal = combSum
    for (delaySeconds in ALLPASS_DELAYS_SECONDS) {
        val delay = maxOf(1, (delaySeconds * sr).toInt())
        val buffer = FloatArray(delay)
        val out = FloatArray(n)
        var index = 0
        for (i in 0 until n) {
            val delayed = buffer[index]
            val input = signal[i]
            val fed = input + delayed * ALLPASS_GAIN
            out[i] = delayed - fed * ALLPASS_GAIN
            buffer[index] = fed
            index = if (index + 1 == delay) 0 else index + 1
        }
        signal = out
    }

    // 4 本足したぶんを戻し、プリディレイぶん遅らせて、元と混ぜる
    val preDelay = (preDelaySeconds * sr).toInt().coerceAtLeast(0)
    val combGain = 1f / COMB_DELAYS_SECONDS.size
    val out = FloatArray(n)
    for (i in 0 until n) {
        val tail = if (i >= preDelay) signal[i - preDelay] * combGain else 0f
        out[i] = y[i] * (1f - wet) + tail * wet
    }
    return out
}
