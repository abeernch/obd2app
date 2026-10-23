# Civic OBD

Personal Android OBD2 app for a 2005 Honda Civic (1.5 L, manual, K-line).
Reads an ELM327 adapter, logs every drive automatically and shows derived metrics.

## Install on the phone

1. Open the repository's **Releases** page on the phone and download `civic-obd.apk` from the release named **latest**.
2. Open the file and allow installs from this source when Android asks.
3. New builds install over the old one. Nothing is lost.

## Build

Every push to `main` builds the APK with GitHub Actions (`.github/workflows/build.yml`).
No local Android Studio is needed. To build locally, open the folder in Android Studio.

## Layout

| Path | What it holds |
| --- | --- |
| `obd/Transport.kt` | Bluetooth link to the adapter |
| `obd/ElmSession.kt` | One command at a time, waits for the `>` prompt, flags ELM errors |
| `obd/Pids.kt` | Mode 01 PID formulas and supported-PID decoding |
| `ui/Components.kt` | Rolling numbers, status light, line icons |
| `ui/Theme.kt` | Colours and the B612 typeface |
| `service/ObdService.kt` | Background connection, auto-connect, trips, 1 Hz recording |
| `core/MetricEngine.kt` | Fuel model, gear, averages, acceleration, power |
| `core/TripStore.kt`, `core/Recordings.kt` | Local database of trips and recorded samples |
| `core/CsvFiles.kt` | Car Scanner CSV import, CSV export |
| `ui/Chart.kt`, `ui/LogsScreen.kt` | Interactive charts: marker, pinch zoom, combined or separate |

## Phases

1. Setup and build pipeline
2. Connection probe
3. Logging core, auto-connect, automatic trips
4. Drive screens and fuel model
5. History, calibration, home-screen widget, fuel price lookup
6. Health, gearbox, HUD, BLE and Wi-Fi adapters

B612 font: SIL Open Font License, https://github.com/polarsys/b612
