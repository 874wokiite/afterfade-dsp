@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.afterfade.playground

import com.afterfade.dsp.Rng
import com.afterfade.dsp.butterworth
import com.afterfade.dsp.estimateKey
import com.afterfade.dsp.estimatePitch
import com.afterfade.dsp.estimateTempo
import com.afterfade.dsp.hzToNote
import com.afterfade.dsp.peakNormalized
import com.afterfade.dsp.rms
import com.afterfade.dsp.softSaturate
import com.afterfade.dsp.sosfilt
import com.afterfade.dsp.spectralCentroid
import com.afterfade.dsp.tapeWarble
import com.afterfade.dsp.vinylNoise
import com.afterfade.dsp.io.Wav
import com.afterfade.dsp.io.WavAudio
import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/** Sample rate for generated ambience: nothing is being matched, so use CD rate. */
private const val AMBIENCE_SAMPLE_RATE = 44_100

/** Analysis cap. Long files still load and play; only the estimators look at the first minute. */
private const val ANALYSIS_SECONDS = 60

private var loaded: WavAudio? = null
private var loadedName: String = "audio"
private var tapeRendered: FloatArray? = null
private var ambienceRendered: FloatArray? = null

fun main() {
    wireFileInput()
    wireTape()
    wireAmbience()
    setStatus("analyse-status", "no file loaded yet")
}

// --- Analyse -------------------------------------------------------------------------------------

private fun wireFileInput() {
    val picker = input("file-input")
    picker.addEventListener("change") {
        picker.files?.item(0)?.let { loadFile(it) }
    }

    val zone = el("drop-zone")
    zone.addEventListener("click") { picker.click() }
    zone.addEventListener("dragover") { event ->
        event.preventDefault()
        zone.classList.add("dragging")
    }
    zone.addEventListener("dragleave") { zone.classList.remove("dragging") }
    zone.addEventListener("drop") { event ->
        event.preventDefault()
        zone.classList.remove("dragging")
        (event as DragEvent).dataTransfer?.files?.item(0)?.let { loadFile(it) }
    }
}

private fun loadFile(file: File) {
    setStatus("analyse-status", "reading ${file.name}…", working = true)
    readFileBytes(
        file,
        onBytes = { bytes ->
            busy("analyse-status", "analysing ${file.name}…") {
                val audio = Wav.decode(bytes)
                loaded = audio
                loadedName = file.name.removeSuffix(".wav").removeSuffix(".WAV").ifEmpty { "audio" }
                tapeRendered = null
                analyse(audio)
            }
        },
        onError = { message -> setStatus("analyse-status", message) },
    )
}

private fun analyse(audio: WavAudio): String {
    val samples = audio.samples
    val sr = audio.sampleRate
    val seconds = samples.size.toDouble() / sr
    val head = if (samples.size > sr * ANALYSIS_SECONDS) samples.copyOf(sr * ANALYSIS_SECONDS) else samples

    show("m-samplerate", "$sr Hz")
    show("m-duration", "${fmt(seconds, 2)} s · ${samples.size} samples · ${audio.sourceChannels}ch/${audio.sourceBitsPerSample}-bit source")

    val bpm = estimateTempo(head, sr)
    show("m-tempo", if (bpm == null) "—" else "${fmt(bpm, 1)} BPM")

    val key = estimateKey(head, sr)
    show("m-key", if (key == null) "—" else "${key.tonic} ${key.scale} · confidence ${fmt(key.confidence, 3)}")

    val hz = estimatePitch(head, sr)
    show("m-pitch", if (hz == null) "—" else "${fmt(hz, 1)} Hz · ${hzToNote(hz).let { (name, octave) -> "$name$octave" }}")

    show("m-rms", "${fmt(rms(head).toDouble(), 4)} · peak ${fmt(peakOf(samples).toDouble(), 3)}")
    show("m-centroid", "${fmt(spectralCentroid(head, sr), 0)} Hz")

    drawWaveform(canvas("canvas-wave"), samples)
    drawSpectrum(canvas("canvas-spectrum"), head, sr)

    enable("tape-play", true)
    enable("tape-download", true)
    val truncated = if (head.size < samples.size) " (estimates from the first ${ANALYSIS_SECONDS}s)" else ""
    return "${loadedName}.wav loaded$truncated"
}

// --- Tape treatment ------------------------------------------------------------------------------

private fun wireTape() {
    for (id in listOf("t-warble", "t-drive", "t-hiss", "t-cutoff")) {
        val slider = input(id)
        slider.addEventListener("input") {
            tapeRendered = null
            showSliderValue(id)
        }
        showSliderValue(id)
    }

    button("tape-play").addEventListener("click") {
        val audio = loaded
        if (audio == null) {
            setStatus("tape-status", "load a WAV file first")
        } else {
            busy("tape-status", "processing…") {
                val out = renderTape(audio)
                playSamples(out, audio.sampleRate)
                "playing ${fmt(out.size.toDouble() / audio.sampleRate, 2)} s"
            }
        }
    }

    button("tape-stop").addEventListener("click") {
        stopPlayback()
        setStatus("tape-status", "stopped")
    }

    button("tape-download").addEventListener("click") {
        val audio = loaded
        if (audio == null) {
            setStatus("tape-status", "load a WAV file first")
        } else {
            busy("tape-status", "encoding…") {
                val out = renderTape(audio)
                downloadBytes(Wav.encodePcm16(out, audio.sampleRate), "$loadedName-tape.wav")
                "downloaded $loadedName-tape.wav"
            }
        }
    }

    enable("tape-play", false)
    enable("tape-download", false)
}

/**
 * The tape chain from the README, with every amount taken from a slider.
 *
 * Filter first so saturation is not fed material that will be thrown away, then wow and flutter,
 * then soft clipping, then hiss on top; normalising last keeps the result just under full scale.
 */
private fun renderTape(audio: WavAudio): FloatArray {
    tapeRendered?.let { return it }

    val sr = audio.sampleRate
    val depth = slider("t-warble")
    val drive = slider("t-drive")
    val hissAmount = slider("t-hiss")
    val cutoff = min(slider("t-cutoff"), sr / 2.0 - 100.0)

    var y = sosfilt(butterworth(2, cutoff, sr), audio.samples)
    y = tapeWarble(y, sr, depth = depth, rateHz = 0.4)
    y = softSaturate(y, drive = drive)
    if (hissAmount > 0.0) {
        val hiss = vinylNoise(y.size, sr, amplitude = hissAmount)
        for (i in y.indices) y[i] += hiss[i]
    }
    val out = peakNormalized(y, 0.9f)
    tapeRendered = out
    return out
}

// --- Generated ambience --------------------------------------------------------------------------

private fun wireAmbience() {
    for (id in listOf("a-seed", "a-seconds", "a-cutoff")) {
        input(id).addEventListener("input") { ambienceRendered = null }
    }

    button("ambience-play").addEventListener("click") {
        busy("ambience-status", "generating…") {
            val out = renderAmbience()
            playSamples(out, AMBIENCE_SAMPLE_RATE)
            "playing ${fmt(out.size.toDouble() / AMBIENCE_SAMPLE_RATE, 1)} s from seed ${seedValue()}"
        }
    }

    button("ambience-stop").addEventListener("click") {
        stopPlayback()
        setStatus("ambience-status", "stopped")
    }

    button("ambience-download").addEventListener("click") {
        busy("ambience-status", "encoding…") {
            val out = renderAmbience()
            val name = "ambience-seed${seedValue()}.wav"
            downloadBytes(Wav.encodePcm16(out, AMBIENCE_SAMPLE_RATE), name)
            "downloaded $name"
        }
    }

    setStatus("ambience-status", "ready")
}

/** Seeded gaussian noise, low-passed into a bed. No input audio is involved. */
private fun renderAmbience(): FloatArray {
    ambienceRendered?.let { return it }

    val sr = AMBIENCE_SAMPLE_RATE
    val seconds = slider("a-seconds").coerceIn(1.0, 60.0)
    val cutoff = slider("a-cutoff").coerceIn(40.0, sr / 2.0 - 100.0)
    val length = (seconds * sr).toInt()

    val noise = Rng(seedValue()).gaussianNoise(length, 0.2)
    val bed = sosfilt(butterworth(4, cutoff, sr), noise)
    val out = peakNormalized(bed, 0.5f)
    ambienceRendered = out
    return out
}

private fun seedValue(): Long = slider("a-seed").toLong()

// --- DOM plumbing ---------------------------------------------------------------------------------

private fun el(id: String): HTMLElement = document.getElementById(id) as HTMLElement

private fun input(id: String): HTMLInputElement = document.getElementById(id) as HTMLInputElement

private fun button(id: String): HTMLButtonElement = document.getElementById(id) as HTMLButtonElement

private fun canvas(id: String): HTMLCanvasElement = document.getElementById(id) as HTMLCanvasElement

private fun slider(id: String): Double = input(id).value.toDoubleOrNull() ?: 0.0

private fun show(id: String, value: String) {
    el(id).textContent = value
}

private fun enable(id: String, enabled: Boolean) {
    button(id).disabled = !enabled
}

private fun showSliderValue(id: String) {
    val value = slider(id)
    val text = when (id) {
        "t-warble" -> fmt(value, 4)
        "t-drive" -> fmt(value, 2)
        "t-hiss" -> fmt(value, 4)
        else -> "${fmt(value, 0)} Hz"
    }
    show("$id-value", text)
}

private fun setStatus(id: String, message: String, working: Boolean = false) {
    val element = el(id)
    element.textContent = message
    if (working) element.classList.add("working") else element.classList.remove("working")
}

/**
 * Shows [message], yields once so the browser paints it, then runs [work] and shows what it
 * returns. Everything here is synchronous main-thread DSP, so without the yield the "working…"
 * line would only appear after the work had already finished.
 */
private fun busy(statusId: String, message: String, work: () -> String) {
    setStatus(statusId, message, working = true)
    defer(24) {
        val result = try {
            work()
        } catch (t: Throwable) {
            "error: ${t.message ?: t.toString()}"
        }
        setStatus(statusId, result)
    }
}

/** Fixed-point formatting; Kotlin/Wasm has no `String.format`. */
private fun fmt(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = round(abs(value) * scale).toLong()
    val sign = if (value < 0.0) "-" else ""
    val whole = scaled / scale
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole." + (scaled % scale).toString().padStart(decimals, '0')
}
