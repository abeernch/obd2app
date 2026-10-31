#!/usr/bin/env bash
# Starts the app and checks the emulator is still there a minute later.
# $1: how frames are taken: burst (back to back), slow (one every 2 s) or none. $2: an extra launch flag.
set -u
PKG=com.abeernch.civicobd
OUT=bisect
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/uitest/app-uitest.apk
for p in BLUETOOTH_CONNECT POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb logcat -c; adb logcat -v time '*:W' > "$OUT/logcat.txt" 2>&1 &
adb shell am start -n $PKG/.MainActivity --ez demo true --ez seed_fuel true --ez "${2:-base}" true
case "$1" in
  burst) for n in $(seq -w 1 30); do adb exec-out screencap -p > "$OUT/f_$n.png" 2>/dev/null || break; done; sleep 30 ;;
  slow) for n in $(seq -w 1 30); do adb exec-out screencap -p > "$OUT/f_$n.png" 2>/dev/null || break; sleep 2; done ;;
  *) sleep 60 ;;
esac
STATE=$(adb get-state 2>/dev/null | tr -d '\r')
echo "mode $1 flag ${2:-base}: emulator after a minute: ${STATE:-gone}" | tee "$OUT/result.txt"
[ "$STATE" = "device" ] && adb exec-out screencap -p > "$OUT/final.png"
[ "$STATE" = "device" ]
