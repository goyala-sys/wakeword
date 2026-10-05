# Handoff: wake word for Find My Phone

State as of 2026-10-05. Branch `claude/epic-newton-ncmg8k`. Read the README for design and numbers;
this file covers where things stand, how to work on it, and what's still open.

## Goal

An always-on wake phrase that rings a lost phone. The first engine (open vocabulary, any typed phrase)
works up close but **fails a few metres away**, which is the actual use case. The work since then is a
QA app that compares three engines side by side on a real phone.

## Where things are

| | |
|---|---|
| Upstream repo | `bhnvgoyal12-coder/wakeword` (owner account `bhnvgoyal12-coder`). Has commits up to `709c5dd` only. |
| Working fork | `goyala-sys/wakeword`. **Latest: `99af966`.** Push here. |
| Local clone remotes | `origin` = upstream, `fork` = goyala-sys |
| `gh` accounts on the dev Mac | `goyala-sys` (active) and `bhnvgoyal12-coder`, both with `workflow` scope |
| QA APK | CI artifact `wakeword-demo-apk` on the fork's Actions page (~106 MB, arm64) |
| Test phone | Samsung Galaxy S24+ (SM-S926B), Android 16 |

No PR to upstream has been opened. The fork is 4 commits ahead of upstream.

## The QA app (`android/demo-app`)

One screen with an **Engine** switch. One engine listens at a time (they all need the mic). Every
detection lands in one log tagged `OV` / `DV` / `LK`. A stats panel shows CPU, memory, battery
temperature/current, mic level and (LiveKit) score.

| Engine | What it is | Phrases | Status on the S24+ |
|---|---|---|---|
| **Open vocabulary** (`OV`) | This repo's engine: sherpa-onnx KWS + Silero VAD + new AGC | Any typed phrase | Close range OK for "hey buddy". "marco polo" detected only 2 of 13 tries, 0 of 7 at 0.5-1 m. Far field poor (sim: 35% at 4 m with AGC). Not re-checked since the three-engine build. |
| **DaVoice** (`DV`) | Commercial SDK, one trained model per fixed phrase | Demo models only: "hey lookdeep", "need help now", "coca cola" | Works in the combined app. Licence accepted, detections fire. 12 detections across the 3 demo phrases in one session. Hit rate and distance not measured (log only shows hits). |
| **LiveKit** (`LK`) | livekit-wakeword pipeline ported to Kotlin | "hey livekit" only (pre-trained) | 3 of 3 close-range hits at score 0.60-0.76. ~34 ms/pass, ~107% of one core because it's ungated (no VAD), so not battery-viable as is. Distance and false alarms not measured. |

## Things that will bite you

- **Pushing to the fork doesn't start CI.** Trigger it by hand:
  `gh workflow run build --repo goyala-sys/wakeword --ref claude/epic-newton-ncmg8k`.
  `99af966` (the DaVoice leak fix and LiveKit logging) has **not been built by CI yet**. The last
  built commit is `c4f7557`.
- **There's no Android SDK on the dev Mac,** and Gradle can't download its distribution through the
  office network. CI is the only Android build. Python (via the internal PyPI mirror) and `kotlinc`
  work locally for the pure-Kotlin core.
- **Two ONNX Runtimes in one APK.** sherpa-onnx ships ORT 1.28 and DaVoice ships ORT 1.24, both as
  `libonnxruntime.so` with versioned symbols (`OrtGetApiBase@VERS_x`), so neither can stand in for the
  other. `fetch_models.sh` renames sherpa's copy to `libonnxruntime_sherpa.so` with `patchelf`. DaVoice's
  AAR is untouched. LiveKit runs on DaVoice's ORT through its Java bindings. Don't drop either copy.
- **DaVoice licence.**
  - **Expiry:** the current evaluation key expires on **2026-10-31**. The base64 before the `-` is the
    expiry time in ms.
  - **Where it lives:** it's pasted in the app on the test phone. It is **not** in the repo or CI
    secrets. Ask the project owner for it; don't commit it (the fork is public).
  - **Baking it in:** set the fork secret `DAVOICE_LICENSE` and CI builds it into the APK. Anyone can
    extract it from there.
  - **Custom phrases:** these need a `.dm` model from DaVoice (info@davoice.io). Put it in
    `android/davoice-models/`, which is gitignored.
- **Signing.** CI signs with the committed `android/debug.keystore`, so new builds install over old ones.
  Phones that still have a build from before `1de4ea0` need one uninstall.
- **The phone's USB connection drops often.** Logs aren't lost: `adb logcat -d -s DaVoiceDemo
  WakeWordService LiveKitDemo` replays them from the phone. The screen also locks quickly; it must
  be unlocked for `adb shell input tap`.
- **Pre-filling settings over adb** works because debug builds allow `run-as`. Settings live in
  `shared_prefs/demo.xml`. Keys: `engine`, `davoice_licence`, `davoice_model`, `livekit_model`,
  `keyword`.
- **DaVoice logs `NNAPI EP not available`** warnings. They're harmless: it falls back to the CPU.

## Build, install, test

```bash
cd android
./run_on_phone.sh                      # downloads the latest successful CI APK for this branch, installs, streams logs
./run_on_phone.sh path/to/demo-app-debug.apk
```
`run_on_phone.sh` streams only the `WakeWordService` and `DaVoiceDemo` tags; add `LiveKitDemo:V` to
its `logcat` line, or run that separately. It also looks up CI runs on `origin` (upstream). To use a fork build, download it first:
`gh run download <run-id> --repo goyala-sys/wakeword -n wakeword-demo-apk`.

Python engine and simulations (`python/`): `python -m pytest`, `python -m eval.distance ../data/<set>`
(simulated distance, AGC on/off). See README "Reproducing".

## Open items, roughly in priority order

1. **Build `99af966` in CI** and check the DaVoice memory fix on the phone (idle memory should level
   off at ~222 MB).
2. **Re-check the Open vocabulary engine in the three-engine build.** It hasn't been run since the ORT
   rename.
3. **Run a proper distance test of all three engines:** 10 tries each at 1 / 2 / 4 m and the next
   room, plus about 10 min of normal talk for false alarms. Write down tries as well as hits.
4. **Get a "marco polo" (or the final phrase) model from DaVoice** and/or train a LiveKit classifier
   for it, then compare all three on the same phrase.
5. **Pick an engine.** If LiveKit wins on accuracy, it needs a VAD gate before it's battery-viable.
6. **Open a PR from the fork to upstream** once the owner wants it.

## Commit history on this branch (newest first)

- `99af966` Release the DaVoice detector on stop; log LiveKit score for distance tests
- `c4f7557` Add LiveKit engine and live stats panel to the QA app
- `709c5dd` Keep both ONNX Runtimes: rename sherpa's instead of dropping DaVoice's
- `1de4ea0` Put both engines in one QA app with an engine switch
- `2381a6c` Add DaVoice comparison app (later merged into the demo app)
- `81d078a` Add AGC for distant speech and per-utterance mic-level logging
- `8e7b812` and earlier: original engine, demo app, CI, `run_on_phone.sh`
