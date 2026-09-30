#!/usr/bin/env bash
# Install and launch the wake-word demo on a USB-connected phone, then stream its log.
#
#   ./run_on_phone.sh                 # latest CI-built APK (needs `gh`), else builds locally
#   ./run_on_phone.sh path/to.apk     # a specific APK
#   ANDROID_SERIAL=XYZ ./run_on_phone.sh   # pick a device when several are connected
#
# Needs: adb (Android platform-tools). Optional: gh (GitHub CLI, logged in) or an Android SDK.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.findmyphone.wakeword.demo
ARTIFACT=wakeword-demo-apk
MODULE=demo-app

die() { echo "error: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found. Install Android platform-tools:
  macOS:   brew install --cask android-platform-tools
  Ubuntu:  sudo apt install adb
  Windows: https://developer.android.com/tools/releases/platform-tools (then see README for the 3 adb commands)"

# ---- 1. device ---------------------------------------------------------------
adb start-server >/dev/null
if [ -z "${ANDROID_SERIAL:-}" ]; then
  # (portable to macOS's bash 3.2: no mapfile)
  devs=$(adb devices | tail -n +2 | grep -v '^[[:space:]]*$' || true)
  [ -n "$devs" ] || die "no device found. Plug the phone in, enable USB debugging, and use a data (not charge-only) cable."
  if echo "$devs" | grep -q unauthorized; then
    die "phone is 'unauthorized': unlock it and accept the 'Allow USB debugging?' prompt, then rerun."
  fi
  ready=$(echo "$devs" | awk '$2=="device"{print $1}')
  n=$(echo "$ready" | grep -c . || true)
  [ "$n" -ge 1 ] || die "device not ready: $devs"
  [ "$n" -eq 1 ] || die "several devices connected; choose one: ANDROID_SERIAL=<serial> $0
$ready"
  export ANDROID_SERIAL="$ready"
fi
echo "device: $ANDROID_SERIAL ($(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r'))"

# ---- 2. APK ------------------------------------------------------------------
apk="${1:-}"
if [ -z "$apk" ] && command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  repo=$(git remote get-url origin | sed -E 's#^(https://github.com/|git@github.com:)##; s#\.git$##; s#^.*/git/##')
  branch=$(git rev-parse --abbrev-ref HEAD)
  run=$(gh run list --repo "$repo" --branch "$branch" --workflow build --status success --limit 1 \
          --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
  if [ -n "$run" ]; then
    echo "downloading APK from CI run $run ($repo@$branch)"
    tmp=$(mktemp -d)
    gh run download "$run" --repo "$repo" -n "$ARTIFACT" -D "$tmp"
    apk="$tmp/$MODULE-debug.apk"
  fi
fi
if [ -z "$apk" ]; then
  if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || [ -f local.properties ]; then
    echo "building APK locally"
    ./fetch_models.sh
    ./fetch_davoice.sh
    ./fetch_livekit.sh
    ./gradlew ":$MODULE:assembleDebug" --console=plain
    apk=$MODULE/build/outputs/apk/debug/$MODULE-debug.apk
  else
    die "no APK. Either:
  - install GitHub CLI and run 'gh auth login' (downloads the CI-built APK), or
  - download '$ARTIFACT' from the repo's Actions tab and run: $0 path/to/$MODULE-debug.apk, or
  - install the Android SDK (Android Studio) to build locally."
  fi
fi
[ -f "$apk" ] || die "APK not found: $apk"

# ---- 3. install + launch -------------------------------------------------------
echo "installing $apk"
if ! out=$(adb install -r -g "$apk" 2>&1); then  # -g pre-grants microphone + notification permissions
  echo "$out"
  if echo "$out" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
    die "the installed $PKG was signed by a different build (each CI run has its own debug key).
Uninstall it first (this deletes its saved settings), then rerun:  adb uninstall $PKG"
  fi
  exit 1
fi
echo "$out" | tail -1
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo
echo "App launched. Pick an engine and a phrase, tap 'Start listening', then speak."
echo "Detections and errors stream below (Ctrl-C to stop watching; the app keeps running)."
echo "---------------------------------------------------------------------------------"

# ---- 4. watch --------------------------------------------------------------------
adb logcat -v time -s WakeWordService:V DaVoiceDemo:V AndroidRuntime:E
