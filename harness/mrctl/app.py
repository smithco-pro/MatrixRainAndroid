"""Control of the Matrix Rain app over adb: start/stop/status/fault and provisioning grants."""

import json
import shlex

from .adb import PACKAGE, SERVICE, STATUS_URI, AdbError

RUN_KEYS = {
    "mode": "es", "profile": "es", "seconds": "ei", "baselineSeconds": "ei", "intensity": "ei", "threads": "ei",
    "memoryMiB": "ei", "diskMiB": "ei", "writeLimitMiB": "ei", "diskMiBPerSecond": "ei", "downloadMiB": "ei",
}


def _extras(values):
    parts = []
    for key, value in values.items():
        if value is None:
            continue
        kind = RUN_KEYS.get(key)
        if kind is None:
            raise ValueError("unknown run option: " + key)
        parts += ["--" + kind, key, shlex.quote(str(value))]
    return " ".join(parts)


def start(adb, command_id=None, **values):
    extras = _extras(values)
    if command_id:
        extras += " --es commandId " + shlex.quote(command_id)
    adb.shell("am start-foreground-service -n %s -a %s.START %s" % (SERVICE, PACKAGE, extras))


def stop(adb, run_id=None):
    extra = " --es runId " + shlex.quote(run_id) if run_id else ""
    adb.shell("am start-foreground-service -n %s -a %s.STOP%s" % (SERVICE, PACKAGE, extra))


def fault(adb, kind):
    if kind not in ("crash", "anr", "native", "handled"):
        raise ValueError("fault must be crash, anr, native or handled")
    adb.shell("am start-foreground-service -n %s -a %s.FAULT --es fault %s" % (SERVICE, PACKAGE, kind))


def read(adb, path):
    out = adb.shell("content read --uri %s/%s" % (STATUS_URI, path), check=False)
    if "Error while accessing provider" in out or "Unknown authority" in out:
        raise AdbError("status provider unavailable (is Matrix Rain installed?): " + out.strip().splitlines()[0])
    return out


def status(adb):
    return json.loads(read(adb, "status"))


def last_result(adb):
    return json.loads(read(adb, "last-result") or "{}")


def error(adb, command_id):
    out = adb.shell("content read --uri %s/error/%s" % (STATUS_URI, shlex.quote(command_id)), check=False)
    return None if "FileNotFoundException" in out else out.strip()


def installed_version(adb):
    out = adb.shell("dumpsys package %s | grep -m1 versionName" % PACKAGE, check=False).strip()
    return out.split("=", 1)[1] if "=" in out else None


def provision_steps(adb, apk=None, usage_access=False, battery_exempt=False, stay_awake=False):
    """Returns the (description, command, restore) steps provisioning would run. restore is None when not reversible
    or not needed."""
    steps = []
    if apk:
        steps.append(("install " + apk, ("install", apk), None))
    steps.append(("allow 'Display over other apps' (Workday foreground launches, HUD)",
                  "appops set %s SYSTEM_ALERT_WINDOW allow" % PACKAGE, "appops set %s SYSTEM_ALERT_WINDOW default" % PACKAGE))
    steps.append(("grant notifications (run status notification)",
                  "pm grant %s android.permission.POST_NOTIFICATIONS" % PACKAGE, None))
    if usage_access:
        steps.append(("allow usage access (Workday pauses when a person takes over)",
                      "appops set %s GET_USAGE_STATS allow" % PACKAGE, "appops set %s GET_USAGE_STATS default" % PACKAGE))
    if battery_exempt:
        steps.append(("exempt from battery optimization",
                      "dumpsys deviceidle whitelist +%s" % PACKAGE, "dumpsys deviceidle whitelist -%s" % PACKAGE))
    if stay_awake:
        previous = adb.shell("settings get global stay_on_while_plugged_in").strip() or "0"
        steps.append(("keep screen on while plugged in (AC/USB/wireless)", "settings put global stay_on_while_plugged_in 7",
                      "settings put global stay_on_while_plugged_in " + previous))
    return steps
