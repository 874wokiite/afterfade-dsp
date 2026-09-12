# afterfade-dsp

[English](README.md) | 日本語

Kotlin Multiplatform 向けの純 Kotlin 音声 DSP ライブラリ。外部依存ゼロ、`commonMain` だけで完結し、
同じテストが Android・iOS・JVM で通ります。

[Afterfade](https://apps.apple.com/us/app/afterfade/id6800247416) の本番音声エンジンから、
ロジックを変えずに切り出したものです。iOS と Android で今日動いています。
数秒の音声を共有 Kotlin コードでオフライン処理するために作られており、低レイテンシのライブエフェクトは対象外です。

## なぜ作ったか

KMP の共有コードに音声解析・加工を置きたいとき、今ある選択肢はどれも重いものでした。

- JVM の音声ライブラリは `java.*` に依存していて Kotlin/Native ではコンパイルできず、iOS 側が空になる
- iOS は Accelerate、Android は別のもの、という構成にすると実装が2本になる。テストも2本、浮動小数の結果も OS ごとにずれる
- C ライブラリを cinterop で包むと、ビルド構成がそれ自体で一つのプロジェクトになる

このライブラリは4つ目の選択肢です。素の Kotlin、`expect`/`actual` は一切なし、
シード付き乱数と自前の FFT により、どのターゲットでも同じ結果が出ます。

## 中身

| 領域 | 関数 |
| --- | --- |
| ピッチ推定 | `yinPitch`, `estimatePitch`, `hzToNote`, `hzToMidi`, `nearestScaleSemitones`, `semitoneRatio` |
| ピッチシフト | `phaseVocoder`, `pitchShift`, `pitchShiftToKey`, `timeStretch` |
| キー推定 | `chroma`, `estimateKey` |
| オンセット / トランジェント | `melFilterbank`, `onsetStrength`, `pickOnsets`, `detectTransients`, `extractBed` |
| テンポ | `estimateTempo`, `estimateTempoFromEnvelope` |
| 特徴量 | `rms`, `zeroCrossingRate`, `spectralCentroid` |
| エフェクト | `tapeWarble`, `vinylNoise`, `softSaturate` |
| フィルタ | `SosFilters`（44.1 kHz 用に設計済みの Butterworth セクション）, `sosfilt` |
| FFT | `Radix2Fft`, `RealFftPlan`, `Fft`, `rfftFreq` |
| リサンプル | `resample`, `resampleTo` |
| 乱数 | `Rng`（シード付き。全プラットフォームでビット一致） |
| 配列ユーティリティ | `hanning`, `linspace`, `median`, `padReflect`, `padEdge`, `clip`, `rint`, `peakNormalized` |
| 入出力 | `Wav.decode`, `Wav.encodePcm16`, `Wav.encodeFloat32` |

すべての関数は `FloatArray` または `DoubleArray` を受け取り、新しい配列を返します。
音声はモノラルで、WAV の読み込みではマルチチャンネルをモノラルに畳みます。

## 活用例

「マイクやファイルから取った数秒の音を、共有コードで解析・加工する」ことができます。
ライブラリの外で要るのは、音の入口（マイク、OS のデコーダ）と出口（再生、描画、共有）だけです。

| やりたいこと | 使う部品 | ライブラリの外で要るもの |
| --- | --- | --- |
| チューナー、ボーカル練習、声の高さの記録 | `estimatePitch` / `hzToNote` / `nearestScaleSemitones` | マイクのストリーミング入力 |
| テンポ（BPM）推定、ビート同期の演出 | `estimateTempo` / `onsetStrength` / `pickOnsets` | 音源の読み込み（WAV 以外は OS のデコーダ） |
| ボイスメモをテープ風・レコード風にする | `tapeWarble` / `vinylNoise` / `softSaturate` / `Wav` | 録音と共有 |
| 波形・スペクトルの可視化 | `Fft` / `RealFftPlan` / `hanning` / `rfftFreq` | Canvas 描画 |
| 環境音・ノイズ・睡眠音の生成 | `Rng` / `sosfilt` / `resample` / `Wav` | 再生だけ（入力は不要） |
| 環境音から素材を切り出して曲にする（Afterfade 本体） | `detectTransients` / `pitchShiftToKey` / `extractBed` | 曲を組み立てるレシピ |

### チューナー

2048 サンプル程度のマイクバッファを 10〜20 fps で渡せば、リアルタイムに追従します。

```kotlin
val hz = estimatePitch(buffer, sr) ?: return        // 無音や短すぎる入力は null
val (name, octave) = hzToNote(hz)                   // 例: "A" to 4
val midi = 69.0 + 12.0 * log2(hz / 440.0)
val cents = ((midi - round(midi)) * 100).roundToInt()   // 最寄りの音からのずれ
println("$name$octave ${if (cents >= 0) "+" else ""}$cents cents")
```

### キー推定

ある録音のキーを推定して、別の音をそのキーに合わせます。`estimateKey` が返す音名とスケールの文字列は、
そのまま `pitchShiftToKey` に渡せます。

```kotlin
val key = estimateKey(reference, sr) ?: return   // 無音は null
println("${key.tonic} ${key.scale}（2位との差 ${key.confidence}）")
val tuned = pitchShiftToKey(other, sr, key.tonic, key.scale)
```

### テンポ（BPM）推定

オンセット強度エンベロープの自己相関でテンポを求めます。半分・倍のテンポと迷う場合は、
120 BPM 付近を中心とした事前分布で寄せます。

```kotlin
val bpm = estimateTempo(y, sr) ?: return            // 無音や短すぎる入力は null
println("about ${bpm.roundToInt()} BPM")
```

素材が分かっているなら `estimateTempo(y, sr, minBpm = 70.0, maxBpm = 140.0)` で探索範囲を狭められます。
エンベロープを別の用途（描画や `pickOnsets`）で既に計算しているなら、
`estimateTempoFromEnvelope(env, sr)` に渡せば重い部分を二度計算せずに済みます。

### ボイスメモをテープ風にする

順番と量は自由です。以下は一例で、Afterfade 本体のマスターチェーンとは別の値にしてあります。
`SosFilters` の係数は 44.1 kHz 用なので、他のレートの音声は先に `resampleTo` で合わせてください。

```kotlin
val audio = Wav.decode(bytes)
val sr = audio.sampleRate
var y = sosfilt(SosFilters.LOWPASS_12000_HZ, audio.samples)   // 高域を落とす
y = tapeWarble(y, sr, depth = 0.003, rateHz = 0.4)            // テープの揺れ
y = softSaturate(y, drive = 0.3)                              // 軽い飽和
val hiss = vinylNoise(y.size, sr, amplitude = 0.004)          // ヒスノイズ
for (i in y.indices) y[i] += hiss[i]
val out = Wav.encodePcm16(peakNormalized(y, 0.9f), sr)
```

### スペクトルの可視化

```kotlin
val n = 2048
val window = hanning(n)
val frame = DoubleArray(n) { buffer[it] * window[it] }
val spec = Fft.rfft(frame)                          // n/2 + 1 本の複素スペクトル
val freqs = rfftFreq(n, sr)                         // 各ビンの周波数 (Hz)
val db = DoubleArray(spec.size) { i ->
    10.0 * log10(spec.re[i] * spec.re[i] + spec.im[i] * spec.im[i] + 1e-12)
}
// freqs と db を Canvas に描く
```

### 環境音の生成

入力なしで音を作る例です。シード付き乱数なので、同じシードなら Android でも iOS でも同じ音になります。

```kotlin
val sr = 44100
val noise = Rng(7L).gaussianNoise(sr * 10, 0.2)     // 10 秒の白色ノイズ
val bed = sosfilt(SosFilters.LOWPASS_800_HZ, noise) // 低域だけ残して風のような音に
val out = Wav.encodePcm16(peakNormalized(bed, 0.5f), sr)
```

## ターゲット

`jvm`, `android`（minSdk 24）, `iosArm64`, `iosSimulatorArm64`, `iosX64`

## 導入

Maven Central にはまだ出していません。それまではコンポジットビルドで使ってください。

```kotlin
// settings.gradle.kts
includeBuild("../afterfade-dsp")
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.874wokiite:afterfade-dsp:0.1.0")
        }
    }
}
```

## 使用例

WAV を読み、打撃的なイベントを検出し、最初のイベントを C メジャーに寄せて WAV に書き戻します。

```kotlin
import com.afterfade.dsp.detectTransients
import com.afterfade.dsp.estimatePitch
import com.afterfade.dsp.hzToNote
import com.afterfade.dsp.pitchShiftToKey
import com.afterfade.dsp.softSaturate
import com.afterfade.dsp.tapeWarble
import com.afterfade.dsp.io.Wav

fun process(wavBytes: ByteArray): ByteArray {
    val audio = Wav.decode(wavBytes)              // モノラルの FloatArray とサンプルレート
    val sr = audio.sampleRate

    val events = detectTransients(audio.samples, sr)
    for (e in events) {
        val hz = estimatePitch(e.audio, sr)
        val note = hz?.let { hzToNote(it) }
        println("onset ${e.onsetSec}s, ${e.audio.size} samples, pitch $note")
    }

    val first = events.firstOrNull()?.audio ?: audio.samples
    var out = pitchShiftToKey(first, sr, key = "C", scale = "major")
    out = tapeWarble(out, sr)
    out = softSaturate(out, drive = 0.4)
    return Wav.encodePcm16(out, sr)
}
```

## 限界

- **バッチ処理専用。** 配列を受け取って配列を返します。2048 サンプル程度の短いバッファなら数ミリ秒で返るので、
  マイク入力を 10〜20 fps で解析する用途（チューナー、可視化）は現実的です。ライブ音声に低レイテンシで
  エフェクトをかける用途は対象外です
- **プラットフォームの FFT より遅い。** 純 Kotlin の FFT は vDSP より桁で遅いです
- **WAV しか読めない。** MP3 / AAC のデコードはありません。先に OS のデコーダで PCM にしてください
- **一つの製品の都合で形が決まっている。** キーは文字列（`"C"`, `"major"`）で受け、`extractBed` のように
  Afterfade のパイプライン由来の名前が残っています
- **フィルタは決め打ちの係数表。** `SosFilters` は 44.1 kHz 用に設計した数本だけです。
  フィルタ設計関数を最初の追加機能として予定しています
- KDoc は英語と日本語が混ざっています

## Afterfade との関係

Afterfade は、その日の音を端末内だけでローファイトラックに変えるアプリです。このライブラリはその DSP 層、
つまり「機構」の部分です。Afterfade をあの音にしている「レシピ」（コードテンプレート、ドラムパターン、
マスターチェーン）はアプリ側に残しています。

何をここに入れるかの判断基準は一つです。
**引数に別の数値を渡せば別の音になるものは機構で、ライブラリに入れる。数値が決め打ちで名前が付いているものはレシピで、アプリに残す。**
`softSaturate(y, drive)` はここにあり、`lofiMaster` はありません。

## 開発

```sh
./gradlew jvmTest                 # ホストの JVM で速く回す
./gradlew iosSimulatorArm64Test   # 同じテストを iOS シミュレータで
./gradlew assemble                # 全ターゲットをコンパイル
```

## ライセンス

Apache License 2.0。[LICENSE](LICENSE) を参照してください。
