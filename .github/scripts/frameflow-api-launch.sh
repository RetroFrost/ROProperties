#!/usr/bin/env bash
set -euo pipefail

API="${FRAMEFLOW_API:?FRAMEFLOW_API must be set}"
mkdir -p ci-artifacts

collect_debug() {
  adb logcat -d -v threadtime > "ci-artifacts/logcat-api-${API}.txt" 2>&1 || true
  adb shell dumpsys activity activities > "ci-artifacts/activities-api-${API}.txt" 2>&1 || true
  adb shell dumpsys meminfo com.frameflow.app > "ci-artifacts/meminfo-api-${API}.txt" 2>&1 || true
}
trap collect_debug EXIT

APK="$(find apk -name '*.apk' -type f | head -1)"
test -n "$APK"
adb install -r "$APK"
# Some older emulator images refuse to clear one log buffer even though log reading works.
adb logcat -c >/dev/null 2>&1 || true

adb shell am force-stop com.frameflow.app
adb shell am start -W -n com.frameflow.app/.MainActivity
sleep 3
test -n "$(adb shell pidof com.frameflow.app)"

adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -W -n com.frameflow.app/.MainActivity
sleep 2
test -n "$(adb shell pidof com.frameflow.app)"

adb shell am force-stop com.frameflow.app
adb shell am start -W -n com.frameflow.app/.MainActivity
sleep 2
test -n "$(adb shell pidof com.frameflow.app)"

if adb logcat -d -v brief | grep -E 'FATAL EXCEPTION.*com.frameflow.app|Process: com.frameflow.app.*FATAL'; then
  echo "Frameflow logged a fatal exception on API ${API}." >&2
  exit 1
fi

echo "Frameflow API ${API} lifecycle verification passed."
