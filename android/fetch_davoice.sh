#!/usr/bin/env bash
# Download the DaVoice wake-word SDK (AAR) and its demo models for :demo-app's DaVoice engine.
# Pinned to one commit of github.com/frymanofer/Android_Native_Wake_Word and checksum-verified.
# Custom models from DaVoice (*.dm) go in davoice-models/; they're copied into the APK too.
# The AAR is used as published; fetch_models.sh renames sherpa-onnx's ONNX Runtime so both fit.
set -euo pipefail
cd "$(dirname "$0")"
REV=3691ef302767dc4054ffcff075fca816083c8345
RAW=https://raw.githubusercontent.com/frymanofer/Android_Native_Wake_Word/$REV/android
MVN=local-maven/com/davoice/keyworddetection/1.0.0
A=demo-app/src/main/assets
mkdir -p "$MVN" "$A"

fetch() { # url dest sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c --status; then
    curl -fL --retry 3 -o "$2" "$1"
  fi
  echo "$3  $2" | shasum -a 256 -c --status || { echo "checksum mismatch: $2" >&2; rm -f "$2"; exit 1; }
}

fetch "$RAW/libs/com/davoice/keyworddetection/1.0.0/keyworddetection-1.0.0.aar" "$MVN/keyworddetection-1.0.0.aar" \
  ac97d17ffe4ac5b175c7c097ff28bcd0bdb28a6e9241e835518747005dda8dc9
cat > "$MVN/keyworddetection-1.0.0.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.davoice</groupId>
  <artifactId>keyworddetection</artifactId>
  <version>1.0.0</version>
  <packaging>aar</packaging>
</project>
POM

# layer1.dm is shared by every wake-word model; the others are DaVoice's demo phrases.
fetch "$RAW/src/main/assets/layer1.dm" "$A/layer1.dm" 3312af11f1c783bac9793d82dbdeaae26a116b92f2ffa89682a2ee9eaf26c95a
fetch "$RAW/src/main/assets/hey_lookdeep.dm" "$A/hey_lookdeep.dm" b018321f937e1ecdc81876aecf58fede02e28e40034ed74b0961b4f41e4d2888
fetch "$RAW/src/main/assets/need_help_now.dm" "$A/need_help_now.dm" 5fae6125bd95e45368a236a91bea5b0525110c2fa7fe011b7893ae636ded65f5
fetch "$RAW/src/main/assets/coca_cola_model_28_05052025.dm" "$A/coca_cola_model_28_05052025.dm" \
  6ff74be31fc8027e4af986d9bc21eb4e327fe10aaaab2ae6bf40cd9b4100d619

shopt -s nullglob
for m in davoice-models/*.dm; do cp "$m" "$A/"; echo "custom model: $(basename "$m")"; done
du -sh "$MVN" "$A"
