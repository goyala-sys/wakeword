#!/usr/bin/env bash
# Download the VoxRT wake-word SDK and its "Hey Assistant" model for :voxrt-demo.
#   SDK:   github.com/VoxRT/voxrt-wake-word-android (Kotlin wrapper Apache-2.0, runtime .so proprietary)
#   Model: github.com/VoxRT/voxrt-wake-word-models release v0.1.0 (proprietary)
# Both licences allow bundling the *unmodified* files into an app but not redistributing them on
# their own, so they're fetched at build time into voxrt-demo/vendor/ (gitignored), pinned and
# checksum-verified, and never committed.
set -euo pipefail
cd "$(dirname "$0")"
REV=65cd8564cce4c350c9412b6948f43378e2595b3b
RAW=https://raw.githubusercontent.com/VoxRT/voxrt-wake-word-android/$REV/voxrt-wake-word/src/main
MODEL=https://github.com/VoxRT/voxrt-wake-word-models/releases/download/v0.1.0/voxrt_wake_word.vxrt
V=voxrt-demo/vendor
K=$V/java/com/voxrt/sdk/wakeword
mkdir -p "$K" "$V/jniLibs/arm64-v8a" "$V/jniLibs/x86_64" "$V/assets"

fetch() { # url dest sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c --status; then
    curl -fL --retry 3 -o "$2" "$1"
  fi
  echo "$3  $2" | shasum -a 256 -c --status || { echo "checksum mismatch: $2" >&2; rm -f "$2"; exit 1; }
}

fetch "$RAW/java/com/voxrt/sdk/wakeword/CpuAffinity.kt" "$K/CpuAffinity.kt" \
  6cb174496e016ff86acef7ce84ae74bdff10c126cd20f9038f12d093ace96c64
fetch "$RAW/java/com/voxrt/sdk/wakeword/VoxrtWakeWordEngine.kt" "$K/VoxrtWakeWordEngine.kt" \
  b5caaa94d636a42ee987eaf63b0a398757f45d8c53c310d99e0d2aaee0fa9074
fetch "$RAW/java/com/voxrt/sdk/wakeword/VoxrtWakeWordNative.kt" "$K/VoxrtWakeWordNative.kt" \
  61e867caaba463f30c60cc123ff262677793bddeed8460be68ac38aa3e10498e
fetch "$RAW/java/com/voxrt/sdk/wakeword/WakeWordDetection.kt" "$K/WakeWordDetection.kt" \
  d25b247946b502dfd690fc0b05f940b37e14c40655a130b55376533fcd3f625a
fetch "$RAW/jniLibs/arm64-v8a/libvoxrt_wake_word.so" "$V/jniLibs/arm64-v8a/libvoxrt_wake_word.so" \
  d9bdf3502f45c96347e544c978ee8a2a9374245bdbe095d0ae59bee543e95683
fetch "$RAW/jniLibs/x86_64/libvoxrt_wake_word.so" "$V/jniLibs/x86_64/libvoxrt_wake_word.so" \
  dc204e2d404929582f34ee70b2fc513a55fe6d48398a52a99d982cad2a33c9c8
fetch "$MODEL" "$V/assets/voxrt_wake_word.vxrt" \
  9d40bdc132a2ad8e85bd8a28bb49b77c51a7c62f60567222a037e44418510e8f
du -sh "$V"
