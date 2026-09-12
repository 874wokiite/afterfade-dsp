package com.afterfade.dsp

/**
 * `scipy.signal.resample(x, num)` — FFT based (band-limited, treats the signal as periodic).
 *
 * Port of the real-input branch of scipy's implementation:
 * ```
 * n_x = len(x); s_fac = n_x / num; m = min(num, n_x); m2 = m // 2 + 1
 * X = rfft(x)[:m2]
 * if m % 2 == 0 and num != n_x:
 *     X[m // 2] *= 2 if num < n_x else 0.5   # unpaired bin at the old Nyquist
 * y = irfft(X / s_fac, n=num)
 * ```
 * The factor is applied *before* the `1 / s_fac` scaling, matching scipy's operation order.
 */
fun resample(x: FloatArray, num: Int): FloatArray {
    require(num > 0) { "resample target length must be positive, got $num" }
    val nx = x.size
    if (nx == 0) return FloatArray(num)
    if (num == nx) return x.copyOf()

    val sFac = nx.toDouble() / num
    val m = minOf(num, nx)
    val m2 = m / 2 + 1

    val spectrum = Fft.rfft(x)
    val bins = minOf(m2, spectrum.size)
    val re = DoubleArray(m2)
    val im = DoubleArray(m2)
    spectrum.re.copyInto(re, 0, 0, bins)
    spectrum.im.copyInto(im, 0, 0, bins)

    if (m % 2 == 0) {
        val factor = if (num < nx) 2.0 else 0.5
        re[m / 2] *= factor
        im[m / 2] *= factor
    }

    for (k in 0 until m2) {
        re[k] /= sFac
        im[k] /= sFac
    }

    val out = Fft.irfft(re, im, num)
    return FloatArray(num) { out[it].toFloat() }
}

/** Resample [x] from [fromRate] to [toRate], rounding the output length like the Python engine. */
fun resampleTo(x: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return x.copyOf()
    val newLength = rint(x.size.toDouble() * toRate / fromRate).toInt()
    if (newLength < 1) return FloatArray(0)
    return resample(x, newLength)
}
