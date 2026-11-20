#!/usr/bin/env bash
set -Eeuo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android/sdk}}"
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo "Android SDK root is unavailable." >&2
  exit 2
fi
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

find_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi
  find "$SDK_ROOT" -type f -name "$name" -print -quit
}

SDKMANAGER="$(find_tool sdkmanager)"
AVDMANAGER="$(find_tool avdmanager)"
EMULATOR="$(find_tool emulator)"
ADB="$(find_tool adb)"
for tool in "$SDKMANAGER" "$AVDMANAGER" "$EMULATOR" "$ADB"; do
  if [[ -z "$tool" || ! -x "$tool" ]]; then
    echo "Required Android SDK tool is missing: $tool" >&2
    exit 2
  fi
done

IMAGE="system-images;android-35;google_apis;x86_64"
AVD_NAME="glafira-acceptance-api35"
LOG_DIR="app/build/outputs/androidTest-results/connected"
mkdir -p "$LOG_DIR"

emulator_pid=""
finish() {
  local result=$?
  "$ADB" logcat -d > "$LOG_DIR/emulator-logcat.txt" 2>&1 || true
  "$ADB" emu kill >/dev/null 2>&1 || true
  if [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" 2>/dev/null; then
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  exit "$result"
}
trap finish EXIT

yes | "$SDKMANAGER" --licenses >/dev/null || "$SDKMANAGER" --licenses </dev/null >/dev/null
"$SDKMANAGER" "platform-tools" "platforms;android-35" "$IMAGE"

echo "no" | "$AVDMANAGER" create avd --force --name "$AVD_NAME" --package "$IMAGE" --device "pixel_2"

"$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
  -no-snapshot -wipe-data -accel on -gpu swiftshader_indirect \
  > "$LOG_DIR/emulator-console.log" 2>&1 &
emulator_pid=$!

if ! timeout 120 "$ADB" wait-for-device; then
  echo "ADB did not connect to the Android emulator." >&2
  exit 1
fi

booted=false
for _ in $(seq 1 240); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo "Android emulator process exited before boot completed." >&2
    exit 1
  fi
  state="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == "1" ]]; then
    booted=true
    break
  fi
  sleep 2
done

if [[ "$booted" != "true" ]]; then
  echo "Android emulator did not finish booting within the bounded 8-minute window." >&2
  "$ADB" devices -l || true
  "$ADB" shell getprop || true
  exit 1
fi

"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
