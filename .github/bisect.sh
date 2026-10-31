#!/usr/bin/env bash
# Starts the app once with one part switched off ($1, a launch extra), records frames for a minute,
# and fails if the emulator has gone away. Used to find what an emulator cannot draw.
set -u
PKG=com.abeernch.civicobd
OUT=bisect
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/uitest/app-uitest.apk
for p in BLUETOOTH_CONNECT POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb logcat -c; adb logcat -v time '*:W' > "$OUT/logcat.txt" 2>&1 &
adb shell am start -n $PKG/.MainActivity --ez demo true --ez seed_fuel true --ez "$1" true
for n in $(seq -w 1 30); do adb exec-out screencap -p > "$OUT/f_$n.png" 2>/dev/null || break; done
sleep 30
STATE=$(adb get-state 2>/dev/null | tr -d '\r')
echo "variant $1: emulator state after a minute: ${STATE:-gone}" | tee "$OUT/result.txt"
[ "$STATE" = "device" ] && adb exec-out screencap -p > "$OUT/final.png"
[ "$STATE" = "device" ]
