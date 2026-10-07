# KhmerAmountSpeech for Android

Offline Khmer and English speech for money amounts on Android. A Kotlin port of the iOS
[KhmerAmountSpeech](https://github.com/many-pichr/ios-amount-tts) library, with the same reading rules,
catalog and recordings.

```kotlin
val speaker = KhmerAmountSpeaker(context)
speaker.speak("1,250,000", CurrencyCode.KHR)
// 🔊 មួយលានពីរសែនប្រាំម៉ឺនរៀល
```

- **Offline and private.** Ships 145 short clips (1.5 MB) and joins them on the device. No TTS
  service is called, and no amount leaves the phone.
- **Natural round amounts.** 100 … 900,000 and 1,000,000 are pre-recorded as single phrases
  (មួយពាន់, ពីរម៉ឺន, "two hundred thousand"). If one of those clips is missing, the player speaks
  its separate words instead.
- **Khmer and English.** Khmer uses the formal reading (…ប្រាំម៉ឺន). English uses the short scale.
- **Voice types:** amount only, confirm pay and received.
- **WAV export.** Render any amount to a WAV `ByteArray` or `.wav` file without playing it.
- **Sample-accurate timing.** Each clip is trimmed, faded and joined into one buffer with an adjustable
  gap or crossfade, then played with `AudioTrack`.
- KHR and USD (with cents), from 0 to 999,999,999,999.

Requires Android 6.0 (API 23) or later. Kotlin coroutines are the only dependency.

## Installation

### JitPack

Push this project to GitHub and create a tag (for example `1.0.0`). Then, in the app project:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.many-pichr:android-amount-tts:1.0.0")
}
```

JitPack builds the library with `jitpack.yml`. Its build page for the tag shows the exact coordinates.

### Maven repository (Maven Central, GitHub Packages, Nexus, …)

The `khmer-amount-speech` module publishes `io.github.many-pichr:khmer-amount-speech:<VERSION_NAME>`
(group, artifact and version are set in `gradle.properties`) with sources, POM and Gradle module metadata.

```sh
./gradlew :khmer-amount-speech:publishToMavenLocal                               # ~/.m2
./gradlew :khmer-amount-speech:publishReleasePublicationToProjectRepository     # build/repo
```

```kotlin
dependencies {
    implementation("io.github.many-pichr:khmer-amount-speech:1.0.0")
}
```

To publish to Maven Central, add signing and your Central Portal credentials. A plugin such as
`com.vanniktech.maven.publish` can do both.

## Usage

### Speak an amount

```kotlin
class PaymentAnnouncer(context: Context, private val scope: CoroutineScope) {
    private val speaker = KhmerAmountSpeaker(context)

    fun announce() = scope.launch {
        try {
            speaker.speak("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
            // ទទួលប្រាក់ចំនួនប្រាំម៉ឺនរៀល
            speaker.speak("25.50", CurrencyCode.USD, SpeechLanguage.ENGLISH, VoiceType.CONFIRM_PAY)
            // The amount is twenty-five dollars fifty cents, please verify
        } catch (e: AmountException) {
            Log.w("Pay", e.message.orEmpty())     // invalid input
        } catch (e: MissingAudioException) {
            Log.e("Pay", e.message.orEmpty())     // missing audio
        }
    }
}
```

`speak` suspends until playback finishes and returns `PlaybackStatus.COMPLETED` or `STOPPED`.
Starting a new utterance stops the one that's playing, so utterances never overlap. Cancelling the
calling coroutine stops playback.

| Voice type    | Khmer                                  | English                                 |
| ------------- | -------------------------------------- | --------------------------------------- |
| `AMOUNT`      | {{amount}}                             | {{amount}}                              |
| `CONFIRM_PAY` | ទឹកប្រាក់ចំនួន{{amount}} សូមផ្ទៀងផ្ទាត់   | The amount is {{amount}}, please verify |
| `RECEIVED`    | ទទួលប្រាក់ចំនួន{{amount}}                | Received {{amount}}                     |

### Export as WAV (no playback)

`AmountAudioExporter` produces the same audio that `KhmerAmountSpeaker` plays: mono, 44.1 kHz, with
the same trimming, gaps and fallback for missing compound clips. You can call it from any thread.

```kotlin
val exporter = AmountAudioExporter(context)

// Text to speech → WAV file (written to <cacheDir>/KhmerAmountSpeech/<UUID>.wav)
val file: File = exporter.speechWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)

// Choose the folder and file name (".wav" is added if missing; an existing file is replaced)
val saved = exporter.speechWav("25.50", CurrencyCode.USD, SpeechLanguage.ENGLISH,
                               directory = context.filesDir, fileName = "order-42")

// In memory
val wav: ByteArray = exporter.wavData("1,250,000", CurrencyCode.KHR)

// To a specific file
exporter.writeWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED, file = File(dir, "received.wav"))

// Options
val custom = AmountAudioExporter(
    context,
    render = RenderOptions(gapMs = 20.0),        // the same timing options as playback
    sampleFormat = WavSampleFormat.FLOAT32,      // PCM16 (default, most compatible) or FLOAT32
)

// Raw samples, with each token's sample range
val sequence = exporter.renderSequence(AmountSpeech("25.50", CurrencyCode.USD).tokens)
sequence.samples; sequence.sampleRate; sequence.segments
```

### Text only (no audio)

```kotlin
val speech = AmountSpeech("1,250,000", CurrencyCode.KHR)
speech.text                  // "មួយលានពីរសែនប្រាំម៉ឺនរៀល"
speech.tokens.map { it.id }  // [km:compound:1000000, km:compound:200000, km:compound:50000, km:currency:riel]
speech.amount.formatted      // "1,250,000"
toKhmerDigits("1,250,000")   // "១,២៥០,០០០"

NumberSpeech.speak(125_000, SpeechLanguage.ENGLISH).text   // "one hundred twenty-five thousand"
```

### Controls and progress

```kotlin
speaker.pause()
speaker.resume()
speaker.stop()

speaker.state.collect { state -> /* IDLE, LOADING, PLAYING, PAUSED */ }
speaker.tokenIndex.collect { index -> /* highlight speech.tokens[index] */ }

speaker.preload()     // decode common clips at launch, so the first speak starts instantly
```

### Configuration

```kotlin
val speaker = KhmerAmountSpeaker(
    context,
    KhmerAmountSpeaker.Configuration(
        render = RenderOptions(gapMs = 20.0, fadeMs = 8.0),   // negative gapMs crossfades
        volume = 1f,
        missingAssetPolicy = MissingAssetPolicy.FAIL,         // refuse to speak a partial amount (default)
        managesAudioFocus = true,                             // transient focus, ducks other audio
    ),
)
```

#### Using your own recordings

The bundled clips are TTS-generated: Khmer uses `km-KH-SreymomNeural` and English uses
`en-US-JennyNeural`. They're fine for prototyping. For production, record the same phrases with a
professional voice artist and put them in your app's assets using the same layout:

```
src/main/assets/recordings/audio/km/numbers/1.mp3
src/main/assets/recordings/audio/km/compounds/1000.mp3
src/main/assets/recordings/audio/km/phrases/received-prefix.mp3
…
```

```kotlin
val config = KhmerAmountSpeaker.Configuration(
    resolver = AudioResources.assetFolder(context, "recordings"),   // falls back to bundled clips
)
// Or files on disk (e.g. downloaded): AudioResources.directory(context, File(filesDir, "recordings"))
```

`AudioCatalog.assets` lists every phrase, with its text and romanization, to use as a script for the
recording session.

## How it works

```
"1,250,000" ─ ParsedAmount ─ NumberSpeech ─ AmountSpeech (voice type phrases)
                                   │
     [km:compound:1000000, km:compound:200000, km:compound:50000, km:currency:riel]
                                   │
   ClipLoader (MediaCodec decode + cache, compound → parts fallback) ─ SequenceRenderer (trim, fade, gap)
                                   │
          AudioTrack (KhmerAmountSpeaker)  or  WAV ByteArray / file (AmountAudioExporter)
```

The catalog and reading rules match the iOS library and the web app, so the same recordings work
on all three.

## Sample app

`sample/` is a small demo: type an amount, pick currency, language and voice type, then speak it
or export a WAV. Run it with `./gradlew :sample:installDebug`.

## Development

```sh
./gradlew :khmer-amount-speech:testDebugUnitTest              # JVM tests: parsing, reading rules, renderer, WAV
./gradlew :khmer-amount-speech:connectedDebugAndroidTest      # device tests: MP3 decoding, playback, export
```
