#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-results
trap 'adb logcat -d > smoke-results/logcat.txt; adb shell dumpsys activity activities > smoke-results/activity.txt; adb exec-out screencap -p > smoke-results/screen.png' EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
wait_for_quote() {
  for tick in $(seq 1 30); do
    adb logcat -d -s GoldTickerStream:I > smoke-results/stream.txt
    if grep -q 'quote_received' smoke-results/stream.txt; then return 0; fi
    sleep 1
  done
  echo 'No TradingView quote received within 30 seconds'
  return 1
}
for attempt in 1 2 3; do
  adb shell am force-stop com.vlad.goldticker
  adb shell am start -W -n com.vlad.goldticker/.MainActivity
  wait_for_quote
  adb shell pidof com.vlad.goldticker
  adb shell dumpsys activity activities | grep -E '(mResumedActivity|topResumedActivity).*com.vlad.goldticker'
  adb logcat -c
done
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n com.vlad.goldticker/.MainActivity
wait_for_quote
adb shell pidof com.vlad.goldticker
adb shell dumpsys activity activities | grep -E '(mResumedActivity|topResumedActivity).*com.vlad.goldticker'
if adb logcat -d -b crash | grep -q 'com.vlad.goldticker'; then
  echo 'GoldTicker crashed during startup/resume'
  exit 1
fi
echo 'PASS: TradingView quote received on three cold launches and after resume'
