package com.afterfade.dsp.cli

import com.afterfade.dsp.FilterType
import com.afterfade.dsp.Rng
import com.afterfade.dsp.butterworth
import com.afterfade.dsp.estimateKey
import com.afterfade.dsp.estimatePitch
import com.afterfade.dsp.estimateTempo
import com.afterfade.dsp.hzToNote
import com.afterfade.dsp.io.Wav
import com.afterfade.dsp.io.WavAudio
import com.afterfade.dsp.peakNormalized
import com.afterfade.dsp.rms
import com.afterfade.dsp.softSaturate
import com.afterfade.dsp.sosfilt
import com.afterfade.dsp.spectralCentroid
import com.afterfade.dsp.tapeWarble
import com.afterfade.dsp.vinylNoise
import com.afterfade.dsp.zeroCrossingRate
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private const val USAGE = """afdsp — try afterfade-dsp from the command line

Usage:
  info     <in.wav>                                    sample rate, length, rms, centroid, ZCR
  tempo    <in.wav>                                    estimated tempo in BPM
  key      <in.wav>                                    estimated key
  pitch    <in.wav>                                    estimated pitch of the whole file
  tape     <in.wav> <out.wav> [--depth D] [--drive D] [--hiss A]
                                                       lowpass + warble + saturation + hiss
  ambience <out.wav> [--seed N] [--seconds S] [--cutoff HZ]
                                                       filtered noise, no input at all
  demo     <out.wav>                                   generate the deterministic demo track
  plot     <in.wav> <out.png>                          spectrogram + onsets + caption
"""

object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println(USAGE)
            exitProcess(1)
        }
        try {
            when (val command = args[0]) {
                "info" -> info(need(args, 1, "info <in.wav>"))
                "tempo" -> tempo(need(args, 1, "tempo <in.wav>"))
                "key" -> key(need(args, 1, "key <in.wav>"))
                "pitch" -> pitch(need(args, 1, "pitch <in.wav>"))
                "tape" -> tape(args)
                "ambience" -> ambience(args)
                "demo" -> demo(need(args, 1, "demo <out.wav>"))
                "plot" -> plot(need(args, 1, "plot <in.wav> <out.png>"), need(args, 2, "plot <in.wav> <out.png>"))
                "help", "--help", "-h" -> println(USAGE)
                else -> fail("unknown command '$command'\n\n$USAGE")
            }
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "bad input")
        }
    }

    // --- commands --------------------------------------------------------------------------

    private fun info(path: String) {
        val audio = read(path)
        val y = audio.samples
        val sr = audio.sampleRate
        println("file               $path")
        println("sample rate        $sr Hz")
        println("length             ${fmt(y.size.toDouble() / sr, 2)} s (${y.size} samples)")
        println("source             ${audio.sourceChannels} ch, ${audio.sourceBitsPerSample}-bit")
        println("rms                ${fmt(rms(y).toDouble(), 4)}")
        println("spectralCentroid   ${fmt(spectralCentroid(y, sr), 1)} Hz")
        println("zeroCrossingRate   ${fmt(zeroCrossingRate(y).toDouble(), 4)}")
    }

    private fun tempo(path: String) {
        val audio = read(path)
        val bpm = estimateTempo(audio.samples, audio.sampleRate)
        println(if (bpm == null) "no clear tempo" else "about ${bpm.roundToInt()} BPM")
    }

    private fun key(path: String) {
        val audio = read(path)
        val k = estimateKey(audio.samples, audio.sampleRate)
        println(if (k == null) "no clear key" else "${k.tonic} ${k.scale} (confidence ${fmt(k.confidence, 2)})")
    }

    private fun pitch(path: String) {
        val audio = read(path)
        val hz = estimatePitch(audio.samples, audio.sampleRate)
        if (hz == null) {
            println("no pitch found")
            return
        }
        val (name, octave) = hzToNote(hz)
        println("${fmt(hz, 1)} Hz, $name$octave")
    }

    private fun tape(args: Array<String>) {
        val inPath = need(args, 1, "tape <in.wav> <out.wav>")
        val outPath = need(args, 2, "tape <in.wav> <out.wav>")
        val opts = Options(args, from = 3)
        val depth = opts.double("--depth", 0.003)
        val drive = opts.double("--drive", 0.3)
        val hissAmp = opts.double("--hiss", 0.004)
        opts.done()

        val audio = read(inPath)
        val sr = audio.sampleRate

        var y = sosfilt(butterworth(2, 12000.0, sr, FilterType.LOWPASS), audio.samples) // high cut
        y = tapeWarble(y, sr, depth = depth, rateHz = 0.4)                              // wow and flutter
        y = softSaturate(y, drive = drive)                                              // gentle saturation
        val hiss = vinylNoise(y.size, sr, amplitude = hissAmp)                          // hiss
        for (i in y.indices) y[i] += hiss[i]

        write(outPath, Wav.encodePcm16(peakNormalized(y, 0.9f), sr))
        println("wrote $outPath — ${fmt(y.size.toDouble() / sr, 2)} s at $sr Hz")
        println("  lowpass 12000 Hz, warble depth $depth, drive $drive, hiss $hissAmp")
    }

    private fun ambience(args: Array<String>) {
        val outPath = need(args, 1, "ambience <out.wav>")
        val opts = Options(args, from = 2)
        val seed = opts.long("--seed", 7L)
        val seconds = opts.double("--seconds", 10.0)
        val cutoff = opts.double("--cutoff", 800.0)
        opts.done()
        require(seconds > 0.0) { "--seconds must be positive, was $seconds" }

        val sr = 44100
        val noise = Rng(seed).gaussianNoise((sr * seconds).toInt(), 0.2)
        val bed = sosfilt(butterworth(4, cutoff, sr, FilterType.LOWPASS), noise)
        write(outPath, Wav.encodePcm16(peakNormalized(bed, 0.5f), sr))
        println("wrote $outPath — ${fmt(seconds, 2)} s at $sr Hz")
        println("  seed $seed, lowpass ${fmt(cutoff, 0)} Hz")
    }

    private fun demo(outPath: String) {
        val track = Demo.render()
        write(outPath, Wav.encodePcm16(track.samples, track.sampleRate))
        println("wrote $outPath — ${fmt(track.samples.size.toDouble() / track.sampleRate, 2)} s at ${track.sampleRate} Hz")
        val bpm = estimateTempo(track.samples, track.sampleRate)
        val k = estimateKey(track.samples, track.sampleRate)
        println("  ${if (bpm == null) "no clear tempo" else "about ${bpm.roundToInt()} BPM"}, ${k?.let { "${it.tonic} ${it.scale}" } ?: "no clear key"}")
    }

    private fun plot(inPath: String, outPath: String) {
        val audio = read(inPath)
        val bytes = Plot.render(audio, File(inPath).name)
        write(outPath, bytes)
        println("wrote $outPath — ${Plot.WIDTH}x${Plot.HEIGHT}, ${bytes.size} bytes")
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun read(path: String): WavAudio {
        val file = File(path)
        if (!file.isFile) fail("no such file: $path")
        return Wav.decode(file.readBytes())
    }

    private fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun need(args: Array<String>, index: Int, usage: String): String =
        args.getOrNull(index) ?: fail("missing argument\n\nUsage: $usage")

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(2)
    }
}

/** `--name value` flags, with a check that nothing was misspelled. */
private class Options(args: Array<String>, from: Int) {
    private val values = LinkedHashMap<String, String>()

    init {
        var i = from
        while (i < args.size) {
            val name = args[i]
            require(name.startsWith("--")) { "expected a --flag, got '$name'" }
            val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("$name needs a value")
            values[name] = value
            i += 2
        }
    }

    fun double(name: String, default: Double): Double {
        val raw = values.remove(name) ?: return default
        return raw.toDoubleOrNull() ?: throw IllegalArgumentException("$name expects a number, got '$raw'")
    }

    fun long(name: String, default: Long): Long {
        val raw = values.remove(name) ?: return default
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name expects a whole number, got '$raw'")
    }

    fun done() {
        require(values.isEmpty()) { "unknown flag(s): ${values.keys.joinToString(", ")}" }
    }
}

internal fun fmt(value: Double, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
