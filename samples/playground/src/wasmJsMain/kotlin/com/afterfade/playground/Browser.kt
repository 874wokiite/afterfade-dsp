@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.afterfade.playground

import kotlinx.browser.document
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toFloat32Array
import org.khronos.webgl.toInt8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.files.File
import org.w3c.files.FileReader

/**
 * Every piece of JavaScript interop the playground needs.
 *
 * The DOM types come from `kotlinx-browser`; what is written by hand here is the Web Audio API
 * (not part of that package), plus the few one-line `js(...)` helpers needed to build a Blob and
 * to hop between `FloatArray`/`ByteArray` and JavaScript typed arrays.
 */

// --- Web Audio ---------------------------------------------------------------------------------

external class AudioContext : JsAny {
    val destination: AudioNode
    val sampleRate: Double
    fun createBuffer(numberOfChannels: Int, length: Int, sampleRate: Double): AudioBuffer
    fun createBufferSource(): AudioBufferSourceNode
    fun resume()
}

external interface AudioNode : JsAny {
    fun connect(destination: AudioNode): AudioNode
}

external interface AudioBuffer : JsAny {
    fun copyToChannel(source: Float32Array, channelNumber: Int)
}

external interface AudioBufferSourceNode : AudioNode {
    var buffer: AudioBuffer?
    fun start()
    fun stop()
}

/** One context for the page: browsers cap how many can exist, and it must outlive each note. */
private var sharedContext: AudioContext? = null

/** The source currently making sound, so a second Play can interrupt the first. */
private var currentSource: AudioBufferSourceNode? = null

/**
 * Copies [samples] into a Web Audio buffer and plays it, stopping whatever was already playing.
 *
 * Must be called from a user gesture (a click); browsers refuse to start an [AudioContext] on
 * their own.
 */
fun playSamples(samples: FloatArray, sampleRate: Int) {
    if (samples.isEmpty()) return
    val ctx = sharedContext ?: AudioContext().also { sharedContext = it }
    ctx.resume() // a context created before the first click starts suspended
    stopPlayback()

    val buffer = ctx.createBuffer(1, samples.size, sampleRate.toDouble())
    buffer.copyToChannel(samples.toFloat32Array(), 0)

    val source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(ctx.destination)
    source.start()
    currentSource = source
}

/** Silences playback started by [playSamples]. Safe to call when nothing is playing. */
fun stopPlayback() {
    currentSource?.stop()
    currentSource = null
}

// --- Files in, files out -----------------------------------------------------------------------

/** Reads a picked or dropped [file] into a [ByteArray] and hands it to [onBytes]. */
fun readFileBytes(file: File, onBytes: (ByteArray) -> Unit, onError: (String) -> Unit) {
    val reader = FileReader()
    reader.onload = {
        val result = reader.result
        if (result == null) onError("could not read ${file.name}") else onBytes(toInt8Array(result).toByteArray())
    }
    reader.onerror = { onError("could not read ${file.name}") }
    reader.readAsArrayBuffer(file)
}

/** Offers [bytes] to the user as a download named [filename]. */
fun downloadBytes(bytes: ByteArray, filename: String, mimeType: String = "audio/wav") {
    val url = createObjectUrl(bytes.toInt8Array(), mimeType)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = filename
    anchor.style.display = "none"
    document.body?.appendChild(anchor)
    anchor.click()
    document.body?.removeChild(anchor)
    // Freed once the browser has taken the bytes; revoking immediately can cancel the download.
    defer(30_000) { revokeObjectUrl(url) }
}

// --- Small JavaScript helpers --------------------------------------------------------------------

/** Wraps an `ArrayBuffer` so its bytes can be copied into Kotlin's heap. */
private fun toInt8Array(buffer: JsAny): Int8Array = js("new Int8Array(buffer)")

private fun createObjectUrl(data: Int8Array, mimeType: String): String =
    js("URL.createObjectURL(new Blob([data], { type: mimeType }))")

private fun revokeObjectUrl(url: String) {
    js("URL.revokeObjectURL(url)")
}

private external fun setTimeout(handler: () -> Unit, timeoutMs: Int): Int

/**
 * Runs [block] after [delayMs], which also lets the browser paint first.
 *
 * All the DSP here runs on the main thread, so a status line set immediately before a long call
 * would never reach the screen without yielding once.
 */
fun defer(delayMs: Int, block: () -> Unit) {
    setTimeout(block, delayMs)
}

/** Device pixel ratio, so canvases can be drawn sharp on a retina screen. */
fun devicePixelRatio(): Double = js("window.devicePixelRatio || 1")
