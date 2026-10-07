# Workloads

Every run is an optional baseline phase followed by the workload, under one run ID, one at a time per device. Status
fields match MatrixRain's `run.json` (`RunId`, `State`, `Phase`, `PhaseIndex`/`PhaseCount`, `PhaseSeconds`,
`PlannedSeconds`, `ElapsedSeconds`, `Detail`, `Result`, `Phases[]`), plus `HeartbeatAgeSeconds` and `Stale` (no
heartbeat for 20 s) when read through the status provider. States: RUNNING, IDLE (stopped or safety stop), COMPLETED,
FAILED.

## Options

| Option | Range | Default (shell/UEM) | Default (display) | Applies to |
|---|---|---|---|---|
| `seconds` | 1–3600; workday 60–28800 | 600 | 600; workday 21600 | all |
| `baselineSeconds` | 0–600; total ≤ 3600 (workday ≤ 29400) | 0 | 60 | all but baseline |
| `intensity` | 1–95 % | 50 | 50 | cpu, science, memory, disk, network |
| `threads` | 1–32, capped at core count | 2 | 2 | cpu, science |
| `memoryMiB` | 8–4096 | 512 | 512 | memory |
| `diskMiB` | 4–256 | 64 | 64 | disk |
| `writeLimitMiB` | diskMiB–4096 | 1024 | 1024 | disk |
| `diskMiBPerSecond` | 1–256 | 10 | 10 | disk |
| `downloadMiB` | 0–256 | 0 | 0 | network |

## Modes

**baseline, visualization.** No load. Baseline turns the rain off, as on Windows.

**cpu, science.** Each thread works for `intensity` ms of every 100 ms slice. Science runs a Monte-Carlo pi estimate
with MatrixRain's seed formula and reports pi. Targets CPU time and battery drain attributed to Matrix Rain.

**memory.** Allocates in 8 MiB blocks of anonymous shared memory and re-touches every page; intensity sets the touch
frequency. Reserve: at least 512 MiB, 20 % of RAM, or twice the low-memory threshold stays available, or the run fails
and frees its memory.

**disk.** Synchronous writes to a scratch file in app storage, paced at `diskMiBPerSecond × intensity`, then read back,
until the time limit or write budget. Requires 2 GiB free. Scratch files are removed afterwards and at the next start.

**workday.** A seeded schedule ported from the Office workday: setup at 0 s, seven opening actions at 5–35 s, then
one action every 30–120 s; a 10–20 minute break first at 70 min and then every 90 min; and the restart-apps slot every
90 min (skipped on the device; closing other apps needs adb). Actions:

| Action | Android behaviour |
|---|---|
| browse | Opens the next site in Chrome (or the default browser) |
| open-app | Brings the next app from `apps` to the foreground |
| create-text / create-csv / edit-file | Writes run-owned TXT/CSV files (4–512 KiB; 12 per type; 64 MiB per run) |
| open-file | Opens one of them in an installed viewer through a read-only content URI |
| return-home / break | Brings the rain display back to the front |

Each app or page stays in front until the next action, so foreground time and launch counts accrue to it. Nothing is
typed or tapped inside other apps. The Workday pauses while the screen is off or locked, and for 15 minutes after a
person uses the device (needs usage access). Actions due during a pause are skipped, not replayed (except the opening
burst), and the deadline keeps running.

**network.** HTTPS GETs (first 256 KiB) across the sites list, every 10 s at 1 % intensity, 5 s at 50 % and 1 s at
90 % or more, reporting average and worst latency and failures. With `downloadMiB > 0` and a configured
`downloadUrl`, a bulk download every 60 s, within the daily data budget and only on unmetered networks unless
allowed.

## Faults

`crash`, `anr` and `native` happen in Matrix Rain's separate `:crashlab` process, so a running workload survives. They
produce `data_app_crash`, `data_app_anr` and `data_app_native_crash` plus a tombstone. `handled` creates an exception
for the Intelligence SDK seam. Limits: 6 per rolling hour, and none for 35 s after an ANR.

## Safety stops (all modes)

The run ends as IDLE with `safety stop: ...` when the thermal status reaches SEVERE, or when the battery is below
`minBatteryPercent` (default 30 %) while unplugged. The battery-drain scenario triggers the battery stop by design;
set `minBatteryPercent=0` if a workload should keep running through it.

## Bench scenarios (harness)

`harness/scenarios/*.json` steps: `battery-drain`, `wifi-off`, `wifi-flap`, `airplane`, `mobile-data-off`, `reboot`,
`am-crash`, `force-stop`, `monkey`, `start`, `stop`, `fault`, `wait`. See `harness/README.md`.
