# CLAUDE.md

Pure Kotlin DSP for Kotlin Multiplatform, extracted unchanged from the Afterfade app's audio
engine. Package `com.afterfade.dsp`, zero dependencies, `commonMain` only.

## Relation to the app

The Afterfade app (private repo, expected at `../afterfade`) consumes this library through
`includeBuild("../afterfade-dsp")`. The dependency is one-way: app → library. Nothing in here
may reference the app.

**What lives where.** This library holds *mechanisms*: FFT, SOS filters, resampling, the seeded
RNG, YIN and phase vocoder (`Pitch.kt`), mel/onset detection (`Transient.kt`), `tapeWarble` /
`vinylNoise` / `softSaturate` (`Effects.kt`) and WAV I/O. The app keeps *recipes*: chord
templates, tone synthesis, drum kits, and the master chains (`lofiMaster`, `nightMaster` in the
app's `cassette/Masters.kt`).

The line in one sentence: **if passing a different number gives a different sound, it is a
mechanism and belongs here; if the numbers are fixed and the thing has a name, it is a recipe
and stays in the app.** Do not add master chains, templates or cassette-specific constants here.

## Rules for changing this library

- **Logic changes must keep the app's `CassetteGoldenTest` green.** That test (in the app,
  `composeApp/src/androidHostTest/`) compares the rendered cassette bit for bit. After editing
  here, run both:
  ```
  ./gradlew jvmTest
  (cd ../afterfade && ./gradlew :composeApp:testAndroidHostTest)
  ```
  If the golden test fails, the change altered the app's sound. Either the change is wrong or
  it needs a deliberate golden update on the app side, decided there, not here.
- **Numerics are deliberate.** Comments such as "float32 like numpy" or "divide first, then
  multiply" reproduce the original Python engine's rounding. Do not "simplify" arithmetic order.
- **No `pow` / `log` for values that can be computed exactly.** `semitoneRatio()` uses a rounded
  table plus powers of two so results are bit-identical on the JVM and Kotlin/Native.
- **Same tests on every target.** Tests live in `commonTest` only and run on the JVM and the iOS
  simulator. Do not add a JVM-only test source set.
- **API visibility.** The app can only use `public` symbols. If the app needs an `internal`
  function, make it public here with KDoc (precedent: `semitoneRatio`).
- **New general-purpose parts start in the app.** Bit crushing, square waves, filter design and
  the like are written in the app's `engine/` first, then moved here once the cassette that uses
  them has shipped and stabilised. Moving is a copy plus package rename; the app then only swaps
  imports.
- This repo is public. No keys, no cassette numbers, no templates.

## Build and test

```
./gradlew jvmTest                  # fast, runs on the host
./gradlew iosSimulatorArm64Test    # same suite on the iOS simulator
./gradlew assemble                 # compile every target
```

CI runs on pull requests and manual dispatch only. A Claude Code hook in `.claude/settings.json`
runs `jvmTest` before any `git push` from Claude and blocks the push on failure.

Not on Maven Central yet. `group = "io.github.874wokiite"`, `version = "0.1.0"` are the
coordinates the app's `includeBuild` substitutes; the artifact is always the sibling checkout's
HEAD.
