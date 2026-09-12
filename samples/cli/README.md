# afdsp — the command line sample

A small JVM tool that puts [afterfade-dsp](../..) behind a command line, so you can hear and see
what the library does before writing any code. Every number it prints and every sample it writes
comes from the library; the only things this sample adds are argument parsing, file IO and the
PNG drawing (JDK `BufferedImage` / `ImageIO`, since drawing is not the library's job).

## Running it

From the repository root:

```sh
./gradlew -p samples :cli:run --args="info docs/demo.wav"
```

Paths on the command line are relative to the repository root. If you would rather have a plain
executable than a Gradle invocation:

```sh
./gradlew -p samples :cli:installDist
samples/cli/build/install/afdsp/bin/afdsp info docs/demo.wav
```

## Commands

| Command | What it shows |
| --- | --- |
| `info <in.wav>` | sample rate, length, `rms`, `spectralCentroid`, `zeroCrossingRate` |
| `tempo <in.wav>` | `estimateTempo` |
| `key <in.wav>` | `estimateKey` |
| `pitch <in.wav>` | `estimatePitch` + `hzToNote` over the whole file |
| `tape <in.wav> <out.wav>` | lowpass, `tapeWarble`, `softSaturate`, `vinylNoise` |
| `ambience <out.wav>` | `Rng` + `butterworth` + `sosfilt`, no input at all |
| `demo <out.wav>` | the deterministic 8-second demo track |
| `plot <in.wav> <out.png>` | spectrogram, onset envelope, onsets, tempo and key |

### info

```sh
./gradlew -p samples :cli:run --args="info docs/demo.wav"
```

```
sample rate        44100 Hz
length             8.00 s (352800 samples)
rms                0.2844
spectralCentroid   764.1 Hz
zeroCrossingRate   0.0169
```

### tempo

```sh
./gradlew -p samples :cli:run --args="tempo docs/demo.wav"        # about 96 BPM
```

### key

```sh
./gradlew -p samples :cli:run --args="key docs/demo.wav"          # D minor (confidence 0.21)
```

### pitch

```sh
./gradlew -p samples :cli:run --args="pitch docs/demo.wav"        # 73.8 Hz, D2
```

### tape

Lowpass at 12 kHz, wow and flutter, gentle saturation, a little hiss, then peak normalise.
`--depth`, `--drive` and `--hiss` are optional and default to `0.003`, `0.3` and `0.004`.

```sh
./gradlew -p samples :cli:run --args="tape in.wav tape.wav --drive 0.6 --hiss 0.01"
```

### ambience

Sound from nothing: seeded gaussian noise through a 4th-order Butterworth lowpass. The same seed
gives the same file on every platform. `--seed`, `--seconds` and `--cutoff` default to `7`, `10`
and `800`.

```sh
./gradlew -p samples :cli:run --args="ambience ambience.wav --seed 12 --seconds 30 --cutoff 400"
```

### demo

Writes the eight-second track the README hero image is drawn from — a filtered noise bed, a kick
and clap pattern at 96 BPM, and a sustained D minor triad, all built from library parts.

```sh
./gradlew -p samples :cli:run --args="demo docs/demo.wav"
```

### plot

A 1600x600 PNG: a log-frequency spectrogram from `RealFftPlan` and `hanning` (2048-point FFT,
hop 512, dB scale), `onsetStrength` drawn underneath, `pickOnsets` as vertical ticks, and a
caption with `estimateTempo` and `estimateKey`.

```sh
./gradlew -p samples :cli:run --args="plot docs/demo.wav docs/hero.png"
```

## Limits

WAV in, WAV or PNG out — the library has no MP3 or AAC decoding, so convert first if you need to.
Multi-channel input is folded to mono on decode.
