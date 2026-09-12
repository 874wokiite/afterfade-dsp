package com.afterfade.dsp.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavTest {

    @Test
    fun pcm16ClipsInsteadOfWrapping() {
        val loud = floatArrayOf(2.0f, -2.0f, 0f)
        val decoded = Wav.decode(Wav.encodePcm16(loud, 44100)).samples
        assertTrue(decoded[0] > 0.99f, "positive overshoot must clip high, got ${decoded[0]}")
        assertTrue(decoded[1] < -0.99f, "negative overshoot must clip low, got ${decoded[1]}")
    }

    @Test
    fun stereoIsAveragedToMono() {
        // Hand-built stereo PCM16: left = +1.0 rail, right = silence -> mono should be ~0.5.
        val frames = 4
        val bytes = ByteArray(44 + frames * 4)
        Wav.encodePcm16(FloatArray(frames * 2), 44100).copyInto(bytes, 0, 0, 44)
        // Patch the header to describe 2 channels.
        bytes[22] = 2; bytes[23] = 0
        writeLe32(bytes, 28, 44100 * 2 * 2)
        bytes[32] = 4; bytes[33] = 0
        writeLe32(bytes, 4, 36 + frames * 4)
        writeLe32(bytes, 40, frames * 4)
        for (f in 0 until frames) {
            val at = 44 + f * 4
            bytes[at] = 0xFF.toByte(); bytes[at + 1] = 0x7F // left = 32767
            bytes[at + 2] = 0; bytes[at + 3] = 0 // right = 0
        }

        val decoded = Wav.decode(bytes)
        assertEquals(2, decoded.sourceChannels)
        assertEquals(frames, decoded.samples.size)
        for (v in decoded.samples) assertEquals(0.5f, v, 1e-4f)
    }

    @Test
    fun rejectsNonRiffInput() {
        val bad = ByteArray(64) { 0 }
        val error = runCatching { Wav.decode(bad) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, got $error")
    }

    private fun writeLe32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
