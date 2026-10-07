# mrctl: Matrix Rain bench harness

Python 3, standard library only. Finds adb via `$ADB`, `PATH`, `~/Downloads/platform-tools` or the Android SDK.

```sh
harness/mrctl.py --help
python3 -m unittest discover -s harness/tests
```

## Safety

- **Allowlist:** device-changing commands (`provision`, `unprovision`, `start`, `stop`, `fault`, `scenario run`) act
  only on serials listed in `harness/devices.allow`, which is gitignored. Copy `devices.allow.example`.
- **Dry run by default:** `provision`, `unprovision` and `scenario run` print their plan and change nothing until
  `--yes` is given.
- **Restores that survive the host:** before changing battery, Wi-Fi, airplane mode or mobile data, the harness starts
  a detached watchdog on the device that restores the setting after the step's duration plus 30 s. A normal restore
  cancels the watchdog. Verified: with the harness killed by SIGKILL mid-step, Wi-Fi came back on its own.
- **Guards:** Wi-Fi and airplane steps are refused over wireless adb, since they would cut the session. Reboots are
  capped at 3 per device per day (`harness/out/reboots.json`). `monkey`, `am-crash` and `force-stop` refuse system,
  Settings, Hub/agent, authenticator and Zebra/Symbol packages.
- `provision` records what it changed in `harness/out/provision-<serial>.json`; `unprovision` restores it.

## Scenario steps

| Step | Keys | Effect |
|---|---|---|
| `battery-drain` | `from` (40), `to` (8), `stepSeconds` (30) | `dumpsys battery unplug`, then `set level` down 1 % per step, then `reset` |
| `wifi-off` | `seconds` (5–600) | Wi-Fi off, then on |
| `wifi-flap` | `offSeconds`, `times` (1–10), `gapSeconds` | Repeated `wifi-off` |
| `airplane` | `seconds` | Airplane mode on, then off (device policy may refuse it) |
| `mobile-data-off` | `seconds` | `svc data disable`, then enable |
| `reboot` | | `adb reboot`, waits for `sys.boot_completed` |
| `am-crash`, `force-stop` | `package` | Crash or stop another app |
| `monkey` | `package`, `events` (500), `throttleMs` (300), `seed` (42) | Random input in one app; system keys disabled |
| `start` | run options, `wait` | Starts a Matrix Rain run; with `wait`, blocks until it ends |
| `stop`, `fault`, `wait` | `fault` / `seconds` | |

Notes:
- Simulated battery is what the framework reports (`BatteryManager`, broadcasts); hardware current readings are not
  simulated.
- After a reboot, a device with a secure lock screen stays locked until someone unlocks it, and Matrix Rain does not
  start until then.

## Collection

`mrctl collect -s SERIAL [--package PKG ...]` writes `harness/out/<serial>-<time>.{json,md}` and a batterystats dump:
the app status and last result with UTC phase times, today's foreground time and launches from `usagestats`, recent
dropbox entries (crashes, ANRs, tombstones), battery and Doze state. Compare these with UEM and Intelligence for the
same device and times.
