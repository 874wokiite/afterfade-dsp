@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.afterfade.playground

import com.afterfade.dsp.Fft
import com.afterfade.dsp.RealFftPlan
import com.afterfade.dsp.hanning
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val ACCENT = "#e8a33d"
private const val GRID = "#2b2b31"
private const val AXIS_TEXT = "#7d7a74"

/** Height of the label strip under the spectrum plot. */
private const val AXIS_HEIGHT = 16.0

/** Size the backing store to the element's CSS box so nothing looks soft on a retina screen. */
private class Surface(val ctx: CanvasRenderingContext2D, val width: Double, val height: Double)

private fun surface(canvas: HTMLCanvasElement): Surface? {
    val dpr = devicePixelRatio()
    val width = canvas.clientWidth.toDouble()
    val height = canvas.clientHeight.toDouble()
    if (width <= 0.0 || height <= 0.0) return null

    canvas.width = (width * dpr).toInt()
    canvas.height = (height * dpr).toInt()

    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.scale(dpr, dpr) // setting width/height above reset the transform
    ctx.clearRect(0.0, 0.0, width, height)
    return Surface(ctx, width, height)
}

/** Peak envelope: one vertical bar per pixel column, from the minimum to the maximum sample. */
fun drawWaveform(canvas: HTMLCanvasElement, samples: FloatArray) {
    val s = surface(canvas) ?: return
    val ctx = s.ctx
    val mid = s.height / 2.0

    ctx.strokeStyle = GRID.toJsString()
    ctx.lineWidth = 1.0
    ctx.beginPath()
    ctx.moveTo(0.0, mid)
    ctx.lineTo(s.width, mid)
    ctx.stroke()

    if (samples.isEmpty()) return

    val columns = s.width.toInt()
    val perColumn = max(1, samples.size / max(1, columns))
    ctx.fillStyle = ACCENT.toJsString()
    for (x in 0 until columns) {
        val from = (x.toLong() * samples.size / columns).toInt()
        val to = min(samples.size, from + perColumn)
        if (from >= to) continue
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in from until to) {
            val v = samples[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val top = mid - hi * mid
        val bottom = mid - lo * mid
        ctx.fillRect(x.toDouble(), top, 1.0, max(1.0, bottom - top))
    }
}

/**
 * Average magnitude spectrum in dB, on a log frequency axis.
 *
 * Up to 48 frames of 2048 samples are spread evenly over the file, each windowed with [hanning]
 * and transformed with a shared [RealFftPlan]; the plan reuses its twiddle tables, so the whole
 * thing costs about as much as a single [Fft.rfft] call per frame.
 */
fun drawSpectrum(canvas: HTMLCanvasElement, samples: FloatArray, sampleRate: Int) {
    val s = surface(canvas) ?: return
    val ctx = s.ctx
    val nFft = 2048
    val bins = nFft / 2 + 1
    val minHz = 20.0
    val maxHz = sampleRate / 2.0
    val minDb = -100.0
    val maxDb = 0.0
    val plotHeight = s.height - AXIS_HEIGHT // the strip along the bottom holds the frequency labels

    drawSpectrumGrid(s, plotHeight, minHz, maxHz)
    if (samples.size < nFft) return

    val window = hanning(nFft)
    val plan = RealFftPlan(nFft)
    val frame = DoubleArray(nFft)
    val re = DoubleArray(bins)
    val im = DoubleArray(bins)
    val magnitude = DoubleArray(bins)

    val frames = min(48, 1 + (samples.size - nFft) / (nFft / 2))
    val step = if (frames <= 1) 0 else (samples.size - nFft) / (frames - 1)
    for (f in 0 until frames) {
        val offset = f * step
        for (i in 0 until nFft) frame[i] = samples[offset + i] * window[i]
        plan.rfft(frame, re, im)
        for (i in 0 until bins) magnitude[i] += sqrt(re[i] * re[i] + im[i] * im[i])
    }

    // Normalise against the loudest bin so quiet files still fill the box.
    var peak = 0.0
    for (v in magnitude) peak = max(peak, v)
    if (peak <= 0.0) return

    ctx.strokeStyle = ACCENT.toJsString()
    ctx.lineWidth = 1.5
    ctx.beginPath()
    var started = false
    for (i in 1 until bins) {
        val hz = i.toDouble() * sampleRate / nFft
        if (hz < minHz) continue
        val db = 20.0 * log10(magnitude[i] / peak + 1e-12)
        val x = s.width * logPosition(hz, minHz, maxHz)
        val y = plotHeight * (1.0 - ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0))
        if (started) {
            ctx.lineTo(x, y)
        } else {
            ctx.moveTo(x, y)
            started = true
        }
    }
    ctx.stroke()
}

/** Decade markers along the bottom and a line every 20 dB, inside the plot area only. */
private fun drawSpectrumGrid(s: Surface, plotHeight: Double, minHz: Double, maxHz: Double) {
    val ctx = s.ctx
    ctx.strokeStyle = GRID.toJsString()
    ctx.fillStyle = AXIS_TEXT.toJsString()
    ctx.lineWidth = 1.0
    ctx.font = "10px ui-monospace, monospace"

    for (hz in intArrayOf(100, 1_000, 10_000)) {
        if (hz >= maxHz) continue
        val x = s.width * logPosition(hz.toDouble(), minHz, maxHz)
        ctx.beginPath()
        ctx.moveTo(x, 0.0)
        ctx.lineTo(x, plotHeight)
        ctx.stroke()
        ctx.fillText(if (hz >= 1000) "${hz / 1000}k" else "$hz", x + 4.0, s.height - 3.0)
    }

    // The axis runs -100 dB to 0 dB, so a line every fifth of the plot is 20 dB.
    for (step in 1..4) {
        val y = plotHeight * step / 5.0
        ctx.beginPath()
        ctx.moveTo(0.0, y)
        ctx.lineTo(s.width, y)
        ctx.stroke()
    }
    ctx.beginPath()
    ctx.moveTo(0.0, plotHeight)
    ctx.lineTo(s.width, plotHeight)
    ctx.stroke()
}

/** Where [hz] sits between [minHz] and [maxHz] on a log axis, in `0..1`. */
private fun logPosition(hz: Double, minHz: Double, maxHz: Double): Double =
    ((ln(max(hz, minHz)) - ln(minHz)) / (ln(maxHz) - ln(minHz))).coerceIn(0.0, 1.0)

/** Largest absolute sample, used for the "peak" readout. */
fun peakOf(samples: FloatArray): Float {
    var peak = 0f
    for (v in samples) peak = max(peak, abs(v))
    return peak
}
