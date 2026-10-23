#!/usr/bin/env bash
# Drives the app on an Android emulator like a person would, and saves what the screen shows.
# Runs inside reactivecircus/android-emulator-runner. Output goes to ./ui-test/.
set -u
PKG=com.abeernch.civicobd
OUT=ui-test
mkdir -p "$OUT"
APK=app/build/outputs/apk/debug/app-debug.apk

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

# Taps the centre of the first on-screen element whose text matches $1 (exact), via the accessibility tree.
tap_text() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    xy=$(python3 .github/find_text.py "$OUT/ui.xml" "$1" "${2:-0}")
    if [ -n "$xy" ]; then
      adb shell input tap $xy
      echo "tap '$1' at $xy"
      return 0
    fi
    sleep 1
  done
  # Fallback for the five tabs: the bar sits at the bottom, slots of equal width.
  case "$1" in
    Drive) i=0;; Sensors) i=1;; Stats) i=2;; Logs) i=3;; Settings) i=4;; *) i=-1;;
  esac
  if [ "$i" -ge 0 ]; then
    read W H < <(adb shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/' | tr -d '\r')
    D=$(adb shell wm density | sed -E 's/.*: ([0-9]+).*/\1/' | tr -d '\r')
    x=$(( (8*D/160) + (W - 16*D/160) * (2*i + 1) / 10 ))
    y=$(( H - (60*D/160) ))
    adb shell input tap $x $y
    echo "tap '$1' by position $x $y"
    return 0
  fi
  echo "NOT FOUND: '$1'"
  cp "$OUT/ui.xml" "$OUT/missing_$(echo "$1" | tr -c 'a-zA-Z0-9' '_').xml" 2>/dev/null
  return 1
}

adb install -r "$APK"
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1

# A small Car Scanner style CSV, copied into the app's private folder (debug builds allow run-as),
# then imported through the same code path as a picked file.
adb push .github/sample_carscanner.csv /data/local/tmp/2026-10-22_09-00-39.csv
adb shell "cat /data/local/tmp/2026-10-22_09-00-39.csv | run-as $PKG sh -c 'mkdir -p files && cat > files/2026-10-22_09-00-39.csv'"
CSV_PATH=/data/data/$PKG/files/2026-10-22_09-00-39.csv

# 1. Cold start, recorded, with the demo car switched on.
adb shell screenrecord --time-limit 8 /sdcard/splash.mp4 &
REC=$!
sleep 1
adb shell am start -n $PKG/.MainActivity --ez demo true \
  --es import_path $CSV_PATH
sleep 0.6; shot 01_splash_early
sleep 0.6; shot 02_splash_mid
wait $REC
adb pull /sdcard/splash.mp4 "$OUT/splash.mp4"

# 2. Let the demo drive run into the acceleration and cruise phases.
sleep 25; shot 03_drive_accelerating
sleep 20; shot 04_drive_cruise

# 3. Record tab switching with animations on.
adb shell screenrecord --time-limit 12 /sdcard/tabs.mp4 &
REC=$!
sleep 1
tap_text "Sensors"; sleep 1.5
tap_text "Stats"; sleep 1.5
tap_text "Logs"; sleep 1.5
tap_text "Settings"; sleep 1.5
tap_text "Drive"; sleep 1.5
wait $REC
adb pull /sdcard/tabs.mp4 "$OUT/tabs.mp4"

# 4. Coasting with fuel cut, then each screen.
sleep 22; shot 05_drive_fuel_cut
tap_text "Sensors"; sleep 2; shot 06_sensors_top
adb shell input swipe 540 1700 540 500 400; sleep 1.5; shot 07_sensors_engine
adb shell input swipe 540 1700 540 400 400; sleep 1.5; shot 08_sensors_diagnostics
tap_text "Settings"; sleep 2; shot 09_settings

# 5. Wait for the demo engine-off phase so the trip closes, then statistics.
sleep 120
tap_text "Stats"; sleep 2; shot 10_stats_week
tap_text "Today"; sleep 2; shot 11_stats_today
adb shell input swipe 540 1700 540 600 400; sleep 1.5; shot 12_stats_trips

# 6. Logs: list, open the imported recording, read values, combined plot.
tap_text "Logs"; sleep 2; shot 13_logs_list
tap_text "Car Scanner"; sleep 3; shot 14_recording_separate
adb shell input tap 700 900; sleep 1.5; shot 15_recording_marker
tap_text "One plot"; sleep 2; shot 16_recording_combined
adb shell input tap 500 1000; sleep 1.5; shot 17_recording_combined_marker
adb shell input keyevent KEYCODE_BACK; sleep 1.5
tap_text "Live" 0 || tap_text "Demo" 0; sleep 3; shot 18_recording_demo

# 7. Connection log from the app itself.
tap_text "Sensors"; sleep 1
for i in 1 2 3 4; do adb shell input swipe 540 1800 540 300 300; sleep 0.5; done
shot 19_sensors_log
adb logcat -d -s AndroidRuntime:E > "$OUT/crash_log.txt" 2>&1 || true
adb shell dumpsys activity services $PKG | head -40 > "$OUT/service_state.txt" || true
echo "done"
