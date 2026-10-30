#!/usr/bin/env bash
# Drives the app on an Android emulator like a person would, and saves what the screen shows.
# Runs inside reactivecircus/android-emulator-runner. Output goes to ./ui-test/.
set -u
PKG=com.abeernch.civicobd
OUT=ui-test
mkdir -p "$OUT"
APK=app/build/outputs/apk/uitest/app-uitest.apk

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
    Drive) i=0;; Trips) i=1;; Stats) i=2;; Car) i=3;; Settings) i=4;; *) i=-1;;
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
# Full device log in the background, so a crash leaves a trace even if the emulator goes away.
adb logcat -c; adb logcat -v time '*:W' > "$OUT/logcat.txt" 2>&1 &
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true
adb shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION || true
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1

# A small Car Scanner style CSV, copied into the app's private folder (debug builds allow run-as),
# then imported through the same code path as a picked file.
adb push .github/sample_carscanner.csv /data/local/tmp/2026-10-22_09-00-39.csv
adb shell "cat /data/local/tmp/2026-10-22_09-00-39.csv | run-as $PKG sh -c 'mkdir -p files && cat > files/2026-10-22_09-00-39.csv'"
CSV_PATH=/data/data/$PKG/files/2026-10-22_09-00-39.csv

# Scrolls down (up to 16 swipes) until the text is on screen, then taps it.
scroll_tap() {
  for n in $(seq 1 16); do
    xy=$(pos_text "$1")
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tap '$1' at $xy after $n looks"; return 0; fi
    adb shell input swipe 540 1600 540 900 300; sleep 0.8
  done
  echo "NOT FOUND after scrolling: '$1'"; return 1
}

# Finds an element's centre without tapping it.
pos_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 .github/find_text.py "$OUT/ui.xml" "$1" 0
}

# Fast frame grabs (about 3 to 5 per second), more reliable than screenrecord on emulators.
burst() {  # burst NAME COUNT
  for n in $(seq -w 1 "$2"); do adb exec-out screencap -p > "$OUT/$1_$n.png"; done
}

# 1. Cold start with the demo car switched on: the splash, the drive welcome (the car's lights come up,
#    a greeting), then the cluster sweeping awake. Frames throughout, plus a recording attempt.
adb shell screenrecord --size 720x1600 --bit-rate 6000000 --time-limit 14 /sdcard/splash.mp4 &
REC=$!
sleep 2
adb shell am start -n $PKG/.MainActivity --ez demo true --ez seed_fuel true --es import_path $CSV_PATH
burst start 40
wait $REC
adb pull /sdcard/splash.mp4 "$OUT/splash.mp4"

# 2. Let the demo drive run into the acceleration and cruise phases.
sleep 14; shot 03_drive_accelerating
# Press a control and catch the light passing across it (the Bluetooth tile just looks for the adapter again).
xy=$(pos_text "BLUETOOTH"); if [ -n "$xy" ]; then adb shell input tap $xy; burst 03c_tile_press 4; fi
sleep 20; shot 04_drive_cruise

# 3. Tab switching: find the tab positions once, then tap and grab frames mid-animation.
TAB_DRIVE=$(pos_text "Drive"); TAB_STATS=$(pos_text "Stats"); TAB_SETTINGS=$(pos_text "Settings")
echo "tabs: drive=$TAB_DRIVE stats=$TAB_STATS settings=$TAB_SETTINGS"
adb shell screenrecord --size 720x1600 --bit-rate 6000000 --time-limit 10 /sdcard/tabs.mp4 &
REC=$!
sleep 1
[ -n "$TAB_STATS" ] && adb shell input tap $TAB_STATS; burst tab_to_stats 5
sleep 1
[ -n "$TAB_SETTINGS" ] && adb shell input tap $TAB_SETTINGS; burst tab_to_settings 5
sleep 1
[ -n "$TAB_DRIVE" ] && adb shell input tap $TAB_DRIVE; burst tab_to_drive 5
wait $REC
adb pull /sdcard/tabs.mp4 "$OUT/tabs.mp4"

# 4. Coasting with fuel cut, HUD, then each screen.
sleep 18; shot 05_drive_fuel_cut
tap_text "HUD"; sleep 3; shot 05b_hud
# Leaving the HUD must return to the same screen at once, with no splash.
adb shell input keyevent KEYCODE_BACK; sleep 0.6; shot 05b2_after_hud
sleep 1

# 4b. Live map on Drive, both views in landscape, then the floating window over the home screen.
tap_text "Map"; sleep 6; shot 05c_drive_map
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 5; shot 05d_map_landscape
tap_text "Gauges"; sleep 3; shot 05e_gauges_landscape
adb shell settings put system user_rotation 0; sleep 4
tap_text "Map"; sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 5; shot 05f_floating_window
adb shell am start -n $PKG/.MainActivity; sleep 4; shot 05g_back_from_window
tap_text "Gauges"; sleep 2

tap_text "Car"; sleep 2; shot 06_car_top
adb shell input swipe 540 1700 540 700 400; sleep 1.5; shot 07_car_tank
scroll_tap "Read codes now"; sleep 4; shot 08b_car_faults_read
adb shell input swipe 540 1700 540 400 400; sleep 1.5; shot 08c_car_battery
# A service job opened for editing.
adb shell input swipe 540 500 540 2000 300; adb shell input swipe 540 500 540 2000 300; sleep 1
tap_text "Engine oil and filter"; sleep 2; shot 08d_car_service_edit
tap_text "Engine oil and filter"; sleep 1
tap_text "Settings"; sleep 2; shot 09_settings_top
tap_text "Daylight"; sleep 2; shot 09c_settings_light
tap_text "Drive"; sleep 2; shot 09d_drive_light
tap_text "Settings"; sleep 2
tap_text "Grand prix"; sleep 2; shot 09e_settings_grandprix
tap_text "Drive"; sleep 2; shot 09f_drive_grandprix
tap_text "Settings"; sleep 2
tap_text "Night"; sleep 2

# 5. Park the car: switching the demo off stops the engine; 60 s later the trip ends with a summary.
tap_text "Settings"; sleep 2
scroll_tap "Stop demo drive"; sleep 95
adb shell cmd statusbar expand-notifications; sleep 2; shot 10a_trip_notification
adb shell cmd statusbar collapse; sleep 1
tap_text "Drive"; sleep 2; shot 10d_drive_parked
adb shell settings put system user_rotation 1; sleep 4; shot 10d2_parked_landscape
adb shell settings put system user_rotation 0; sleep 4
# End the paused drive, so it can be merged later on: the goodbye plays (the car's lights go out).
tap_text "End drive now"; burst 10e_goodbye 30; sleep 4; shot 10f_drive_ended
adb shell settings put system user_rotation 1; sleep 4; shot 10f2_ended_landscape
adb shell settings put system user_rotation 0; sleep 4
tap_text "Stats"; sleep 2; shot 10_stats_week
# The period is a menu now: open it, then pick Today.
tap_text "This week"; sleep 1.5; shot 10e_stats_period_menu
tap_text "Today"; sleep 2; shot 11_stats_today
# Month view: October by name, step back to September and forward again, then select a bar.
tap_text "Today"; sleep 1; tap_text "This month"; sleep 2; shot 11b_stats_month
tap_text "Previous"; sleep 2; shot 11c_stats_prev_month
tap_text "Next"; sleep 2
xy=$(pos_text "26"); if [ -n "$xy" ]; then set -- $xy; adb shell input tap $1 $(( $2 - 160 )); fi; sleep 1.5; shot 11d_stats_bar_selected
# Year view: twelve months, a step back to last year.
tap_text "This month"; sleep 1; tap_text "This year"; sleep 2; shot 11e_stats_year
tap_text "Previous"; sleep 2; shot 11f_stats_prev_year
adb shell input swipe 540 1700 540 600 400; sleep 1.5; shot 12_stats_trips
tap_text "Car"; sleep 2
scroll_tap "Fill-ups"; sleep 2; shot 10b_fuel
tap_text "Log a fill-up"; sleep 4; shot 10c_fuel_form
# One back closes the form; the tab bar takes it from there (two backs could leave the app).
adb shell input keyevent KEYCODE_BACK; sleep 1.5

# 6. Trips: the list, an imported drive's page, then the demo drive's page and route map.
tap_text "Trips"; sleep 2; shot 13_trips_list
tap_text "Car Scanner"; sleep 4; shot 14_trip_imported
adb shell input swipe 540 1800 540 700 400; sleep 1.5; shot 14b_trip_imported_lower
# The chart: one plot for every reading picked. Place the marker, then add RPM.
tap_text "Chart"; sleep 3; shot 15_trip_chart
xy=$(pos_text "Drag across the chart"); if [ -n "$xy" ]; then set -- $xy; adb shell input tap 540 $(( $2 + 400 )); fi; sleep 1.5; shot 15b_trip_chart_marker
tap_text "RPM"; sleep 2; shot 16_trip_chart_rpm
adb shell input keyevent KEYCODE_BACK; sleep 1.5
# The newest drive is the demo one; every card shows "km/L", so the first match is the top card.
tap_text "km/L"; sleep 4; shot 18_trip_demo
adb shell input swipe 540 1800 540 900 400; sleep 2; shot 18a_trip_demo_map
tap_text "Open the full-screen map"; sleep 8; shot 20_route_map
# Press and hold on the map, then slide: the marker should snap to the route and follow it.
adb shell input swipe 400 800 402 802 1500; sleep 1; shot 21_route_hold
adb shell input swipe 300 700 800 1200 3000; sleep 1; shot 22_route_scrub
# Route options are menus: open each by its current value, then pick.
tap_text "Economy"; sleep 1; tap_text "Speed"; sleep 2; shot 24_route_speed
tap_text "Parula"; sleep 1; tap_text "Red–yellow–green"; sleep 2; shot 23_route_rdylgn
scroll_tap "Esri gray"; sleep 1; tap_text "OpenStreetMap"; sleep 6; shot 22b_route_osm
adb shell input keyevent KEYCODE_BACK; sleep 1.5
# Back to the list, pick the two newest drives and merge them.
adb shell input keyevent KEYCODE_BACK; sleep 1.5
tap_text "Select"; sleep 1
tap_text "km/L" 0; sleep 0.5; tap_text "km/L" 1; sleep 1; shot 18b_merge_picked
tap_text "Merge 2 drives"; sleep 3; shot 18c_merged
adb shell input keyevent KEYCODE_BACK; sleep 1.5; shot 13c_trips_after_merge
tap_text "Routes"; sleep 4; shot 13b_trips_routes

# 7. Connection log, now in Settings.
tap_text "Settings"; sleep 1
scroll_tap "Show log"; sleep 1.5; shot 19_settings_log
adb logcat -d -s AndroidRuntime:E > "$OUT/crash_log.txt" 2>&1 || true
adb shell dumpsys activity services $PKG | head -40 > "$OUT/service_state.txt" || true
# Last: the all-drives fuel map, the heaviest screen for a GPU-less emulator. Everything above is saved first.
tap_text "Trips"; sleep 2
tap_text "Routes"; sleep 3
tap_text "Fuel map of all drives"; sleep 8; shot 26_fuel_map
echo "done"
