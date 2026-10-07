# Phase 0 feasibility results

Tested 2026-10-06 with the throwaway `spike/` and `spikeprobe/` APKs and the app itself.

| Device | Android | Management at test time |
|---|---|---|
| Zebra TC26 | 14 (SDK 34) | Intelligent Hub 26.08.0.128 is Device Owner (affiliated); Hub lockdown launcher on its shared-device check-out screen |
| Google Pixel 8a | 17 (SDK 37) | Hub 26.08.0.128 installed, no device owner (appears unenrolled); secure keyguard locked |

## Results

| # | Question | Result | Evidence |
|---|---|---|---|
| a | Does a `specialUse` foreground service keep running with the screen off? | **Yes.** No wake lock, 10 s heartbeat: TC26 63 min, worst gap 10.26 s; Pixel 59 min with the screen off and locked throughout, worst gap 10.02 s. Under forced deep Doze with the battery simulated as unplugged, a 150 s CPU run completed with fresh heartbeats and the spike service missed no beats. | `dumpsys activity service`: beats = expected + 1; `dumpsys deviceidle force-idle deep` |
| b | Can the app bring other apps to the foreground from the background? | **Only with "Display over other apps".** Without it every launch is blocked. With it, launches are allowed on Android 17 even with no overlay visible (`BAL_ALLOW_SAW_PERMISSION`); with a visible overlay on Android 14 (`BAL_ALLOW_VISIBLE_WINDOW`). | `ActivityTaskManager: Background activity launch blocked` / `START ... (BAL_ALLOW_SAW_PERMISSION) result code=0` |
| c | Does the TC26's Hub lockdown launcher allow the rotation? | **Not in check-out state.** The device is in lock-task mode (`mLockTaskModeState=LOCKED`); every start, including `am start` from adb, returns 101 (`START_RETURN_LOCK_TASK_MODE_VIOLATION`). Once the device left lock-task mode, a Workday ran all its actions: Chrome, Slack, a file viewer, Horizon client and the display each came to the foreground on schedule. | `dumpsys activity activities` → LockTaskController; sampled `topResumedActivity` every 5 s |
| d | Are the shell-only control surfaces closed to other apps? | **Yes.** An ordinary app's `startService` gets `SecurityException`; its broadcast is never delivered. | `Permission Denial: ... requires android.permission.DUMP` |
| e | Do crashes/ANRs in `:crashlab` reach dropbox while the main process survives? | **Yes**, on both. Native crash via `Os.kill(SIGSEGV)` writes a tombstone. | `data_app_crash`, `data_app_anr`, `data_app_native_crash`, `SYSTEM_TOMBSTONE`; main PID unchanged |
| f | Does the data reach UEM / Intelligence? | **Data unavailable.** Needs a day of ingestion on an enrolled device. Pixel must be enrolled first. | — |

## Findings that changed the design

1. **Background foreground-service starts.** A managed-config command evaluated from a JobScheduler job was refused on
   Android 17 (`startForegroundService() not allowed`), with or without the overlay permission. Evaluating it inside an
   exact-alarm receiver is allowed (`Background started FGS: Allowed code:ALARM_MANAGER_WHILE_IDLE`), with no overlay
   permission and no battery exemption. The config poll is therefore a 15-minute exact-alarm chain
   (`USE_EXACT_ALARM`), and config changes schedule an immediate alarm.
2. **Memory workload.** On ART, `ByteBuffer.allocateDirect` comes out of the Java heap (256 MiB limit on the Pixel), so
   a 512 MiB run hit `OutOfMemoryError` and killed the process. The memory workload now uses `SharedMemory`
   (Ashmem, outside the heap but charged to the app: 512 MiB Ashmem, PSS ≈ 545 MB), and any `Throwable` in a run is
   reported as FAILED instead of crashing.
3. **ANR blocks `:crashlab`.** A fault sent while an ANR is in progress queues behind it and is lost when the system
   kills the process. Faults are refused for 35 s after an ANR.
4. **Locked devices.** Activities launched behind a secure keyguard count as launches but accrue no foreground time.
   Workday pauses while the screen is off or the keyguard is up, and its HUD overlay keeps the screen on while other
   apps are in front.
5. **First-run screens.** Chrome showed its first-run screen on both devices, and the Horizon client opened a
   permission prompt that stayed in front until the next action. Complete each rotated app's first-run once per
   device (or list only set-up apps in `apps`), or the Workday measures those screens instead of the app.
6. **No `curl` on the Pixel.** The harness uses only `am`, `cmd`, `dumpsys`, `settings`, `svc` and `content`.
7. **Viewer chooser.** With several apps able to open text and no default, Android showed its chooser. Workday now
   picks a viewer itself (seeded), so files open directly.
