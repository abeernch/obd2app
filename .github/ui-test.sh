#!/usr/bin/env bash
# Drives the app on an Android emulator like a person would, and saves what the screen shows.
# Runs inside reactivecircus/android-emulator-runner. Output goes to ./ui-test/.
set -u
PKG=com.abeernch.civicobd
OUT=ui-test
mkdir -p "$OUT"
APK=app/build/outputs/apk/uitest/app-uitest.apk

# Runs on an Android 11 emulator: the Android 13 and 14 emulator images (emulator 37.2) go down when the
# screen is captured while this app is showing. The app itself runs on them; only the capture fails.
# Frames are taken raw and turned into PNGs here: encoding them on the emulator takes seconds each.
shot() { adb exec-out screencap > "$OUT/.raw_$1" && python3 .github/raw2png.py "$OUT/.raw_$1" "$OUT/$1.png"; rm -f "$OUT/.raw_$1"; echo "shot $1"; }

# Stops early if the emulator has gone away, instead of spending the time limit on failing commands.
alive() {
  if [ "$(adb get-state 2>/dev/null | tr -d '\r')" != "device" ]; then
    echo "EMULATOR GONE at: $1"
    adb logcat -d -s AndroidRuntime:E > "$OUT/crash_log.txt" 2>&1 || true
    exit 1
  fi
}

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

# Scrolls down (up to 16 swipes) until the text is on screen, without tapping it.
scroll_to() {
  for n in $(seq 1 16); do
    if [ -n "$(pos_text "$1")" ]; then echo "found '$1' after $n looks"; return 0; fi
    adb shell input swipe 540 1600 540 900 300; sleep 0.8
  done
  echo "NOT FOUND after scrolling: '$1'"; return 1
}

# Taps the text field on the same row as a label, then types into it (after clearing it, if asked).
type_field() {  # type_field LABEL TEXT [clear]
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  xy=$(python3 .github/find_field.py "$OUT/ui.xml" "$1")
  if [ -z "$xy" ]; then echo "NOT FOUND: field '$1'"; return 1; fi
  adb shell input tap $xy; sleep 0.8
  if [ -n "${3:-}" ]; then adb shell input keyevent KEYCODE_MOVE_END; for n in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DEL; done; fi
  adb shell input text "$2"; echo "typed '$2' into '$1' at $xy"
}

# Closes the on-screen keyboard if it is showing (a Back with no keyboard would leave the screen).
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
}

# Finds an element's centre without tapping it.
pos_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 .github/find_text.py "$OUT/ui.xml" "$1" 0
}

# Fast frame grabs: raw frames first, as quickly as the emulator gives them, then the PNGs.
burst() {  # burst NAME COUNT
  for n in $(seq -w 1 "$2"); do adb exec-out screencap > "$OUT/.raw_$1_$n"; done
  for n in $(seq -w 1 "$2"); do python3 .github/raw2png.py "$OUT/.raw_$1_$n" "$OUT/$1_$n.png"; rm -f "$OUT/.raw_$1_$n"; done
}

# 1. Cold start with the demo car switched on: the splash, then the drive welcome inside the Drive tab
#    (the car's lights come up where the cluster will be, a greeting), then the cluster sweeping awake.
#    Frames throughout (no screen recording here: the app is decoding its own clip, and the emulator does
#    both in software).
adb shell am start -n $PKG/.MainActivity --ez demo true --ez seed_fuel true --es import_path $CSV_PATH
burst start 48
alive "the drive welcome"

# 2. Let the demo drive run into the acceleration and cruise phases.
sleep 14; alive "the first minute of driving"; shot 03_drive_accelerating
# Press a control and catch the light passing across it (the Bluetooth tile just looks for the adapter again).
xy=$(pos_text "BLUETOOTH"); if [ -n "$xy" ]; then adb shell input tap $xy; burst 03c_tile_press 4; fi
sleep 20; shot 04_drive_cruise

# 3. Tab switching: find the tab positions once, then tap and grab frames mid-animation.
TAB_DRIVE=$(pos_text "Drive"); TAB_STATS=$(pos_text "Stats"); TAB_SETTINGS=$(pos_text "Settings"); TAB_CAR=$(pos_text "Car")
echo "tabs: drive=$TAB_DRIVE stats=$TAB_STATS settings=$TAB_SETTINGS"
[ -n "$TAB_STATS" ] && adb shell input tap $TAB_STATS; burst tab_to_stats 4
sleep 1
[ -n "$TAB_SETTINGS" ] && adb shell input tap $TAB_SETTINGS; burst tab_to_settings 4
sleep 1
[ -n "$TAB_DRIVE" ] && adb shell input tap $TAB_DRIVE; burst tab_to_drive 4

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

alive "the map and floating window"
tap_text "Car"; sleep 2; shot 06_car_top
# The location mark beside the connection light: struck through while location is switched off (as from the
# quick settings, with the app in view), plain again once it is back on.
adb shell cmd location set-location-enabled false >/dev/null 2>&1; adb shell settings put secure location_mode 0 >/dev/null 2>&1
sleep 3; shot 06a_location_off
adb shell cmd location set-location-enabled true >/dev/null 2>&1; adb shell settings put secure location_mode 3 >/dev/null 2>&1
sleep 3; shot 06b_location_on
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
# An odometer reading typed while the demo drive is being recorded: the Car tab must show it as typed, not
# with the drive so far added on top.
scroll_to "Odometer now"; type_field "Odometer now" "50000"; hide_keyboard; sleep 1
# By the tab's place: Settings has a "Car" heading of its own.
[ -n "$TAB_CAR" ] && adb shell input tap $TAB_CAR; sleep 2; shot 09g_car_odometer_mid_drive

# 5. The end of a drive. The demo car's ignition goes off with the adapter left plugged in: the drive is
#    parked straight away (PARKED and its countdown arrive), coming back to the app as if after a long time
#    away does not bring the welcome, and when the countdown runs out (100 s in this build) the goodbye
#    plays by itself.
tap_text "Drive"; sleep 2
T0=$(date +%s)
adb shell am start -n $PKG/.MainActivity --ez ignition_off true >/dev/null
burst 10c_parking 24; alive "parking"
echo "parked after $(( $(date +%s) - T0 )) s of frames"
shot 10d_drive_parked
adb shell am start -n $PKG/.MainActivity --ez rearm_intro true >/dev/null
burst 10d1_back_while_parked 10
adb shell cmd statusbar expand-notifications; sleep 2; shot 10a_trip_notification
adb shell cmd statusbar collapse; sleep 1
adb shell settings put system user_rotation 1; sleep 4; shot 10d2_parked_landscape
adb shell settings put system user_rotation 0; sleep 4
# Wait for the countdown to run out, then watch the goodbye (the car's lights go out, the halo comes up).
while [ $(( $(date +%s) - T0 )) -lt 94 ]; do sleep 1; done
burst 10e_goodbye 40; alive "the goodbye"; sleep 4; shot 10f_drive_ended
# The carpooling share, settled on the goodbye: two passengers at Rs 400 each.
tap_text "Add carpooling share"; sleep 2; shot 10f3_share_editor
tap_text "One passenger more"; sleep 0.5
type_field "Share each" "400"; hide_keyboard
tap_text "Save  Rs 800"; sleep 2; shot 10f4_share_saved
adb shell settings put system user_rotation 1; sleep 4; shot 10f2_ended_landscape
adb shell settings put system user_rotation 0; sleep 4
# Coming back after a long time with the goodbye already seen: now the welcome may play.
adb shell am start -n $PKG/.MainActivity --ez rearm_intro true >/dev/null
burst 10g_back_after_goodbye 16
# The demo car stays off for the rest of the walkthrough.
tap_text "Settings"; sleep 2
scroll_tap "Stop demo drive"; sleep 3
# With the demo off, its fault code is gone from the Car tab: no alert line, codes not yet read.
tap_text "Car"; sleep 2; shot 10h_car_after_demo
scroll_to "Fault codes"; adb shell input swipe 540 1500 540 1100 300; sleep 1.5; shot 10i_codes_after_demo
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
# 30 days: too many bars to share the width, so they keep their width and the chart slides sideways.
tap_text "This year"; sleep 1; tap_text "30 days"; sleep 2
adb shell input swipe 540 1600 540 1000 400; sleep 1.5; shot 11g_stats_30_days
xy=$(pos_text "Tap a bar for its figures"); if [ -n "$xy" ]; then set -- $xy; adb shell input swipe 300 $(( $2 + 330 )) 950 $(( $2 + 330 )) 500; fi; sleep 1.5; shot 11h_stats_30_days_earlier
adb shell input swipe 540 1700 540 600 400; sleep 1.5; shot 12_stats_trips
tap_text "Car"; sleep 2
scroll_tap "Fill-ups"; sleep 2; shot 10b_fuel
tap_text "Log a fill-up"; sleep 4; shot 10c_fuel_form
# Log a full tank of 30 L at the price shown, correct it to 32 L, then delete it (tapped twice).
type_field "Litres" "30"; hide_keyboard
scroll_tap "Save"; sleep 2; shot 10c1_fuel_saved
tap_text "Edit"; sleep 1.5; shot 10c2_fuel_editing
type_field "Litres" "32" clear; hide_keyboard
scroll_tap "Save changes"; sleep 2; shot 10c3_fuel_corrected
tap_text "Edit"; sleep 1.5
scroll_tap "Delete this fill-up"; sleep 1; shot 10c4_fuel_delete_confirm
tap_text "Tap again to delete"; sleep 2; shot 10c5_fuel_deleted
# A second full tank, kept: the drives since the first one become a finished tank in Stats.
tap_text "Log a fill-up"; sleep 3
type_field "Litres" "40"; hide_keyboard
scroll_tap "Save"; sleep 2; shot 10c6_fuel_second_full
# One back closes the form; the tab bar takes it from there (two backs could leave the app).
adb shell input keyevent KEYCODE_BACK; sleep 1.5

# 6. Trips: the list, an imported drive's page, then the demo drive's page and route map.
alive "the stats and fill-ups"
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
# Route options are menus: open each by its current value, then pick. Before the marker is placed: its
# readout sits above the options and has an "Economy" of its own.
tap_text "Economy"; sleep 1; tap_text "Speed"; sleep 2; shot 24_route_speed
tap_text "Parula"; sleep 1; tap_text "Red–yellow–green"; sleep 2; shot 23_route_rdylgn
scroll_tap "Esri gray"; sleep 1; tap_text "OpenStreetMap"; sleep 6; shot 22b_route_osm
# Press and hold on the map, then slide: the marker should snap to the route and follow it.
adb shell input swipe 400 800 402 802 1500; sleep 1; shot 21_route_hold
adb shell input swipe 300 700 800 1200 3000; sleep 1; shot 22_route_scrub
adb shell input keyevent KEYCODE_BACK; sleep 1.5
# Back to the list, pick the two newest drives and merge them.
adb shell input keyevent KEYCODE_BACK; sleep 1.5
tap_text "Select"; sleep 1
tap_text "km/L" 0; sleep 0.5; tap_text "km/L" 1; sleep 1; shot 18b_merge_picked
tap_text "Merge 2 drives"; sleep 3; shot 18c_merged
adb shell input keyevent KEYCODE_BACK; sleep 1.5; shot 13c_trips_after_merge

# 6b. Drives the app missed, added by hand. From kilometres: 25 km with 40 minutes' driving, yesterday.
alive "the trips"
tap_text "Add a drive"; sleep 2; shot 27_add_drive_form
tap_text "Kilometres"; sleep 1
type_field "Distance" "25"; hide_keyboard
scroll_to "Time driving (optional)"; type_field "Time driving (optional)" "40"; hide_keyboard
tap_text "Day before"; sleep 1; shot 27a_add_drive_filled
scroll_tap "Save drive"; sleep 3; shot 27b_hand_drive_page
# Its carpooling share, in place on its page: the usual carpool (from the goodbye) is offered first.
scroll_tap "Add carpooling share"; sleep 1.5; shot 27b1_share_editor_remembered
scroll_tap "Save  Rs 800"; sleep 2; shot 27c_hand_drive_shared
# Corrected to 30 km: its fuel is estimated again, and the share stays.
tap_text "More: correct, delete"; sleep 1; tap_text "Correct this drive"; sleep 2; shot 27c1_correct_form
type_field "Distance" "30" clear; hide_keyboard
scroll_tap "Save changes"; sleep 3; shot 27d_hand_drive_corrected
adb shell input keyevent KEYCODE_BACK; sleep 1.5; shot 27e_trips_with_hand_drive
# From odometer readings two days ago: older than the reading in use, which stays. Then deleted.
tap_text "Add a drive"; sleep 2
tap_text "Before and after"; sleep 1
type_field "Odometer before" "10000"; hide_keyboard
type_field "Odometer after" "10012"; hide_keyboard
tap_text "Day before"; sleep 0.5; tap_text "Day before"; sleep 1; shot 28_before_after_form
scroll_tap "Save drive"; sleep 3; shot 28a_before_after_page
tap_text "More: correct, delete"; sleep 1; tap_text "Delete this drive"; sleep 0.8; tap_text "Tap again to delete this drive"; sleep 2
shot 28a1_trips_after_delete
# From the odometer now: the reading the app expects plus 7 km means 7 km were not recorded.
tap_text "Add a drive"; sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
EXP=$(python3 -c "import re; t=open('$OUT/ui.xml').read(); m=re.search(r'expects about ([0-9,]+) km', t); print(m.group(1).replace(',', '') if m else '')")
echo "odometer the app expects: $EXP"
if [ -n "$EXP" ]; then
  type_field "What the odometer shows" "$((EXP + 7))"; hide_keyboard; sleep 1; shot 28b_odometer_now_form
  scroll_tap "Save drive"; sleep 3; shot 28c_odometer_now_page
  adb shell input keyevent KEYCODE_BACK; sleep 1.5
  tap_text "Car"; sleep 2; shot 28d_car_odometer_after
  tap_text "Trips"; sleep 2
fi

# 6c. Stats by the tank: the one in use (since the second full tank), then the one before, with its economy
#     by the pump.
tap_text "Stats"; sleep 2
tap_text "This week"; sleep 1.5; tap_text "This tank"; sleep 2; shot 29_stats_this_tank
tap_text "Previous"; sleep 2; shot 29b_stats_previous_tank
adb shell input swipe 540 1600 540 900 400; sleep 1.5; shot 29c_stats_previous_tank_lower
tap_text "Trips"; sleep 2
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

# 8. The welcome on a light page: Daylight, then a fresh start (the car stands cut out on the page).
adb shell input keyevent KEYCODE_BACK; sleep 1.5
tap_text "Settings"; sleep 2
tap_text "Daylight"; sleep 2
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.MainActivity; burst start_light 40
echo "done"
