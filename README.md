# Matrix Rain for Android

An Android port of [Matrix Rain](../MatrixRain) (Windows 2.6.0). It makes an idle lab device generate realistic usage
for Workspace ONE UEM and Omnissa Intelligence DEX: synthetic load, a launch-and-dwell workday across real apps and
websites, network probes, and on-demand fault events, all shown on a "matrix rain" status display.

> **Synthetic data.** Intelligence cannot tell these events from real ones. Run only on lab devices that are tagged in
> UEM (for example `DEX-Synthetic`) and filter on that tag in reports.

## How it is built

Two layers, each usable without the other:

| Layer | What it does | Needs |
|---|---|---|
| **On-device app** (`app/`, `core/`) | Runs every workload as a foreground service; UEM deploys it and controls it with managed config; shows the rain display | Nothing beyond install (Workday also needs "Display over other apps") |
| **Bench harness** (`harness/mrctl.py`) | Provisioning, start/stop/status over adb, and privileged scenarios only the shell user can perform: simulated battery drain, Wi-Fi/airplane/mobile-data drops, reboots, crashing other apps, monkey | USB debugging on a lab device |

The app is the primary engine because only it scales to a fleet, runs untethered, and has its load and faults
attributed to an app package (adb-only load is charged to the `shell` user and never appears in app rankings). The
harness adds what Android reserves for the shell. See [docs/SPIKE-RESULTS.md](docs/SPIKE-RESULTS.md) for the
on-device evidence behind these choices.

```
core/      Platform-neutral Kotlin: request validation, engine (CPU/science/memory/disk), Workday planner and driver,
           network driver, run store, single-run coordinator, managed-config policy. JVM unit tests.
app/       Android app: WorkloadService, status provider, managed config, Workday actions, faults (:crashlab), UI.
harness/   mrctl (Python 3 stdlib): provision, control, scenarios, collection. Unit tests with a fake adb.
spike/     Phase 0 feasibility APKs (throwaway, kept so the results can be reproduced).
docs/      UEM setup, workload reference, spike results.
```

## Workloads

| Mode | What runs | Ported from |
|---|---|---|
| `baseline` | Nothing; heartbeats only. Rain off. | Baseline |
| `visualization` | Rain display only | Visualization |
| `cpu` / `science` | Duty-cycled threads (1–95 %); science estimates pi | Engine.cs |
| `memory` | 8–4096 MiB of off-heap shared memory, pages re-touched | Engine.cs |
| `disk` | Paced scratch-file writes/reads under a write budget | Engine.cs |
| `workday` | 1 min–8 h seeded schedule: Chrome pages, app launches, TXT/CSV create/edit/open, return to display, breaks | Office workday |
| `network` | HTTPS latency probes; optional bulk downloads within a daily budget | new |
| faults | crash, ANR, native crash, handled exception in Matrix Rain itself | new |

Details, parameters and safety stops: [docs/WORKLOADS.md](docs/WORKLOADS.md).

## Build

Requires Android Studio's JDK (21) and SDK platform 36.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:test                 # JVM unit tests (ported MatrixRain scenarios)
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease       # signed when MATRIXRAIN_KEYSTORE* properties are set (see app/build.gradle.kts)
python3 -m unittest discover -s harness/tests
```

## Quick start on a bench device

```sh
cp harness/devices.allow.example harness/devices.allow      # list your lab device serials
harness/mrctl.py devices
harness/mrctl.py provision -s SERIAL --apk app/build/outputs/apk/debug/app-debug.apk --usage-access          # dry run
harness/mrctl.py provision -s SERIAL --apk app/build/outputs/apk/debug/app-debug.apk --usage-access --yes
harness/mrctl.py start -s SERIAL --mode cpu --seconds 600 --baseline-seconds 60
harness/mrctl.py status -s SERIAL --watch 5
harness/mrctl.py stop -s SERIAL
harness/mrctl.py scenario run harness/scenarios/battery-critical.json -s SERIAL           # prints the plan
harness/mrctl.py collect -s SERIAL --package com.android.chrome
```

Plain adb works too:

```sh
adb shell am start-foreground-service -n com.aftersix.matrixrain/.service.WorkloadService \
    -a com.aftersix.matrixrain.START --es mode cpu --ei seconds 600
adb shell content read --uri content://com.aftersix.matrixrain.status/status
adb shell am start-foreground-service -n com.aftersix.matrixrain/.service.WorkloadService -a com.aftersix.matrixrain.STOP
```

The service and status provider are guarded by `android.permission.DUMP`, which only the shell and system hold, so
other apps cannot drive them.

## Fleet deployment

Upload the release APK to UEM as an internal app and control it with managed config: set `profile` (Node01–Node06
presets) or `mode` and options, set `command=start`, and change `commandId`. See
[docs/UEM-SETUP-ANDROID.md](docs/UEM-SETUP-ANDROID.md).

## Status

| Phase | State |
|---|---|
| 0 Feasibility spike | Done on Zebra TC26 (Android 14) and Pixel 8a (Android 17); see spike results |
| 1 Core and engines | Done; verified on Pixel 8a and TC26, including under forced Doze |
| 2 Display | Done; checked on the TC26 by screenshot (rain, readings, status, controls) |
| 3 UEM managed config | Done; verified on Pixel via the debug-only config injection; not yet pushed from a UEM console |
| 4 Workday | Done; foreground rotation verified on the TC26 (all actions on schedule); pause logic verified on the locked Pixel |
| 5 Faults, network, Intelligence | Faults and network verified on Pixel; Intelligence SDK not embedded (seam only, see below) |
| 6 Harness | Done; battery, Wi-Fi and host-killed recovery verified on Pixel; reboot not run live |
| 7 Pilot | Not started |

**Intelligence SDK:** `app/.../telemetry/Telemetry.kt` defines the `Intel` seam (breadcrumbs with run IDs, handled
exceptions, user flows) with a no-op implementation. The SDK artifact coordinates, init API and an app ID from your
Intelligence tenant are needed to implement it; they are not in this repo (Data unavailable).
