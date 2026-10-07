"""Privileged bench scenarios: battery simulation, network impairment, reboots, crashes of other apps, monkey.

Safety model:
  * Plans are printed and nothing changes unless --yes is given.
  * Only serials listed in harness/devices.allow are accepted.
  * Before each state change a restore watchdog is started on the device itself, so it recovers even if the host
    disconnects; the harness also restores in `finally` and on Ctrl-C.
  * Wi-Fi/airplane steps are refused over wireless adb; reboots are capped per device per day.
  * monkey and am crash refuse system, agent and security packages.
"""

import json
import os
import re
import time
from datetime import date

from . import app

PROTECTED = re.compile(r"^(android|com\.android\.(settings|systemui|phone|shell|providers\..*)|com\.google\.android\.(gms|gsf)|"
                       r"com\.airwatch\..*|com\.omnissa\.(hub|workspaceone)\..*|com\.azure\.authenticator|com\.symbol\..*|com\.zebra\..*)$")
MAX_STEP_SECONDS = 1800
DEFAULT_REBOOTS_PER_DAY = 3


class ScenarioError(ValueError):
    pass


def load(path):
    with open(path) as f:
        data = json.load(f)
    steps = data.get("steps") if isinstance(data, dict) else data
    if not isinstance(steps, list) or not steps:
        raise ScenarioError("scenario needs a non-empty 'steps' list")
    for i, step in enumerate(steps):
        validate(step, i)
    return {"name": data.get("name", os.path.basename(path)) if isinstance(data, dict) else os.path.basename(path), "steps": steps}


def _secs(step, key, low=1, high=MAX_STEP_SECONDS, default=None):
    value = step.get(key, default)
    if not isinstance(value, int) or not low <= value <= high:
        raise ScenarioError("%s: %s must be an integer %d..%d" % (step.get("action"), key, low, high))
    return value


def _package(step):
    pkg = step.get("package", "")
    if not re.match(r"^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$", pkg):
        raise ScenarioError("%s: package must be an Android package name" % step.get("action"))
    if PROTECTED.match(pkg):
        raise ScenarioError("%s: refusing protected package %s" % (step.get("action"), pkg))
    return pkg


def validate(step, index=0):
    action = step.get("action")
    if action == "battery-drain":
        start, end = step.get("from", 40), step.get("to", 8)
        if not (isinstance(start, int) and isinstance(end, int) and 0 <= end < start <= 100):
            raise ScenarioError("battery-drain: need 0 <= to < from <= 100")
        _secs(step, "stepSeconds", 5, 600, 30)
    elif action in ("wifi-off", "airplane", "mobile-data-off"):
        _secs(step, "seconds", 5, 600)
    elif action == "wifi-flap":
        _secs(step, "offSeconds", 5, 600)
        _secs(step, "gapSeconds", 5, 1800, 60)
        _secs(step, "times", 1, 10, 1)
    elif action == "reboot":
        pass
    elif action in ("am-crash", "force-stop"):
        _package(step)
    elif action == "monkey":
        _package(step)
        _secs(step, "events", 1, 5000, 500)
        _secs(step, "throttleMs", 50, 5000, 300)
    elif action == "start":
        unknown = set(step) - {"action", "wait"} - set(app.RUN_KEYS)
        if unknown:
            raise ScenarioError("start: unknown keys " + ", ".join(sorted(unknown)))
    elif action == "fault":
        if step.get("fault") not in ("crash", "anr", "native", "handled"):
            raise ScenarioError("fault: fault must be crash, anr, native or handled")
    elif action == "wait":
        _secs(step, "seconds", 1, 8 * 3600)
    elif action == "stop":
        pass
    else:
        raise ScenarioError("step %d: unknown action %r" % (index + 1, action))


def describe(step):
    a = step["action"]
    if a == "battery-drain":
        return "simulate unplugged battery %d%% -> %d%% in 1%% steps every %ds, then reset" % (
            step.get("from", 40), step.get("to", 8), step.get("stepSeconds", 30))
    if a == "wifi-off":
        return "Wi-Fi off for %ds" % step["seconds"]
    if a == "wifi-flap":
        return "Wi-Fi off %ds, %d time(s), %ds apart" % (step["offSeconds"], step.get("times", 1), step.get("gapSeconds", 60))
    if a == "airplane":
        return "airplane mode for %ds" % step["seconds"]
    if a == "mobile-data-off":
        return "mobile data off for %ds" % step["seconds"]
    if a == "reboot":
        return "reboot and wait for boot"
    if a == "am-crash":
        return "crash %s (am crash)" % step["package"]
    if a == "force-stop":
        return "force-stop %s" % step["package"]
    if a == "monkey":
        return "monkey %d events on %s (throttle %dms)" % (step.get("events", 500), step["package"], step.get("throttleMs", 300))
    if a == "start":
        return "start Matrix Rain run " + json.dumps({k: v for k, v in step.items() if k not in ("action", "wait")})
    if a == "fault":
        return "raise %s in Matrix Rain" % step["fault"]
    if a == "wait":
        return "wait %ds" % step["seconds"]
    if a == "stop":
        return "stop the Matrix Rain run"
    return a


class Runner:
    def __init__(self, adb, log=print, sleep=time.sleep, state_dir=None, reboots_per_day=DEFAULT_REBOOTS_PER_DAY):
        self.adb = adb
        self.log = log
        self.sleep = sleep
        self.restores = []
        self.state_dir = state_dir
        self.reboots_per_day = reboots_per_day

    # ---- restore bookkeeping ----

    def _arm(self, restore, max_seconds):
        """Starts the on-device watchdog that applies `restore` if the harness never gets to."""
        self.restores.append((restore, self.adb.detach(restore, max_seconds + 30)))

    def _guard(self, change, restore, max_seconds):
        """Arms a watchdog for `restore`, then applies `change`."""
        self._arm(restore, max_seconds)
        self.adb.shell(change)

    def _restore(self, restore):
        self.adb.shell(restore, check=False)
        for entry in [e for e in self.restores if e[0] == restore]:
            # Restored normally: cancel the watchdog so it cannot fire into a later step.
            self.adb.cancel(entry[1])
            self.restores.remove(entry)

    def restore_all(self):
        for restore, _ in list(reversed(self.restores)):
            self.log("  restoring: " + restore)
            self._restore(restore)

    # ---- steps ----

    def run(self, scenario):
        try:
            for i, step in enumerate(scenario["steps"]):
                self.log("[%d/%d] %s" % (i + 1, len(scenario["steps"]), describe(step)))
                getattr(self, "_" + step["action"].replace("-", "_"))(step)
        finally:
            self.restore_all()

    def _battery_drain(self, step):
        start, end, every = step.get("from", 40), step.get("to", 8), step.get("stepSeconds", 30)
        total = (start - end + 1) * every
        self._arm("dumpsys battery reset", total)
        self.adb.shell("dumpsys battery unplug")
        for level in range(start, end - 1, -1):
            self.adb.shell("dumpsys battery set level %d" % level)
            self.sleep(every)
        self._restore("dumpsys battery reset")

    def _wifi_off(self, step):
        self._require_usb("wifi-off")
        restore = "cmd wifi set-wifi-enabled enabled"
        self._guard("cmd wifi set-wifi-enabled disabled", restore, step["seconds"])
        self.sleep(step["seconds"])
        self._restore(restore)

    def _wifi_flap(self, step):
        for n in range(step.get("times", 1)):
            self._wifi_off({"seconds": step["offSeconds"]})
            if n + 1 < step.get("times", 1):
                self.sleep(step.get("gapSeconds", 60))

    def _airplane(self, step):
        self._require_usb("airplane")
        restore = "cmd connectivity airplane-mode disable"
        self._guard("cmd connectivity airplane-mode enable", restore, step["seconds"])
        self.sleep(step["seconds"])
        self._restore(restore)

    def _mobile_data_off(self, step):
        restore = "svc data enable"
        self._guard("svc data disable", restore, step["seconds"])
        self.sleep(step["seconds"])
        self._restore(restore)

    def _reboot(self, step):
        self._count_reboot()
        self.adb.run("reboot")
        self.adb.run("wait-for-device", timeout=300)
        for _ in range(120):
            if self.adb.shell("getprop sys.boot_completed", check=False).strip() == "1":
                self.log("  boot completed")
                return
            self.sleep(5)
        raise RuntimeError("device did not finish booting within 10 minutes")

    def _am_crash(self, step):
        self.adb.shell("am crash " + step["package"])

    def _force_stop(self, step):
        self.adb.shell("am force-stop " + step["package"])

    def _monkey(self, step):
        self.adb.shell("monkey -p %s -s %d --throttle %d --pct-syskeys 0 --pct-anyevent 0 %d" % (
            step["package"], step.get("seed", 42), step.get("throttleMs", 300), step.get("events", 500)), timeout=3600)

    def _start(self, step):
        app.start(self.adb, **{k: v for k, v in step.items() if k not in ("action", "wait")})
        if step.get("wait"):
            self.sleep(3)
            while app.status(self.adb).get("State") == "RUNNING":
                self.sleep(10)

    def _stop(self, step):
        app.stop(self.adb)

    def _fault(self, step):
        app.fault(self.adb, step["fault"])

    def _wait(self, step):
        self.sleep(step["seconds"])

    def _require_usb(self, action):
        if self.adb.wireless:
            raise ScenarioError("%s would disconnect a wireless adb session; connect over USB" % action)

    def _count_reboot(self):
        if not self.state_dir:
            return
        os.makedirs(self.state_dir, exist_ok=True)
        path = os.path.join(self.state_dir, "reboots.json")
        try:
            with open(path) as f:
                counts = json.load(f)
        except (OSError, ValueError):
            counts = {}
        key = "%s/%s" % (self.adb.serial, date.today().isoformat())
        if counts.get(key, 0) >= self.reboots_per_day:
            raise ScenarioError("reboot cap reached: %d today for %s" % (self.reboots_per_day, self.adb.serial))
        counts[key] = counts.get(key, 0) + 1
        with open(path, "w") as f:
            json.dump(counts, f, indent=1)
