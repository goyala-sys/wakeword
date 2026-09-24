# Integrating the wake word into the Find My Phone launcher

## 0. One-time setup

```bash
cd android && ./fetch_models.sh      # ~5 MB of int8 models + silero VAD + the sherpa-onnx AAR
```

In your app's `settings.gradle.kts`:

```kotlin
includeBuild("path/to/wakeword/android/wakeword-core")
include(":wakeword-android")
project(":wakeword-android").projectDir = file("path/to/wakeword/android/wakeword-android")
```

Then add `implementation(project(":wakeword-android"))` in the app module. The library
manifest merges `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`
and the service declaration. The APK grows by about 5 MB of models plus the sherpa/onnxruntime
`.so` files (~30 MB per ABI uncompressed). Ship an App Bundle so each device downloads only its own ABI.

## 1. Pick an integration mode

Your app **already has a mic loop** for clap/whistle. Android does not reliably let one
app run two `AudioRecord`s at once: on many devices the second one gets silence, and
since Android 10 concurrent capture is policy-driven. Use **one** capture.

### Mode A: feed your existing loop into the engine (recommended)

```kotlin
// On your audio thread, once:
val engine = WakeWordEngine.create(context, listOf(KeywordSpec("hey buddy")), inputSampleRate = 44100)

// Inside your existing read loop, next to the clap detector:
val n = record.read(buf, 0, buf.size)
clapDetector.process(buf, n)
for (d in engine.accept(buf, n)) mainHandler.post { ringer.ring() }

// When the loop ends:
engine.release()
```

`inputSampleRate` handles 44.1/48 kHz input with an internal resampler. The model needs
16 kHz, so recording at 16 kHz directly is cheaper if your clap detector can live with it.
**Use `MediaRecorder.AudioSource.VOICE_RECOGNITION`** if you can. `MIC` often applies
AGC and noise suppression, which hurt both detectors. The engine applies its own gain
internally (only to its copy of the audio), so distant, quiet speech still reaches the model.
Your `buf` is not modified. Set `engine.utteranceListener` to log each utterance's mic level;
see README "Distance".

### Mode B: let `WakeWordService` own the mic

```kotlin
// Settings screen:
val v = WakeWord.validate(ctx, KeywordSpec(userText))
if (!v.ok) return showError(v.error)          // digits, symbols, empty
v.warning?.let { showWarning(it) }            // too short: likely false rings
WakeWord.start(ctx, listOf(KeywordSpec(userText)))

// Home activity (the launcher):
override fun onResume() { super.onResume(); WakeWord.ensureRunning(this) }

// Anywhere in-process:
WakeWord.addListener { d -> ringer.ring() }
// Your clap detector can consume the same frames (16 kHz PCM16, audio thread):
WakeWordService.frameListeners += WakeWordService.FrameListener { pcm, n -> clapDetector.process(pcm, n) }
```

## 2. Why the launcher is the right place for this

- **Android 14+ refuses to start a microphone foreground service from the background.**
  Neither `START_STICKY` restarts nor `BOOT_COMPLETED` receivers can start it. A launcher is
  in the foreground every time the user presses Home, so `ensureRunning()` in `onResume`
  revives the service after process death, an update or a reboot, the first time the home
  screen is shown. That's why the service returns `START_NOT_STICKY`: a system restart
  would be refused anyway.
- The persistent notification is mandatory for a microphone FGS. Its channel is
  `IMPORTANCE_LOW`, so it doesn't make noise.
- You mentioned the app already has Play approval for foreground microphone use. The
  Play declaration covers the *purpose* ("find my phone by sound"). If the declared use
  case only lists clap/whistle, update it to mention voice keywords.

## 3. Things that will bite on real devices

| Issue | What happens | Mitigation |
|---|---|---|
| Phone calls, voice recorders, and other apps using the mic | Android silences our capture (reads return zeros) | Nothing to do. The VAD sees silence and the spotter idles. Detection resumes automatically. |
| OEM battery killers (Xiaomi, Huawei, Oppo, Samsung "deep sleep") | FGS killed despite the notification | Being the default launcher helps a lot. Also ask users to exempt the app from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is allowed for this use case). |
| Phone in pocket or bag | Muffled audio, lower recall | Expected. The clap/whistle path has the same issue. |
| Ringing re-triggers | The mic hears the ring | The ring isn't speech, so the VAD mostly stays closed. `EngineConfig.cooldownS` (2 s) blocks double-fires. |
| Keywords with digits or symbols | `KeywordException` | Use `WakeWord.validate()` in the settings UI and ask users to spell numbers out. |
| Very short keywords ("buddy", "phone") | Many false rings | `validate()` returns a `warning`. Nudge users toward 2–3 words. |

## 4. Tuning per keyword

`KeywordSpec(text, boost, threshold)`:
- `threshold` (default 0.25): raising it to 0.35–0.45 costs recall but barely changes false alarms
  in our tests. It's a weak knob for this model.
- `boost` (default 1.0): 2.0 catches more keywords in noise (43→53% at 0 dB SNR) but triples
  false alarms (≈2→6 per hour of talk). Values above 2 make recall *worse*.
- `maxActivePaths` (engine, default 4): 8 catches more keywords in noise but false-alarms
  far more. See the README table before changing it.

Use `python/eval` with your chosen phrase to see the trade-off before shipping a default.
