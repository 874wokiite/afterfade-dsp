package com.afterfade.dsp

/**
 * Second-order-section IIR filtering — the `scipy.signal.sosfilt` half of `scipy.signal.butter`.
 *
 * The engine uses four fixed Butterworth filters at sr=44100. The coefficients below are
 * `scipy.signal.butter(..., output="sos")` output.
 */
object SosFilters {

    /** `butter(4, 800 / (44100/2), "low")` — `transient.extract_bed`. */
    val LOWPASS_800_HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(
            9.1278764060962e-06, 1.82557528121924e-05, 9.1278764060962e-06,
            1.0, -1.7980857947055418, 0.8098293590772162,
        ),
        doubleArrayOf(
            1.0, 2.0, 1.0,
            1.0, -1.9041461469850596, 0.9165824072102602,
        ),
    )

    /** `butter(2, 12000 / (44100/2), "low")` — `effects.lofi_master` high-cut. */
    val LOWPASS_12000_HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(
            0.3347852840605983, 0.6695705681211966, 0.3347852840605983,
            1.0, 0.16287479927794427, 0.17626633696444916,
        ),
    )

    /** `butter(2, [200, 4000] / (44100/2), "band")` — `effects.vinyl_noise`. */
    val BANDPASS_200_4000_HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(
            0.05240981871078714, 0.10481963742157428, 0.05240981871078714,
            1.0, -1.2690965470638802, 0.48479288277162275,
        ),
        doubleArrayOf(
            1.0, -2.0, 1.0,
            1.0, -1.9600389255134525, 0.9609109804014363,
        ),
    )

    /** `butter(2, 6000 / (44100/2), "high")` — `templates.lofi._hihat`. */
    val HIGHPASS_6000_HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(
            0.5400499569423662, -1.0800999138847325, 0.5400499569423662,
            1.0, -0.8559895026725952, 0.30421032509687007,
        ),
    )

    /** Sample rate the coefficients above were designed for. */
    const val DESIGN_SAMPLE_RATE = 44100
}

/**
 * `scipy.signal.sosfilt(sos, x)` — cascaded transposed direct form II.
 *
 * scipy widens float32 input to float64 internally and the engine casts back afterwards, so the
 * state and accumulators here are [Double] even though the signal is a [FloatArray].
 */
fun sosfilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
    val sections = sos.size
    val z1 = DoubleArray(sections)
    val z2 = DoubleArray(sections)
    val out = DoubleArray(x.size)

    for (i in x.indices) {
        var cur = x[i]
        for (s in 0 until sections) {
            val c = sos[s]
            val next = c[0] * cur + z1[s]
            z1[s] = c[1] * cur - c[4] * next + z2[s]
            z2[s] = c[2] * cur - c[5] * next
            cur = next
        }
        out[i] = cur
    }
    return out
}

/** [sosfilt] for a float32 signal, returning float32 — matches `sosfilt(...).astype(np.float32)`. */
fun sosfilt(sos: Array<DoubleArray>, x: FloatArray): FloatArray {
    val sections = sos.size
    val z1 = DoubleArray(sections)
    val z2 = DoubleArray(sections)
    val out = FloatArray(x.size)

    for (i in x.indices) {
        var cur = x[i].toDouble()
        for (s in 0 until sections) {
            val c = sos[s]
            val next = c[0] * cur + z1[s]
            z1[s] = c[1] * cur - c[4] * next + z2[s]
            z2[s] = c[2] * cur - c[5] * next
            cur = next
        }
        out[i] = cur.toFloat()
    }
    return out
}
