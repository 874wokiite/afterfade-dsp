# afterfade-dsp browser playground

A single page that runs the library in the browser through Kotlin/Wasm, so you can hear what
`afterfade-dsp` does without installing anything.

The page is bilingual: it starts in Japanese when the browser language is Japanese and in English otherwise, and the button in the header switches between the two. Strings live in `I18n.kt`; static text is tagged with `data-i18n` in `index.html`.

- **Analyse** — drop or pick a WAV file and see sample rate, duration, `estimateTempo`,
  `estimateKey`, `estimatePitch` + `hzToNote`, `rms` and `spectralCentroid`, plus a waveform and an
  averaged spectrum drawn on a `<canvas>` with `RealFftPlan` and `hanning`.
- **Tape treatment** — the tape chain from the top-level README (`butterworth` low-pass →
  `tapeWarble` → `softSaturate` → `vinylNoise` → `peakNormalized`) with a slider for each amount.
  Play goes through the Web Audio API; Download hands back `Wav.encodePcm16` output as a `.wav`.
- **Generate ambience** — `Rng(seed).gaussianNoise` through a 4th-order `butterworth` low-pass,
  with no input audio at all. The same seed gives the same samples here as on iOS, Android and the
  JVM.

Nothing is uploaded: the file you choose is read with `FileReader` and processed in the page.

## Run it locally

From the repository root:

```sh
./gradlew -p samples :playground:wasmJsBrowserDevelopmentRun
```

This starts a webpack dev server (it prints the URL, by default <http://localhost:8080/>) and
rebuilds on change. A recent browser with WebAssembly garbage collection is required — Chrome or
Edge 119+, Firefox 120+, Safari 18.4+.

## Build the static site

```sh
./gradlew -p samples :playground:wasmJsBrowserDistribution
```

The output is a plain static site — `index.html`, `styles.css`, `playground.js` and one `.wasm`
file — in:

```
samples/playground/build/dist/wasmJs/productionExecutable/
```

Serve that directory with any file server, for example `python3 -m http.server 8080` from inside
it. It needs no server-side code and no special headers, so it can be published as-is to GitHub
Pages (copy the directory into the branch or workflow artifact that Pages serves) or to any other
static host.

## How it is put together

| File | What is in it |
| --- | --- |
| `src/wasmJsMain/kotlin/.../Main.kt` | wiring: file loading, the three sections, formatting |
| `src/wasmJsMain/kotlin/.../Draw.kt` | waveform and spectrum drawing on `<canvas>` |
| `src/wasmJsMain/kotlin/.../Browser.kt` | all the JavaScript interop: Web Audio, Blob downloads, typed arrays |
| `src/wasmJsMain/resources/index.html` | the page itself |
| `src/wasmJsMain/resources/styles.css` | hand-written stylesheet, no framework |

DOM types come from [`kotlinx-browser`](https://github.com/Kotlin/kotlinx-browser). The Web Audio
API is not part of that package, so `Browser.kt` declares the handful of `external` members it
uses. Kotlin arrays cannot cross into JavaScript directly; `FloatArray.toFloat32Array()` and
`ByteArray.toInt8Array()` (from `org.khronos.webgl`) do the copying.

All processing runs on the main thread. Each button shows a "working…" state and yields once
before starting, so the status line reaches the screen first; with files of a few seconds that is
all the responsiveness this needs.
