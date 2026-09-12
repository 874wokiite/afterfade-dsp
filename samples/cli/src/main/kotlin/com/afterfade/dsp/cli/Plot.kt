package com.afterfade.dsp.cli

import com.afterfade.dsp.RealFftPlan
import com.afterfade.dsp.estimateKey
import com.afterfade.dsp.estimateTempoFromEnvelope
import com.afterfade.dsp.hanning
import com.afterfade.dsp.io.WavAudio
import com.afterfade.dsp.onsetStrength
import com.afterfade.dsp.padReflect
import com.afterfade.dsp.pickOnsets
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A README-sized picture of one file: log-frequency spectrogram, the onset strength envelope
 * underneath it, the picked onsets as ticks, and what the library thinks the tempo and key are.
 *
 * Everything is drawn with the JDK's own 2D API; the numbers all come from the library.
 */
object Plot {

    const val WIDTH = 1600
    const val HEIGHT = 600

    private const val N_FFT = 2048
    private const val HOP = 512
    private const val DB_FLOOR = -78.0
    private const val F_MIN = 55.0

    // Layout.
    private const val LEFT = 66
    private const val RIGHT = WIDTH - 20
    private const val SPEC_TOP = 46
    private const val SPEC_BOTTOM = 420
    private const val ENV_TOP = 436
    private const val ENV_BOTTOM = 540
    private val PLOT_W = RIGHT - LEFT
    private val SPEC_H = SPEC_BOTTOM - SPEC_TOP
    private val ENV_H = ENV_BOTTOM - ENV_TOP

    // Palette.
    private val BACKGROUND = Color(0x0B, 0x0D, 0x12)
    private val PANEL = Color(0x12, 0x15, 0x1D)
    private val INK = Color(0xE8, 0xEA, 0xF0)
    private val MUTED = Color(0x7A, 0x82, 0x96)
    private val GRID = Color(0xFF, 0xFF, 0xFF, 22)
    private val ENVELOPE = Color(0x7F, 0xD1, 0xC8)
    private val ONSET = Color(0xFF, 0x9E, 0x4A)

    /** Magma-like ramp: stops are `position, r, g, b`. */
    private val RAMP = arrayOf(
        doubleArrayOf(0.00, 8.0, 10.0, 18.0),
        doubleArrayOf(0.18, 28.0, 24.0, 64.0),
        doubleArrayOf(0.38, 78.0, 34.0, 108.0),
        doubleArrayOf(0.58, 143.0, 49.0, 108.0),
        doubleArrayOf(0.75, 205.0, 83.0, 82.0),
        doubleArrayOf(0.89, 242.0, 148.0, 66.0),
        doubleArrayOf(1.00, 252.0, 232.0, 178.0),
    )

    fun render(audio: WavAudio, sourceName: String): ByteArray {
        val y = audio.samples
        val sr = audio.sampleRate
        require(y.size > N_FFT) { "need more than $N_FFT samples to plot, got ${y.size}" }

        // Same reflect padding and hop as onsetStrength, so frame f means the same instant in both.
        val padded = padReflect(y, N_FFT / 2)
        val frames = 1 + (padded.size - N_FFT) / HOP
        val plan = RealFftPlan(N_FFT)
        val window = hanning(N_FFT)
        val frame = DoubleArray(N_FFT)
        val re = DoubleArray(plan.bins)
        val im = DoubleArray(plan.bins)

        val rows = rowBins(sr)
        val cells = Array(frames) { DoubleArray(SPEC_H) }
        var peak = 1e-12
        for (f in 0 until frames) {
            val start = f * HOP
            for (k in 0 until N_FFT) frame[k] = padded[start + k] * window[k]
            plan.rfft(frame, re, im)
            val column = cells[f]
            for (row in 0 until SPEC_H) {
                var best = 0.0
                for (b in rows[row].first..rows[row].second) {
                    val m = sqrt(re[b] * re[b] + im[b] * im[b])
                    if (m > best) best = m
                }
                column[row] = best
                if (best > peak) peak = best
            }
        }
        // Magnitudes to dB relative to the loudest cell.
        for (column in cells) {
            for (row in column.indices) {
                column[row] = (20.0 * log10(column[row] / peak + 1e-12)).coerceIn(DB_FLOOR, 0.0)
            }
        }

        val envelope = onsetStrength(y, sr, hopLength = HOP, nFft = N_FFT)
        val onsets = pickOnsets(envelope)
        val bpm = estimateTempoFromEnvelope(envelope, sr, hopLength = HOP)
        val key = estimateKey(y, sr)

        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        g.color = BACKGROUND
        g.fillRect(0, 0, WIDTH, HEIGHT)

        drawSpectrogram(image, cells, frames)
        val seconds = y.size.toDouble() / sr
        drawFrequencyAxis(g, sr)
        drawEnvelope(g, envelope, onsets, frames)
        drawTimeAxis(g, seconds, frames)
        drawHeader(g, sourceName, sr, seconds)
        drawCaption(g, bpm, key?.let { "${it.tonic} ${it.scale}" })
        drawLegend(g)
        drawFooter(g, onsets.size)
        g.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    // --- spectrogram -----------------------------------------------------------------------

    /** For each pixel row of the spectrogram, the FFT bins that fall inside it (log spaced). */
    private fun rowBins(sr: Int): Array<Pair<Int, Int>> {
        val bins = N_FFT / 2
        val fMax = sr / 2.0
        val binOf = { hz: Double -> (hz * N_FFT / sr).roundToInt().coerceIn(1, bins) }
        return Array(SPEC_H) { row ->
            // row 0 is the top of the panel, i.e. the highest frequency.
            val hi = (SPEC_H - row).toDouble() / SPEC_H
            val lo = (SPEC_H - row - 1).toDouble() / SPEC_H
            val fHi = F_MIN * (fMax / F_MIN).pow(hi)
            val fLo = F_MIN * (fMax / F_MIN).pow(lo)
            val a = binOf(fLo)
            val b = binOf(fHi)
            a to maxOf(a, b)
        }
    }

    private fun drawSpectrogram(image: BufferedImage, cells: Array<DoubleArray>, frames: Int) {
        for (x in 0 until PLOT_W) {
            val f = (x.toLong() * frames / PLOT_W).toInt().coerceIn(0, frames - 1)
            val column = cells[f]
            for (row in 0 until SPEC_H) {
                val t = (column[row] - DB_FLOOR) / (0.0 - DB_FLOOR)
                image.setRGB(LEFT + x, SPEC_TOP + row, colorAt(t).rgb)
            }
        }
    }

    private fun colorAt(position: Double): Color {
        val p = position.coerceIn(0.0, 1.0)
        var i = 0
        while (i < RAMP.size - 2 && p > RAMP[i + 1][0]) i++
        val a = RAMP[i]
        val b = RAMP[i + 1]
        val span = (b[0] - a[0]).coerceAtLeast(1e-9)
        val t = ((p - a[0]) / span).coerceIn(0.0, 1.0)
        return Color(
            (a[1] + (b[1] - a[1]) * t).roundToInt().coerceIn(0, 255),
            (a[2] + (b[2] - a[2]) * t).roundToInt().coerceIn(0, 255),
            (a[3] + (b[3] - a[3]) * t).roundToInt().coerceIn(0, 255),
        )
    }

    // --- axes and panels -------------------------------------------------------------------

    private fun yOfFrequency(hz: Double, sr: Int): Int {
        val fMax = sr / 2.0
        val frac = ln(hz / F_MIN) / ln(fMax / F_MIN)
        return SPEC_BOTTOM - (frac * SPEC_H).roundToInt()
    }

    private fun drawFrequencyAxis(g: Graphics2D, sr: Int) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val metrics = g.fontMetrics
        val ticks = doubleArrayOf(100.0, 250.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        for (hz in ticks) {
            if (hz >= sr / 2.0) continue
            val y = yOfFrequency(hz, sr)
            if (y < SPEC_TOP + 8 || y > SPEC_BOTTOM - 2) continue
            g.color = GRID
            g.drawLine(LEFT, y, RIGHT, y)
            val label = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else hz.roundToInt().toString()
            g.color = MUTED
            g.drawString(label, LEFT - 8 - metrics.stringWidth(label), y + 4)
        }
        g.color = MUTED
        val axisName = "Hz"
        g.drawString(axisName, LEFT - 8 - metrics.stringWidth(axisName), SPEC_TOP + 10)
    }

    private fun drawEnvelope(g: Graphics2D, envelope: FloatArray, onsets: IntArray, frames: Int) {
        g.color = PANEL
        g.fillRect(LEFT, ENV_TOP, PLOT_W, ENV_H)

        var max = 1e-9f
        for (v in envelope) if (v > max) max = v
        val n = minOf(envelope.size, frames)

        // Onset ticks: full height of the envelope panel, plus a short mark under the spectrogram.
        g.color = ONSET
        g.stroke = BasicStroke(1f)
        for (index in onsets) {
            if (index >= n) continue
            val x = LEFT + (index.toLong() * PLOT_W / frames).toInt()
            g.drawLine(x, ENV_TOP, x, ENV_BOTTOM)
            // A second mark in the gap between the panels, where nothing is drawn over it.
            g.drawLine(x, SPEC_BOTTOM + 3, x, ENV_TOP - 3)
        }

        val path = Path2D.Double()
        for (i in 0 until n) {
            val x = LEFT + i.toDouble() * PLOT_W / frames
            val y = ENV_BOTTOM - 3.0 - (envelope[i] / max) * (ENV_H - 10.0)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        g.color = ENVELOPE
        g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)

        g.color = MUTED
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString("onset", LEFT - 8 - g.fontMetrics.stringWidth("onset"), ENV_TOP + 18)
        g.drawString("strength", LEFT - 8 - g.fontMetrics.stringWidth("strength"), ENV_TOP + 33)
    }

    private fun drawTimeAxis(g: Graphics2D, seconds: Double, frames: Int) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val metrics = g.fontMetrics
        val step = when {
            seconds <= 12 -> 1.0
            seconds <= 40 -> 5.0
            else -> 10.0
        }
        var t = 0.0
        while (t <= seconds + 1e-9) {
            val x = LEFT + (t / seconds * PLOT_W).roundToInt()
            if (x <= RIGHT) {
                g.color = MUTED
                g.drawLine(x, ENV_BOTTOM, x, ENV_BOTTOM + 4)
                val label = "${t.roundToInt()}s"
                g.drawString(label, x - metrics.stringWidth(label) / 2, ENV_BOTTOM + 19)
            }
            t += step
        }
    }

    private fun drawHeader(g: Graphics2D, sourceName: String, sr: Int, seconds: Double) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        val text = "$sourceName  ·  $sr Hz  ·  ${fmt(seconds, 1)} s  ·  ${N_FFT}-point FFT, hop $HOP, Hann"
        g.color = MUTED
        g.drawString(text, RIGHT - g.fontMetrics.stringWidth(text), SPEC_TOP - 16)
    }

    private fun drawCaption(g: Graphics2D, bpm: Double?, key: String?) {
        val title = "afterfade-dsp"
        val detail = buildString {
            append(if (bpm == null) "no clear tempo" else "${bpm.roundToInt()} BPM")
            append("  ·  ")
            append(key ?: "no clear key")
        }
        val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 26)
        val detailFont = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        val titleWidth = g.getFontMetrics(titleFont).stringWidth(title)
        val detailWidth = g.getFontMetrics(detailFont).stringWidth(detail)
        val boxW = maxOf(titleWidth, detailWidth) + 32
        val boxH = 78
        val boxX = LEFT + 18
        val boxY = SPEC_TOP + 18

        g.color = Color(0x0B, 0x0D, 0x12, 190)
        g.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10)
        g.color = Color(0xFF, 0xFF, 0xFF, 34)
        g.drawRoundRect(boxX, boxY, boxW, boxH, 10, 10)

        g.color = INK
        g.font = titleFont
        g.drawString(title, boxX + 16, boxY + 33)
        g.color = ONSET
        g.font = detailFont
        g.drawString(detail, boxX + 16, boxY + 60)
    }

    private fun drawLegend(g: Graphics2D) {
        val barW = 150
        val barH = 9
        val x = RIGHT - barW - 18
        val y = SPEC_TOP + 30

        // A panel behind it, so the labels stay readable over a busy spectrogram.
        g.color = Color(0x0B, 0x0D, 0x12, 190)
        g.fillRoundRect(x - 14, y - 14, barW + 28, 100, 10, 10)
        g.color = Color(0xFF, 0xFF, 0xFF, 34)
        g.drawRoundRect(x - 14, y - 14, barW + 28, 100, 10, 10)

        for (i in 0 until barW) {
            g.color = colorAt(i.toDouble() / (barW - 1))
            g.fillRect(x + i, y, 1, barH)
        }
        g.color = Color(0xFF, 0xFF, 0xFF, 50)
        g.drawRect(x, y, barW - 1, barH)

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        val metrics = g.fontMetrics
        g.color = MUTED
        g.drawString("${DB_FLOOR.roundToInt()} dB", x, y + barH + 14)
        val top = "0 dB"
        g.drawString(top, x + barW - metrics.stringWidth(top), y + barH + 14)

        var row = y + barH + 34
        g.color = ENVELOPE
        g.stroke = BasicStroke(2f)
        g.drawLine(x, row - 4, x + 18, row - 4)
        g.color = MUTED
        g.drawString("onsetStrength", x + 26, row)

        row += 17
        g.color = ONSET
        g.stroke = BasicStroke(1f)
        g.drawLine(x + 4, row - 11, x + 4, row - 1)
        g.drawLine(x + 10, row - 11, x + 10, row - 1)
        g.drawLine(x + 16, row - 11, x + 16, row - 1)
        g.color = MUTED
        g.drawString("pickOnsets", x + 26, row)
    }

    private fun drawFooter(g: Graphics2D, onsetCount: Int) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.color = MUTED
        g.drawString(
            "log-frequency spectrogram from RealFftPlan  ·  $onsetCount onsets  ·  tempo and key from estimateTempo / estimateKey",
            LEFT,
            HEIGHT - 16,
        )
    }
}
