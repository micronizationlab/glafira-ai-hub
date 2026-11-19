#!/usr/bin/env bash
# GitHub-hosted Ubuntu runner only. Require VM acceleration; never silently fall
# back to software CPU emulation. Keep every device operation and teardown bounded.
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
OUTPUT="$ROOT/app/build/outputs"
mkdir -p "$OUTPUT"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
: "${SDK_ROOT:?ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK}"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$SDK_ROOT/emulator/emulator"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL=emulator-5554
export ANDROID_SERIAL="$SERIAL"
export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

# Overrides allow the host regression tests to exercise deadlines in seconds.
BOOT_TIMEOUT_SECONDS=${BOOT_TIMEOUT_SECONDS:-480}
PACKAGE_TIMEOUT_SECONDS=${PACKAGE_TIMEOUT_SECONDS:-120}
ADB_TIMEOUT_SECONDS=${ADB_TIMEOUT_SECONDS:-10}
CLEANUP_TIMEOUT_SECONDS=${CLEANUP_TIMEOUT_SECONDS:-10}
POLL_SECONDS=${POLL_SECONDS:-5}
EMULATOR_PID=
LOGCAT_PID=

adb_bounded() {
  timeout --signal=TERM --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" "$ADB" -s "$SERIAL" "$@"
}

stop_process() {
  local pid=${1:-}
  [[ -n "$pid" ]] || return 0
  # Each long-lived process starts in its own session, so terminate children
  # such as qemu as well as the emulator launcher itself.
  kill -TERM -- "-$pid" 2>/dev/null || true
  local deadline=$((SECONDS + CLEANUP_TIMEOUT_SECONDS))
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep 0.2
  done
  kill -KILL -- "-$pid" 2>/dev/null || true
  deadline=$((SECONDS + CLEANUP_TIMEOUT_SECONDS))
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep 0.2
  done
  # Even a host process stuck in uninterruptible I/O must not hold teardown.
  if ! kill -0 "$pid" 2>/dev/null; then
    wait "$pid" 2>/dev/null || true
  else
    echo "Process $pid did not exit after SIGKILL; leaving it to runner teardown" >&2
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  stop_process "$LOGCAT_PID"
  if [[ -x "$ADB" ]]; then
    adb_bounded devices -l > "$OUTPUT/androidTest-adb-devices-final.txt" 2>&1
    adb_bounded logcat -b crash -d -v threadtime > "$OUTPUT/androidTest-crash-logcat.txt" 2>&1
    adb_bounded shell dumpsys activity instrumentation > "$OUTPUT/androidTest-instrumentation.txt" 2>&1
    adb_bounded logcat -d -v threadtime > "$OUTPUT/androidTest-logcat.txt" 2>&1
    adb_bounded emu kill > "$OUTPUT/androidTest-emulator-shutdown.txt" 2>&1
  fi
  stop_process "$EMULATOR_PID"
  printf 'Original test step exit status: %s\n' "$status" > "$OUTPUT/androidTest-exit-status.txt"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! -x "$SDKMANAGER" || ! -x "$AVDMANAGER" ]]; then
  echo "Android command-line tools not found under $SDK_ROOT" >&2
  exit 1
fi
timeout --kill-after=10s 2m "$SDKMANAGER" --licenses < <(yes) > "$OUTPUT/androidTest-sdk-licenses.txt" 2>&1
timeout --kill-after=10s 8m "$SDKMANAGER" --install "platform-tools" "emulator" "platforms;android-35" "system-images;android-35;google_apis;x86_64"
if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  echo "SDK install did not provide adb and emulator" >&2
  exit 1
fi
timeout --kill-after=2s 30s "$EMULATOR" -accel-check > "$OUTPUT/androidTest-acceleration.txt" 2>&1
cat "$OUTPUT/androidTest-acceleration.txt"
printf 'no\n' | timeout --kill-after=2s 60s "$AVDMANAGER" create avd --force --name glafira-ci --package "system-images;android-35;google_apis;x86_64" --device pixel_2
adb_bounded start-server
adb_bounded devices -l > "$OUTPUT/androidTest-adb-devices.txt" 2>&1
setsid "$EMULATOR" -port 5554 -avd glafira-ci -no-window -gpu swiftshader -no-snapshot -noaudio -no-boot-anim -camera-back none -camera-front none -accel on > "$OUTPUT/androidTest-emulator-console.txt" 2>&1 &
EMULATOR_PID=$!

deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
booted=false
while (( SECONDS < deadline )); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "Android emulator exited before boot completed" >&2
    exit 1
  fi
  if boot_state=$(adb_bounded shell getprop sys.boot_completed 2>/dev/null) && [[ "${boot_state//$'\r'/}" == "1" ]]; then
    booted=true
    break
  fi
  sleep "$POLL_SECONDS"
done
if [[ "$booted" != true ]]; then
  echo "Android emulator did not boot within ${BOOT_TIMEOUT_SECONDS}s" >&2
  exit 1
fi

# Do not inject unlock keyevents: package-manager readiness is the actual
# prerequisite for installing the instrumentation APK and has its own deadline.
deadline=$((SECONDS + PACKAGE_TIMEOUT_SECONDS))
package_ready=false
while (( SECONDS < deadline )); do
  if adb_bounded shell pm path android 2>/dev/null | grep -q '^package:'; then
    package_ready=true
    break
  fi
  sleep "$POLL_SECONDS"
done
if [[ "$package_ready" != true ]]; then
  echo "Android package manager did not become ready within ${PACKAGE_TIMEOUT_SECONDS}s" >&2
  exit 1
fi
adb_bounded logcat -c
# Streaming logcat intentionally runs until cleanup, which owns and stops its PID.
setsid "$ADB" -s "$SERIAL" logcat -b all -v threadtime > "$OUTPUT/androidTest-logcat-live.txt" 2>&1 &
LOGCAT_PID=$!
if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
  echo "Android emulator exited before instrumentation started" >&2
  exit 1
fi
timeout --signal=TERM --kill-after=30s 20m gradle :app:connectedDebugAndroidTest --stacktrace
python3 .github/scripts/verify-junit.py app/build/outputs/androidTest-results/connected
