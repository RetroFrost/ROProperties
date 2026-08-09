#!/usr/bin/env bash
set -euo pipefail

mkdir -p ci-artifacts

collect_debug() {
  adb logcat -d -v threadtime > ci-artifacts/logcat.txt 2>&1 || true
  adb shell dumpsys activity activities > ci-artifacts/activities.txt 2>&1 || true
  adb shell dumpsys meminfo com.frameflow.app > ci-artifacts/meminfo.txt 2>&1 || true
  adb shell screencap -p /sdcard/frameflow-ci.png >/dev/null 2>&1 || true
  adb pull /sdcard/frameflow-ci.png ci-artifacts/screen.png >/dev/null 2>&1 || true
}
trap collect_debug EXIT

adb logcat -c
gradle --no-daemon :app:connectedDebugAndroidTest --stacktrace
adb shell am force-stop com.frameflow.app
adb shell am start -W -n com.frameflow.app/.MainActivity
sleep 3

test -n "$(adb shell pidof com.frameflow.app)"
adb shell dumpsys activity activities | grep -q 'com.frameflow.app/.MainActivity'
if adb logcat -d -v brief | grep -E 'FATAL EXCEPTION.*com.frameflow.app|Process: com.frameflow.app.*FATAL'; then
  echo 'Frameflow logged a fatal exception during interaction verification.' >&2
  exit 1
fi

echo 'Frameflow interaction verification passed.'
