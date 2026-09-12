package com.afterfade.dsp.io

import kotlin.math.roundToInt

/** Decoded WAV contents: mono float32 samples plus the source format description. */
class WavAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val sourceChannels: Int,
    val sourceBitsPerSample: Int,
)

/**
 * Minimal RIFF/WAVE reader and writer, pure Kotlin so it lives in commonMain.
 *
 * Covers what the engine actually meets: PCM 16/24/32-bit integer and IEEE 32/64-bit float,
 * including WAVE_FORMAT_EXTENSIBLE. Multi-channel input is averaged down to mono, matching
 * `generator.generate_track`'s `audio.mean(axis=1)`.
 *
 * File IO is deliberately out of scope — the caller supplies/consumes a [ByteArray].
 */
object Wav {

    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun decode(bytes: ByteArray): WavAudio {
        require(bytes.size >= 12) { "not a WAV file: only ${bytes.size} bytes" }
        require(tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a RIFF/WAVE file" }

        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = readInt32(bytes, pos + 4)
            val body = pos + 8
            if (size < 0 || body > bytes.size) break

            when (id) {
                "fmt " -> {
                    audioFormat = readInt16(bytes, body)
                    channels = readInt16(bytes, body + 2)
                    sampleRate = readInt32(bytes, body + 4)
                    bitsPerSample = readInt16(bytes, body + 14)
                    if (audioFormat == FORMAT_EXTENSIBLE && size >= 26) {
                        // The real format lives in the first two bytes of the SubFormat GUID.
                        audioFormat = readInt16(bytes, body + 24)
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataLength = minOf(size, bytes.size - body)
                }
            }

            pos = body + size + (size and 1) // chunks are word aligned
        }

        require(dataOffset >= 0) { "WAV file has no data chunk" }
        require(channels > 0) { "WAV file has no fmt chunk" }

        val bytesPerSample = bitsPerSample / 8
        require(bytesPerSample > 0) { "unsupported bits per sample: $bitsPerSample" }
        val frameSize = bytesPerSample * channels
        val frames = dataLength / frameSize

        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val at = dataOffset + f * frameSize + c * bytesPerSample
                sum += readSample(bytes, at, audioFormat, bitsPerSample)
            }
            mono[f] = (sum / channels).toFloat()
        }

        return WavAudio(mono, sampleRate, channels, bitsPerSample)
    }

    /** Mono 16-bit PCM WAV — the format `soundfile.write` produces by default. */
    fun encodePcm16(samples: FloatArray, sampleRate: Int): ByteArray {
        val out = header(sampleRate, channels = 1, bitsPerSample = 16, format = FORMAT_PCM, dataBytes = samples.size * 2)
        var at = out.size - samples.size * 2
        for (v in samples) {
            // libsndfile (and therefore soundfile.write) scales by 32768 and clips, so that
            // encode/decode are exact inverses apart from the rounding step.
            val scaled = (v * 32768f).roundToInt().coerceIn(-32768, 32767)
            out[at++] = (scaled and 0xFF).toByte()
            out[at++] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Mono 32-bit IEEE float WAV — lossless round trip for engine output. */
    fun encodeFloat32(samples: FloatArray, sampleRate: Int): ByteArray {
        val out = header(
            sampleRate,
            channels = 1,
            bitsPerSample = 32,
            format = FORMAT_IEEE_FLOAT,
            dataBytes = samples.size * 4,
        )
        var at = out.size - samples.size * 4
        for (v in samples) {
            val bits = v.toRawBits()
            out[at++] = (bits and 0xFF).toByte()
            out[at++] = ((bits shr 8) and 0xFF).toByte()
            out[at++] = ((bits shr 16) and 0xFF).toByte()
            out[at++] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(bytes: ByteArray, at: Int, format: Int, bits: Int): Double = when {
        format == FORMAT_IEEE_FLOAT && bits == 32 -> Float.fromBits(readInt32(bytes, at)).toDouble()
        format == FORMAT_IEEE_FLOAT && bits == 64 -> Double.fromBits(readInt64(bytes, at))
        format == FORMAT_PCM && bits == 8 -> (bytes[at].toInt() and 0xFF) / 128.0 - 1.0
        format == FORMAT_PCM && bits == 16 -> readInt16Signed(bytes, at) / 32768.0
        format == FORMAT_PCM && bits == 24 -> {
            val v = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                (bytes[at + 2].toInt() shl 16) // top byte keeps its sign
            v / 8388608.0
        }
        format == FORMAT_PCM && bits == 32 -> readInt32(bytes, at) / 2147483648.0
        else -> throw IllegalArgumentException("unsupported WAV sample format=$format bits=$bits")
    }

    private fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, format: Int, dataBytes: Int): ByteArray {
        val out = ByteArray(44 + dataBytes)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        writeTag(out, 0, "RIFF")
        writeInt32(out, 4, 36 + dataBytes)
        writeTag(out, 8, "WAVE")
        writeTag(out, 12, "fmt ")
        writeInt32(out, 16, 16)
        writeInt16(out, 20, format)
        writeInt16(out, 22, channels)
        writeInt32(out, 24, sampleRate)
        writeInt32(out, 28, byteRate)
        writeInt16(out, 32, channels * bitsPerSample / 8)
        writeInt16(out, 34, bitsPerSample)
        writeTag(out, 36, "data")
        writeInt32(out, 40, dataBytes)
        return out
    }

    private fun tag(bytes: ByteArray, at: Int): String =
        buildString { for (i in 0 until 4) append(bytes[at + i].toInt().toChar()) }

    private fun writeTag(out: ByteArray, at: Int, value: String) {
        for (i in value.indices) out[at + i] = value[i].code.toByte()
    }

    private fun readInt16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readInt16Signed(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)

    private fun readInt32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            (bytes[at + 3].toInt() shl 24)

    private fun readInt64(bytes: ByteArray, at: Int): Long =
        (readInt32(bytes, at).toLong() and 0xFFFFFFFFL) or (readInt32(bytes, at + 4).toLong() shl 32)

    private fun writeInt16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
