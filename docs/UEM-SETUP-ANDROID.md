# Deploying Matrix Rain for Android with Workspace ONE UEM

Matrix Rain is deployed as an internal app and controlled entirely through its managed configuration (Android
Enterprise app config). This replaces the Windows package's install scripts, UAC gate and Freestyle Node scripts.

## 1. Build and sign

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export MATRIXRAIN_KEYSTORE=/secure/path/matrixrain.jks MATRIXRAIN_KEYSTORE_PASSWORD=... MATRIXRAIN_KEY_ALIAS=... MATRIXRAIN_KEY_PASSWORD=...
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Keep the keystore out of the repo and reuse it for every version: Android only installs an update signed with the
same key. Increase `versionCode` in `app/build.gradle.kts` for each upload.

## 2. Add the app

1. **Apps & Books > Native > Internal > Add Application**, upload `app-release.apk`.
2. Assign it to a smart group containing only lab devices, ideally selected by a tag such as `DEX-Synthetic`.
3. Deployment: Automatic. The app does nothing until it receives a command.

## 3. Managed configuration keys

The schema ships in the APK (`app/src/main/res/xml/app_restrictions.xml`), so the UEM console shows these fields.
Integers left at `-1` and empty strings mean "not set".

| Key | Type | Default | Purpose |
|---|---|---|---|
| `enabled` | bool | true | Kill switch. Off stops the current run and refuses starts from UEM, adb and the display |
| `allowLocalControl` | bool | true | Off makes the display's Go/Stop read-only |
| `labTitle` | string | AFTER SIX LAB | Header text on the display |
| `minBatteryPercent` | int | 30 | Runs stop early below this level while unplugged |
| `command` | choice | none | `start`, `stop` or `fault`, executed once per `commandId` |
| `commandId` | string | | Change to a new value (a timestamp works) to execute `command` |
| `profile` | choice | | `node01`–`node06`: Baseline, Visualization, CPU, Memory, Disk, Science (600 s, no baseline) |
| `mode` | choice | | baseline, visualization, cpu, memory, disk, science, workday, network |
| `seconds`, `baselineSeconds`, `intensity`, `threads`, `memoryMiB`, `diskMiB`, `writeLimitMiB`, `diskMiBPerSecond`, `downloadMiB` | int | -1 | Same ranges as the Windows worker; see WORKLOADS.md |
| `sites` | string | | Workday/network HTTPS URLs, one per line or comma-separated. Empty: the built-in 8 sites |
| `apps` | string | | Workday package names, comma-separated. Empty: installed apps from the built-in list |
| `downloadUrl` | string | | Network bulk-download source. Must be HTTPS and a file you own or may load-test |
| `dailyDataBudgetMiB` | int | 512 | Shared daily allowance for bulk downloads |
| `allowMeteredData` | bool | false | Allow bulk downloads on metered networks |
| `fault` | choice | | `crash`, `anr`, `native`, `handled` for `command=fault`; at most 6 per hour |

**Starting a run, the Node03 equivalent:** set `profile=node03`, `command=start`, `commandId=2026-10-07T09-00`, and
save. To run it again, change only `commandId`.

**When it takes effect:** immediately if the app is running; otherwise at the next 15-minute poll, at boot, or after
an app update. Starts are made from an exact-alarm callback, which Android allows to start a foreground service from
the background (verified on Android 17), so no battery-optimization exemption is needed.

**Outcome:** `files/workloads/managed-config.json` records the last trigger and outcome. Rejected commands are kept in
`error-<commandId>.txt`. On a bench device: `adb shell content read --uri content://com.aftersix.matrixrain.status/error/<commandId>`.

## 4. Device prerequisites

| Need | For | How |
|---|---|---|
| "Display over other apps" | Workday (bringing apps forward, HUD that keeps the screen on) | Settings > Apps > Matrix Rain > Display over other apps. Bench: `mrctl provision`. Fleet: an Android Enterprise DPC cannot grant this special access through the standard permission policy; on Zebra devices MX Access Manager may be able to (assumption, not verified). |
| Usage access (optional) | Workday pauses when a person takes over the device | Settings > Special app access > Usage access. Bench: `mrctl provision --usage-access` |
| Chrome set up | Workday browse actions | Complete Chrome's first-run screens once per device |
| Not in kiosk lock-task mode | Workday | On devices with the Hub lockdown launcher, check the device out and add Matrix Rain and the rotated apps to the launcher profile. In the check-out screen every launch is refused (observed on a TC26) |
| Enrolled in UEM | Hub/Intelligence data | Unenrolled devices produce local usage only |

## 5. Seeing the data

- **Intelligence:** Hub for Android embeds the Intelligence SDK and reports device telemetry (app usage, battery,
  network, reboots) when Mobile/DEX collection is enabled for the tenant. Matrix Rain appears as an app in usage
  rankings. Crash and ANR records for Matrix Rain itself appear only if the Intelligence SDK is embedded in Matrix
  Rain (not in this build; see README).
- **Correlate** by device, run ID and the UTC phase times in `last-result.json` (`mrctl collect` writes them with
  the device's own usage and dropbox records).
- **Ingestion timing:** Hub app-usage metrics are checked every 10 minutes and reported daily (per the SDK docs), so
  look the next day.

## 6. Removing it

Unassign the app. Run history lives in the app's private storage and is removed with it.
