#!/usr/bin/env bash
# Download the livekit-wakeword models (Apache-2.0) for :demo-app's LiveKit engine:
# the two frozen front-end models (mel + speech embedding) and the "hey livekit" example classifier.
# Pinned to one commit of github.com/livekit/livekit-wakeword and checksum-verified.
# Your own phrase: train a classifier with `livekit-wakeword`, put the .onnx in livekit-models/,
# and it is copied into the APK and shows up in the LiveKit phrase list.
set -euo pipefail
cd "$(dirname "$0")"
REV=95448a7559c453fcd87645bd67b247ffb45f85b0
RAW=https://raw.githubusercontent.com/livekit/livekit-wakeword/$REV
A=demo-app/src/main/assets/livekit
mkdir -p "$A"

fetch() { # url dest sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c --status; then
    curl -fL --retry 3 -o "$2" "$1"
  fi
  echo "$3  $2" | shasum -a 256 -c --status || { echo "checksum mismatch: $2" >&2; rm -f "$2"; exit 1; }
}

fetch "$RAW/src/livekit/wakeword/resources/melspectrogram.onnx" "$A/melspectrogram.onnx" \
  ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f
fetch "$RAW/src/livekit/wakeword/resources/embedding_model.onnx" "$A/embedding_model.onnx" \
  70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f
fetch "$RAW/examples/resources/hey_livekit.onnx" "$A/hey_livekit.onnx" \
  8bd634fb7acf1e52d06307fb8f460abf2c7a40e561fb4532fc56e087e0246f62

shopt -s nullglob
for m in livekit-models/*.onnx; do cp "$m" "$A/"; echo "custom model: $(basename "$m")"; done
du -sh "$A"
