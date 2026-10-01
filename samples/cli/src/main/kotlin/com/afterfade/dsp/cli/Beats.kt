package com.afterfade.dsp.cli

import com.afterfade.dsp.FilterType
import com.afterfade.dsp.Rng
import com.afterfade.dsp.Waveform
import com.afterfade.dsp.addAt
import com.afterfade.dsp.adsr
import com.afterfade.dsp.applyEnvelope
import com.afterfade.dsp.bitCrush
import com.afterfade.dsp.butterworth
import com.afterfade.dsp.butterworthBandpass
import com.afterfade.dsp.compressor
import com.afterfade.dsp.expDecay
import com.afterfade.dsp.expSweep
import com.afterfade.dsp.fade
import com.afterfade.dsp.feedbackDelay
import com.afterfade.dsp.filterSweep
import com.afterfade.dsp.gain
import com.afterfade.dsp.glide
import com.afterfade.dsp.hardClip
import com.afterfade.dsp.midiToHz
import com.afterfade.dsp.mix
import com.afterfade.dsp.oscillator
import com.afterfade.dsp.parsePattern
import com.afterfade.dsp.peakNormalized
import com.afterfade.dsp.reverse
import com.afterfade.dsp.sampleHold
import com.afterfade.dsp.schroederReverb
import com.afterfade.dsp.sidechainDuck
import com.afterfade.dsp.softSaturate
import com.afterfade.dsp.sosfilt
import com.afterfade.dsp.stepPositions
import com.afterfade.dsp.supersaw
import com.afterfade.dsp.tapeWarble
import com.afterfade.dsp.vinylNoise

/**
 * Two full-length beats (about a minute and a half each) written entirely with library parts, to
 * show how the oscillator, envelope, sequencer, dynamics and arrangement building blocks fit
 * together. The drum sounds, patterns, progressions and song forms here are recipes: they belong
 * to this sample, not to the library.
 *
 * - [boomBap]: 90 BPM, C minor. Intro, verse, hook, verse, hook, outro.
 * - [hyperpop]: 160 BPM, E major. Intro, verse, build, drop, verse, build, drop, bridge, drop,
 *   outro.
 *
 * Each section is rendered into its own stems (drums, music, kick positions), treated as a
 * section (filter sweeps, crushing, reverb), then laid into the song. Every random number comes
 * from a seeded [Rng], so each render is identical.
 */
object Beats {

    const val SAMPLE_RATE = 44100
    private const val SR = SAMPLE_RATE

    private fun secs(s: Double) = (s * SR).toInt()

    /** One rendered section: drums, everything else, and where the kicks fell (for ducking). */
    private class Stems(length: Int) {
        val drums = FloatArray(length)
        val music = FloatArray(length)
        val kicks = ArrayList<Int>()
    }

    /** Song buffer that sections are laid into one after another. */
    private class Song(val bar: Int, bars: Int, tail: Int) {
        val out = FloatArray(bar * bars + tail)
        var cursor = 0
        fun add(section: FloatArray, bars: Int) {
            addAt(out, section, cursor)
            cursor += bar * bars
        }
    }

    // ---- shared drum voices ------------------------------------------------------------------

    private fun kick(rng: Rng, startHz: Double, endHz: Double, sweepRate: Double, decay: Double, drive: Double): FloatArray {
        val n = secs(0.6)
        val body = applyEnvelope(oscillator(expSweep(startHz, endHz, sweepRate, n, SR), SR), expDecay(n, SR, decay))
        addAt(body, rng.gaussianNoise(secs(0.005), 0.4), 0) // click
        return softSaturate(body, drive)
    }

    private fun snare(rng: Rng, toneHz: Double, lowHz: Double, highHz: Double, decay: Double): FloatArray {
        val n = secs(0.35)
        val noise = sosfilt(butterworthBandpass(2, lowHz, highHz, SR), rng.gaussianNoise(n, 0.5))
        val tone = gain(applyEnvelope(oscillator(toneHz, n, SR), expDecay(n, SR, 30.0)), 0.6)
        return mix(applyEnvelope(noise, expDecay(n, SR, decay)), tone)
    }

    private val hatFilter = butterworth(2, 7000.0, SR, FilterType.HIGHPASS)
    private fun hat(rng: Rng, seconds: Double, decay: Double): FloatArray {
        val n = secs(seconds)
        return applyEnvelope(sosfilt(hatFilter, rng.gaussianNoise(n, 0.5)), expDecay(n, SR, decay))
    }

    private fun crash(rng: Rng): FloatArray {
        val n = secs(1.6)
        val noise = sosfilt(butterworth(2, 4500.0, SR, FilterType.HIGHPASS), rng.gaussianNoise(n, 0.5))
        return applyEnvelope(noise, expDecay(n, SR, 2.6))
    }

    /** Plays [notes] (step, midi, lengthInSteps) from [grid] through [voice] into [into]. */
    private fun melody(
        into: FloatArray,
        grid: IntArray,
        offsetStep: Int,
        notes: List<Triple<Int, Int, Int>>,
        voice: (midi: Int, n: Int) -> FloatArray,
    ) {
        for ((step, midi, len) in notes) {
            val s = offsetStep + step
            if (s + len >= grid.size) continue
            addAt(into, voice(midi, grid[s + len] - grid[s]), grid[s])
        }
    }

    // ==== boom bap ===============================================================================

    private enum class BoomBapPart { INTRO, VERSE, HOOK, OUTRO }

    /** Song form as (part, bars): 34 bars, about 91 seconds at 90 BPM. */
    private val boomBapForm = listOf(
        BoomBapPart.INTRO to 4, BoomBapPart.VERSE to 8, BoomBapPart.HOOK to 4,
        BoomBapPart.VERSE to 8, BoomBapPart.HOOK to 8, BoomBapPart.OUTRO to 2,
    )

    // Cm9 | Abmaj9 | Fm9 | G7(b9)
    private val bbChords = listOf(
        listOf(48, 55, 58, 62, 63), listOf(44, 51, 55, 58, 60),
        listOf(41, 48, 51, 55, 56), listOf(43, 50, 53, 56, 59),
    )
    private val bbRoots = listOf(36, 32, 29, 31)

    // Two-bar hook phrase in C minor pentatonic: (16th step, midi, length in 16ths)
    private val bbHook = listOf(
        Triple(0, 79, 3), Triple(3, 77, 3), Triple(6, 75, 2), Triple(8, 72, 6), Triple(14, 75, 2),
        Triple(16, 77, 3), Triple(19, 75, 3), Triple(22, 72, 2), Triple(24, 70, 4), Triple(28, 72, 4),
    )

    fun boomBap(): Track {
        val rng = Rng(874)
        val bpm = 90.0
        val swing = 0.58
        val bar = stepPositions(bpm, SR, 17, swing = swing)[16]
        val totalBars = boomBapForm.sumOf { it.second }
        val song = Song(bar, totalBars, tail = secs(2.0))

        val k = kick(rng, 158.0, 48.0, 28.0, 7.0, 1.2)
        val s = snare(rng, 185.0, 1200.0, 7000.0, 14.0)
        val kickA = parsePattern("x... .... ..x. x...")
        val kickB = parsePattern("x... ...x ..x. ..x.")
        val snareP = parsePattern(".... x... .... x...")

        var chordBar = 0
        for ((part, bars) in boomBapForm) {
            val grid = stepPositions(bpm, SR, 16 * bars + 1, swing = swing)
            val stems = Stems(bar * bars + secs(2.0))
            val drums = part == BoomBapPart.VERSE || part == BoomBapPart.HOOK
            val keys = FloatArray(stems.music.size)
            val bass = FloatArray(stems.music.size)
            val lead = FloatArray(stems.music.size)

            for (b in 0 until bars) {
                val kp = if (b % 2 == 0) kickA else kickB
                // every fourth bar drops the drums out for the last beat: room for the rapper
                val breakBar = drums && b % 4 == 3 && part == BoomBapPart.VERSE
                for (i in 0 until 16) {
                    val at = grid[16 * b + i]
                    if (breakBar && i >= 12) continue
                    if (drums && kp[i]) { addAt(stems.drums, gain(k, 0.9), at); stems.kicks += at }
                    if (drums && snareP[i]) addAt(stems.drums, gain(s, 0.75), at + secs(0.012)) // lazy snare
                    if (drums || (part == BoomBapPart.INTRO && b == bars - 1 && i >= 8)) {
                        val open = i == 14 && (b % 4 == 3 || part == BoomBapPart.HOOK && b % 2 == 1)
                        val vel = if (i % 2 == 0) 0.32 else 0.16 + rng.nextDouble() * 0.08
                        addAt(stems.drums, gain(if (open) hat(rng, 0.25, 14.0) else hat(rng, 0.05, 80.0), vel), at)
                    }
                }
                val c = chordBar++ % 4
                addAt(keys, rhodes(bbChords[c], bar), grid[16 * b])
                if (drums) {
                    val hits = (0 until 16).filter { kp[it] }
                    for ((h, i) in hits.withIndex()) {
                        val end = if (h + 1 < hits.size) grid[16 * b + hits[h + 1]] else grid[16 * (b + 1)]
                        val n = ((end - grid[16 * b + i]) * 0.95).toInt()
                        val note = applyEnvelope(oscillator(midiToHz(bbRoots[c]), n, SR), adsr(n, SR, 0.005, 0.0, 1.0, 0.03))
                        addAt(bass, gain(note, 0.45), grid[16 * b + i])
                    }
                }
                if (part == BoomBapPart.HOOK && b % 2 == 0) melody(lead, grid, 16 * b, bbHook, ::bell)
            }

            var keysOut = sosfilt(butterworth(2, 2800.0, SR), gain(keys, 0.09))
            if (part == BoomBapPart.INTRO) keysOut = filterSweep(gain(keys, 0.09), SR, 350.0, 2800.0)
            if (part == BoomBapPart.OUTRO) keysOut = fade(filterSweep(gain(keys, 0.09), SR, 2800.0, 300.0), SR, outSec = 2.0)
            val leadOut = feedbackDelay(gain(lead, 0.08), SR, delaySec = 60.0 / bpm * 0.75, feedback = 0.35, wet = 0.4)
            val music = sidechainDuck(mix(keysOut, bass, leadOut), stems.kicks.toIntArray(), SR, depth = 0.55, releaseSec = 0.12)
            song.add(mix(stems.drums, music), bars)
        }

        var y = softSaturate(song.out, drive = 0.6)
        y = tapeWarble(y, SR, depth = 0.0015, rateHz = 0.4)
        y = mix(y, vinylNoise(y.size, SR, amplitude = 0.02, seed = 7))
        y = sosfilt(butterworth(2, 12000.0, SR), y)
        return Track(fade(peakNormalized(y, 0.9f), SR, inSec = 0.5, outSec = 1.5), SR)
    }

    /** Sine plus a decaying third-harmonic bell partial, with a slow decay and a 4.5 Hz tremolo. */
    private fun rhodes(notes: List<Int>, n: Int): FloatArray {
        val env = applyEnvelope(expDecay(n, SR, 0.9), adsr(n, SR, 0.003, 0.0, 1.0, 0.05))
        val tremolo = FloatArray(n).also { t -> oscillator(4.5, n, SR).forEachIndexed { i, v -> t[i] = 1f + 0.15f * v } }
        val voices = notes.map { m ->
            val f = midiToHz(m)
            val bell = gain(applyEnvelope(oscillator(f * 3.0, n, SR), expDecay(n, SR, 6.0)), 0.18)
            mix(oscillator(f, n, SR), bell)
        }
        return applyEnvelope(applyEnvelope(mix(*voices.toTypedArray()), env), tremolo)
    }

    /** Glassy lead: sine with a quiet octave partial and a short decay. */
    private fun bell(midi: Int, n: Int): FloatArray {
        val f = midiToHz(midi)
        val tone = mix(oscillator(f, n, SR), gain(oscillator(f * 2.0, n, SR), 0.25))
        return applyEnvelope(tone, applyEnvelope(expDecay(n, SR, 3.0), adsr(n, SR, 0.004, 0.0, 1.0, 0.03)))
    }

    // ==== hyperpop ===============================================================================

    private enum class HyperPart { INTRO, VERSE, BUILD, DROP, BRIDGE, OUTRO }

    /** Song form as (part, bars): 60 bars, 90 seconds at 160 BPM. BUILD must be 4 bars. */
    private val hyperForm = listOf(
        HyperPart.INTRO to 4, HyperPart.VERSE to 8, HyperPart.BUILD to 4, HyperPart.DROP to 8,
        HyperPart.VERSE to 8, HyperPart.BUILD to 4, HyperPart.DROP to 8,
        HyperPart.BRIDGE to 4, HyperPart.DROP to 8, HyperPart.OUTRO to 4,
    )

    // E | B | C#m | A, voiced high and wide
    private val hpChords = listOf(
        listOf(64, 68, 71, 76), listOf(63, 66, 71, 75),
        listOf(61, 64, 68, 73), listOf(61, 64, 69, 73),
    )
    private val hpRoots = listOf(40, 35, 37, 33)

    // Two-bar drop lead in E major, 8th notes: (16th step, midi, length in 16ths)
    private val hpLead = listOf(83, 85, 88, 85, 83, 80, 81, 83, 88, 87, 85, 83, 81, 80, 76, -1)
        .mapIndexedNotNull { i, m -> if (m < 0) null else Triple(i * 2, m, 2) }

    fun hyperpop(): Track {
        val rng = Rng(1600)
        val bpm = 160.0
        val bar = stepPositions(bpm, SR, 17)[16]
        val step16 = stepPositions(bpm, SR, 2)[1]
        val step32 = stepPositions(bpm, SR, 2, stepsPerBeat = 8)[1]
        val totalBars = hyperForm.sumOf { it.second }
        val song = Song(bar, totalBars, tail = secs(2.0))

        val k = kick(rng, 260.0, 55.0, 45.0, 9.0, 2.0)
        val s = snare(rng, 230.0, 1500.0, 9000.0, 18.0)
        val cr = crash(rng)
        val dropKick = parsePattern("x... .... x.x. ....")
        val verseKick = parsePattern("x... .... ..x. ....")
        val backbeat = parsePattern(".... x... .... x...")
        val halfTime = parsePattern(".... .... x... ....")

        var verseCount = 0
        for ((part, bars) in hyperForm) {
            val grid = stepPositions(bpm, SR, 16 * bars + 1)
            val len = bar * bars + secs(2.0)
            val stems = Stems(len)
            val stabs = FloatArray(len)
            val arp = FloatArray(len)
            val lead = FloatArray(len)
            val bass = FloatArray(len)
            if (part == HyperPart.VERSE) verseCount++

            for (b in 0 until bars) {
                val c = b % 4
                val start = grid[16 * b]
                val chord = hpChords[c]

                // ---- drums
                for (i in 0 until 16) {
                    val at = grid[16 * b + i]
                    when (part) {
                        HyperPart.DROP -> {
                            if (dropKick[i]) { addAt(stems.drums, k, at); stems.kicks += at }
                            if (backbeat[i]) addAt(stems.drums, gain(s, 0.8), at)
                            addAt(stems.drums, gain(hat(rng, 0.03, 120.0), if (i % 4 == 2) 0.35 else 0.22), at)
                            if (b % 2 == 1 && i >= 12) addAt(stems.drums, gain(hat(rng, 0.03, 120.0), 0.18), at + step32)
                        }
                        HyperPart.VERSE -> {
                            if (verseKick[i]) { addAt(stems.drums, gain(k, 0.85), at); stems.kicks += at }
                            if (halfTime[i]) addAt(stems.drums, gain(s, 0.7), at)
                            if (verseCount > 1 && i % 2 == 0) addAt(stems.drums, gain(hat(rng, 0.03, 120.0), 0.2), at)
                        }
                        HyperPart.BUILD -> {
                            // snare roll that doubles in speed every bar: quarters, 8ths, 16ths, 32nds
                            val every = intArrayOf(4, 2, 1, 1)[b]
                            val level = 0.25 + 0.6 * (16 * b + i) / (16.0 * bars)
                            if (i % every == 0 && !(b == bars - 1 && i >= 14)) {
                                addAt(stems.drums, gain(s, level), at)
                                if (b == bars - 1) addAt(stems.drums, gain(s, level), at + step32)
                            }
                        }
                        else -> {}
                    }
                }
                if (part == HyperPart.DROP && b == 0) addAt(stems.drums, gain(cr, 0.5), start)

                // ---- supersaw stabs on every 8th (drop, build) or held pads (bridge, intro, outro)
                if (part == HyperPart.DROP || part == HyperPart.BUILD) {
                    for (i in 0 until 16 step 2) {
                        val n = grid[16 * b + i + 2] - grid[16 * b + i]
                        addAt(stabs, gain(applyEnvelope(chordSaw(chord, n, rng), adsr(n, SR, 0.002, 0.08, 0.6, 0.02)), 0.07), grid[16 * b + i])
                    }
                } else if (part != HyperPart.VERSE) {
                    addAt(stabs, gain(applyEnvelope(chordSaw(chord, bar, rng), adsr(bar, SR, 0.25, 0.0, 1.0, 0.2)), 0.05), start)
                }

                // ---- arp: bright saw in drops, crushed chip pulse everywhere else
                for (i in 0 until 16) {
                    val m = chord[(i * 3) % chord.size] + 12
                    val n = grid[16 * b + i + 1] - grid[16 * b + i]
                    val note = if (part == HyperPart.DROP) {
                        applyEnvelope(oscillator(midiToHz(m), n, SR, Waveform.SAW), adsr(n, SR, 0.001, 0.05, 0.3, 0.01))
                    } else {
                        gain(applyEnvelope(oscillator(midiToHz(m), n, SR, Waveform.PULSE, duty = 0.25), adsr(n, SR, 0.001, 0.04, 0.5, 0.01)), 0.6)
                    }
                    addAt(arp, gain(note, if (part == HyperPart.DROP) 0.1 else 0.09), grid[16 * b + i])
                }

                // ---- lead over the drops, sparse in the bridge
                if (part == HyperPart.DROP && b % 2 == 0) melody(lead, grid, 16 * b, hpLead) { m, n -> sawLead(m, n, rng) }
                if (part == HyperPart.BRIDGE && b % 2 == 0) {
                    melody(lead, grid, 16 * b, hpLead.filterIndexed { i, _ -> i % 2 == 0 }.map { (st, m, _) -> Triple(st, m, 4) }) { m, n -> sawLead(m, n, rng) }
                }

                // ---- 808: one note per bar; in drops it glides up an octave into the next bar
                if (part == HyperPart.DROP || part == HyperPart.VERSE) {
                    val f = midiToHz(hpRoots[c])
                    val to = if (part == HyperPart.DROP && b % 2 == 1) f * 2.0 else f
                    val curve = glide(f, to, 0.08, bar, SR, startAt = grid[14])
                    val note = applyEnvelope(oscillator(curve, SR), adsr(bar, SR, 0.003, 0.3, 0.8, 0.02))
                    addAt(bass, hardClip(softSaturate(gain(note, 1.5), drive = 3.0), 0.8f), start)
                }
            }

            // ---- section treatment
            var arpOut = arp
            if (part != HyperPart.DROP) arpOut = bitCrush(sampleHold(arp, 3), 6)
            val leadOut = feedbackDelay(gain(lead, 0.09), SR, delaySec = 3.0 * step16 / SR, feedback = 0.4, wet = 0.45)
            var music = mix(sosfilt(butterworth(2, 180.0, SR, FilterType.HIGHPASS), mix(stabs, arpOut)), leadOut)
            when (part) {
                HyperPart.INTRO -> music = filterSweep(music, SR, 400.0, 9000.0)
                HyperPart.BUILD -> {
                    music = filterSweep(music, SR, 150.0, 3000.0, FilterType.HIGHPASS, resonance = 1.2)
                    val n = bar * bars
                    val riser = fade(filterSweep(rng.gaussianNoise(n, 0.25), SR, 400.0, 12000.0, FilterType.HIGHPASS, 2.0), SR, inSec = n.toDouble() / SR)
                    addAt(music, riser, 0)
                    addAt(music, gain(reverse(cr), 0.4), n - cr.size)
                    // a beat of silence (everything but the reversed crash) right before the drop
                    for (i in grid[16 * bars - 2] until n) { stems.drums[i] = 0f }
                }
                HyperPart.BRIDGE -> music = schroederReverb(music, SR, wet = 0.5f, feedback = 0.88f, damping = 0.4f)
                HyperPart.OUTRO -> music = fade(filterSweep(music, SR, 9000.0, 250.0), SR, outSec = 3.0)
                else -> {}
            }
            val pumped = sidechainDuck(mix(music, gain(bass, 0.5)), stems.kicks.toIntArray(), SR, depth = 0.85, releaseSec = 0.16)
            song.add(mix(stems.drums, pumped), bars)
        }

        var y = compressor(song.out, SR, thresholdDb = -14.0, ratio = 6.0, attackSec = 0.002, releaseSec = 0.08, makeupDb = 6.0)
        y = hardClip(peakNormalized(y, 1.3f), 0.95f) // loud on purpose
        return Track(fade(y, SR, outSec = 0.5), SR)
    }

    private fun chordSaw(chord: List<Int>, n: Int, rng: Rng): FloatArray =
        mix(*chord.map { m -> supersaw(DoubleArray(n) { midiToHz(m) }, SR, seed = rng.nextInt(1 shl 30).toLong()) }.toTypedArray())

    private fun sawLead(midi: Int, n: Int, rng: Rng): FloatArray {
        val tone = supersaw(DoubleArray(n) { midiToHz(midi) }, SR, voices = 3, detune = 0.006, seed = rng.nextInt(1 shl 30).toLong())
        return applyEnvelope(tone, adsr(n, SR, 0.003, 0.1, 0.7, 0.03))
    }
}
