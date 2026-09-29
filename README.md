# Wake word engine for Find My Phone

An always-on listener for a **user-typed** wake phrase ("hey buddy", "where's my phone"),
with no per-keyword training. Built on the [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
open-vocabulary keyword spotter (3.3M-param streaming zipformer, int8, ~4 MB), with a
Silero VAD gate in front so the neural spotter only runs while someone is speaking.

```
mic 16 kHz ─► pre-roll (0.5 s) ─► Silero VAD ─► sherpa KWS stream ─► cooldown ─► action (ring)
                                   │
                                   └─ 1 s of no speech: pad 0.8 s, flush, drop stream → spotter idle
```

| Directory | What it is | Verified here |
|---|---|---|
| `python/` | Reference engine, CLI (mic or WAV), evaluation pipeline | 19 pytest tests; full evaluation below |
| `android/wakeword-core` | Pure-Kotlin port: tokenizer, gating state machine, cooldown, resampler | 16 JUnit tests on the JVM |
| `android/wakeword-android` | sherpa adapters, microphone foreground service, `WakeWord` facade, ring action | Type-checked here against `android.jar` (API 34) and the sherpa-onnx 1.13.8 AAR. Built by CI into the demo APK. **Not yet run on a phone by me.** |
| `shared/` | Tokenizer vocabulary + golden file both tokenizers are tested against | — |

| `android/demo-app` | One-screen test app: type a phrase, start, see mic level, speech indicator and detections | APK built by CI; on-device tests run on an Android 14 emulator in CI |

To integrate into the launcher, see **[android/INTEGRATION.md](android/INTEGRATION.md)**.

## Run it on your phone

**Fastest: phone plugged in with USB debugging on** (macOS / Linux / WSL):
```bash
git clone -b claude/epic-newton-ncmg8k https://github.com/bhnvgoyal12-coder/wakeword && cd wakeword/android
./run_on_phone.sh
```
The script checks the phone is connected and authorized, downloads the latest CI-built APK
(needs the [GitHub CLI](https://cli.github.com) after `gh auth login`, or builds locally if you
have the Android SDK), installs it with mic and notification permissions pre-granted, and
launches it. It then streams the app's log, so every detection and error shows in your terminal.
You can also pass an APK you downloaded yourself: `./run_on_phone.sh ~/Downloads/demo-app-debug.apk`.
It needs only `adb` (`brew install --cask android-platform-tools` / `sudo apt install adb`).

**Windows (no bash):** download `wakeword-demo-apk` from the repo's **Actions** tab (latest
**build** run → Artifacts), unzip it, then:
```
adb install -r -g demo-app-debug.apk
adb shell am start -n com.findmyphone.wakeword.demo/.MainActivity
adb logcat -s WakeWordService
```

**Android Studio:** run `android/fetch_models.sh` once, open `android/`, choose the `demo-app`
configuration and press Run.

**No computer:** open the Actions page on the phone, download the artifact, unzip it, tap
the APK, and allow "Install unknown apps".

**Why not an emulator?** An emulator can't meaningfully test "does it hear *me*". Its mic is your
laptop's mic through a virtual device. CI does use an emulator for something narrower: on every
push it runs `EngineOnDeviceTest` on an Android 14 emulator. That test checks the native
libraries and models load, recorded "hey buddy" clips are detected, other speech isn't, and the
listening service starts.

**What the screen shows**

| Element | Meaning |
|---|---|
| Microphone level bar | The mic is delivering audio. If it doesn't move when you talk, it's a mic or permission problem, not the model. |
| ● speech / ○ quiet | The VAD gate. ● means the neural spotter is running. If this lights up but nothing is detected, the model heard speech and didn't match the phrase. |
| Green flash + "DETECTED" | A detection, with the time. Also posted as a notification, so you see it with the screen locked. |
| Recent list | The last 50 detections, including ones while the app was closed. |
| Normal / Sensitive | boost 1.0 vs 2.0. Sensitive catches more in noise and false-alarms about 3× more. |
| Ring on detection | Plays the alarm tone at full volume. Tap the panel to stop it. |

**A 15-minute test script**
1. Quiet room, 1 m away: say the phrase 10 times with pauses. Count detections.
2. Same from 3 m, then from the next room with the door open.
3. TV or music on at normal volume: 10 more tries.
4. Talk normally for 5 minutes *without* the phrase (read something aloud, have a phone call nearby) and count false detections.
5. Lock the screen, wait 10 minutes, say the phrase. A notification should appear (and a ring, if enabled).

`adb logcat -s WakeWordService` shows each detection, one line per utterance with its mic level
(`utterance 1.9s: peak -44 dBFS, gain +22 dB, no detection`), and, on stop, the spotter duty cycle.
Say the phrase at 1, 2 and 4 m and compare the peaks to see how quietly your phone hears you.

## DaVoice engine (in the same demo app)

The demo app has an **Engine** switch: *Open vocabulary* (this repo's engine) or *DaVoice*
([davoice.io](https://davoice.io), SDK from
[frymanofer/Android_Native_Wake_Word](https://github.com/frymanofer/Android_Native_Wake_Word), pinned
and checksum-verified by `fetch_davoice.sh`). DaVoice uses **one trained model per fixed phrase**, the
approach suggested above for far-field use, so QA can compare both engines on one phone. Only one
engine listens at a time, since both need the mic. The detection log tags each hit `OV` or `DV`.

- **Licence:** DaVoice won't listen without a key from DaVoice (info@davoice.io). Paste it into the
  app, or set the `DAVOICE_LICENSE` repo secret so CI bakes in a default. A baked-in key can be
  extracted from the APK, so use an evaluation key. Keys encode their expiry: the base64 part
  before `-` is a Unix time in ms.
- **Phrases:** the build bundles DaVoice's demo models ("hey lookdeep", "need help now", "coca cola").
  For your own phrase, get its `.dm` file from DaVoice, drop it in `android/davoice-models/`
  (gitignored: DaVoice's licensed files), and rebuild.
- **Two ONNX Runtimes:** both SDKs bundle ONNX Runtime as `libonnxruntime.so` (sherpa-onnx 1.28,
  DaVoice 1.24). They can't share one: each exports versioned symbols (`OrtGetApiBase@VERS_1.28.2`),
  and DaVoice failed to load against sherpa's copy with `cannot locate symbol "OrtGetApiBase"`. So
  `fetch_models.sh` renames sherpa's copy to `libonnxruntime_sherpa.so` with `patchelf` (brew / apt)
  and re-points sherpa's own libraries at it. DaVoice's AAR is used exactly as published. The
  version tags keep the two copies apart in one process.
- **Signing:** CI signs with the committed `android/debug.keystore`, so new builds install over old
  ones.
- DaVoice ships arm64 native code only, so on 32-bit phones the DaVoice engine shows a load error.
  The open-vocabulary engine still works there.

## Quick start (desktop)

```bash
cd python
pip install -r requirements.txt sounddevice      # models (~20 MB) download on first run
python cli.py --keyword "hey buddy" --mic --ring
python cli.py --keyword "hey buddy" --keyword "find my phone" --wav some_recording.wav
pytest tests
```

## Measured results

Keyword "hey buddy", default settings (beam 4, threshold 0.25, boost 1.0, Silero gate).
Dataset: 100 keyword utterances from random voices of a 904-speaker TTS at 0.85–1.2× speed,
some wrapped in carrier phrases ("um, hey buddy, where are you"). Negatives: 30 min of
everyday speech, 25% of it near-misses ("hey body", "hey bobby", "buddy", "hey daddy",
"heavy duty"…). Noise is pink/brown/white/babble, rotating every 20 s.

| Noise | Recall | False alarms / hour of talk | Latency after word ends (median / p90) |
|---|---|---|---|
| clean | **92%** | 0 | 0.46 s / 0.56 s |
| 10 dB SNR (TV in the room) | **79%** | 2 (1 event: "Why is the printer not working again?") | 0.43 s / 0.59 s |
| 0 dB SNR (noise as loud as the voice) | **43%** | 2 (1 event: "hey body") | 0.46 s / 0.60 s |

CPU, single thread on this x86 container (a phone core is maybe 2–4× slower; **measure on a device**):

| Scenario | Spotter running | CPU of one core |
|---|---|---|
| Quiet room, ~10% of the time someone talks | 23% of the time | **0.9%** |
| Busy room, ~30% talk, louder ambient noise | 54% | 1.4% |
| No gate (spotter always on) | 100% | 2.0% |

### Distance

The results above use speech at close-talk level (about -17 dBFS). A phone across the room hears
you 20-30 dB quieter *and* with more room echo. That combination is where this model fails:
in real use on a Galaxy S24+ it worked up close and stopped working a few metres away.
`eval/distance.py` simulates it (0.5 s RT60 living room, quiet-room noise floor):

| Distance (simulated) | Recall, no gain | Recall, with AGC (default) |
|---|---|---|
| 0.3 m | 93% | 92% |
| 1 m | 83% | 78% |
| 2 m | 52% | 63% |
| 4 m | 20% | 35% |
| next room | 8% | 18% |

60 positives (±5 points per cell); false alarms 0-1 in 3 min of speech in every cell, with or without AGC.

- **The VAD gate is not the problem.** It opens on 100% of keywords at every distance. The spotter
  runs but doesn't match.
- **Quiet alone is mostly fine** (88% at the 4 m level with no echo). **Echo alone costs 15-35
  points.** Together they collapse, because the model was trained on close-mic podcast speech.
- **The AGC** (`core/Agc.kt`, `wakeword/agc.py`) is a digital gain stage in front of the VAD. It
  scales the loudest recent 100 ms to -22 dBFS, up to +30 dB, and never attenuates. It roughly
  doubles far-field recall and doesn't wake the spotter in a quiet room (duty cycle stays at 0%).
  Pass `agc = null` to `WakeWordEngine.create` to turn it off.
- **Gain can't remove echo.** For reliable across-the-room detection, the next step is a fixed-phrase
  model trained with reverb augmentation (see "Why not Vosk / Porcupine / openWakeWord?").
- The levels in the table are estimates. The Android log prints one line per utterance with its
  real peak level before gain, e.g. `utterance 1.9s: peak -44 dBFS, gain +22 dB, no detection`.
  Use those lines to calibrate `CONDITIONS` in `eval/distance.py` for a given phone.

### What the tuning experiments showed

- **The VAD gate improves accuracy as well as saving battery.** With no gate the spotter runs
  one endless stream and recall drops from 92% to 80% on clean audio. Starting a fresh stream
  per utterance helps it. An energy gate is cheaper (0.45% CPU) but lost 9 points of recall at 0 dB.
- **Beam width (`max_active_paths`) is the real trade-off knob.** Beam 8 gets 95/87/62% recall,
  but false alarms go from ~0–2/h to **12–16/h**, almost all on near-misses ("hey body",
  "buddy", "hey birdie"). A false alarm here rings the phone, so the default stays at 4.
- **Boost 2.0** is a milder version of the same trade: 94/80/53% recall at 4–6 false alarms/h.
  It's a reasonable "sensitive" user setting. Boost ≥3 hurt recall.
- **Threshold barely matters** at beam 4: 0.15 to 0.45 changes recall by about 5 points and doesn't change false alarms.
- int8 models are as accurate as fp32. Ship int8.

### How far to trust these numbers

- **They're optimistic.** TTS voices are cleaner and more uniform than people. Noise was
  generated, not recorded. The near-miss rate is deliberately exaggerated, so real-life false
  alarms/hour on beam 8 would be lower. Missed detections in real rooms will be higher.
- 100 positives means about ±3–5 points of noise. "0 false alarms in 30 min" is not "0 per day".
- **0 dB SNR is where this model breaks down (43%).** Phone in a pocket, music playing, or
  shouting from another room will often fail. If that matters, the next step is a
  **trained model for one fixed phrase** (openWakeWord or Porcupine). That typically gets
  much better noise robustness, but users can't choose their own word.
- To get real numbers: record 20–30 people saying the phrase on the actual phones, plus
  a few hours of household audio, and run `eval/evaluate.py` against those WAVs.

## Reproducing

```bash
cd python
python -m eval.make_dataset --keyword "hey buddy" --out ../data/hey_buddy \
    --confusable "hey body" --confusable "hey bobby" --confusable "hey daddy" \
    --confusable "a buddy" --confusable "hey birdie" --confusable "heavy duty"   # ~1 min
python -m eval.evaluate ../data/hey_buddy --show-fa        # ~3 min on 4 cores
python -m eval.evaluate ../data/hey_buddy --sweep          # boost x threshold grid
python -m eval.evaluate ../data/hey_buddy --paths 8        # beam comparison
python -m eval.evaluate ../data/hey_buddy --gates          # silero / energy / none
python -m eval.distance ../data/hey_buddy                  # simulated distance, AGC off vs on
python -m eval.idle_cost --minutes 10                      # quiet-room CPU
```

TTS output is random on every call, so a regenerated dataset gives slightly different numbers.
The tests use committed WAV fixtures for that reason.

```bash
cd android/wakeword-core && gradle test                    # JVM, no Android SDK needed
cd android/verify && ./prepare.sh && gradle compileKotlin  # type-check the Android module
cd android && ./fetch_models.sh && ./fetch_davoice.sh && ./gradlew :demo-app:assembleDebug   # Android SDK + patchelf
```

## Design notes

- **Tokenizer.** sherpa takes keywords as the model's sentencepiece pieces (`▁HE Y ▁BU D D Y`).
  Both Python and Kotlin use a ~60-line Viterbi unigram encoder over `shared/kws_vocab.tsv`, so
  the phone doesn't need sentencepiece. It matches the reference library on 3,000+ random
  and real phrases. Only letters, apostrophes and spaces are allowed, so numbers must be spelled out.
- **Short phrases are refused softly.** `validate()` warns for one-word or <4-token phrases.
  Those are false-alarm magnets.
- **Pre-roll (0.5 s)** replays audio from before the VAD fired, so the first syllable reaches the spotter.
- **Tail pad (0.8 s)**: the model emits a keyword about 0.5 s after it ends. When the stream is
  closed, silence is fed so a keyword spoken just before a pause still fires.
- **Cooldown (2 s)** blocks double-fires from one utterance.

## Why not Vosk / Porcupine / openWakeWord?

- **Vosk** (grammar-restricted ASR) also takes arbitrary words, but it's a ~40 MB general
  recognizer running continuously. The sherpa KWS model is built for this task and about 10× smaller.
- **Porcupine** is the most polished, but custom words need their console, it's per-device
  licensed, and it's closed.
- **openWakeWord / microWakeWord** give better accuracy for a *fixed* phrase, but each phrase
  needs a training run. That's the right upgrade if you ship one default phrase.

## Next steps (not done)

1. Run the demo APK on 2–3 real phones and measure CPU and battery (the numbers above are x86).
2. Record real voices for a truthful recall and false-alarm number, then pick the default beam/boost.
3. Put an energy pre-gate in front of Silero. Silero is about half of idle CPU, and a cheap RMS check
   can skip it in silence.
4. Optional: "enroll" mode. Record the user saying their phrase three times and auto-tune
   boost/threshold against those recordings.
