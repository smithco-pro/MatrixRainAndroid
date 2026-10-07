"""Evidence collection after a run: what the device itself recorded, to compare with UEM / Intelligence."""

import json
import os
import re
import time

from . import app
from .adb import PACKAGE


def usage_for(adb, packages):
    """Today's foreground time and launches per package from `dumpsys usagestats` (daily interval)."""
    out = adb.shell("dumpsys usagestats --hide-events 2>/dev/null || dumpsys usagestats", check=False, timeout=180)
    daily = out.split("In-memory daily stats", 1)[-1].split("In-memory weekly stats", 1)[0]
    rows = {}
    for match in re.finditer(r'package=(\S+) totalTimeUsed="([^"]+)".*?appLaunchCount=(\d+)', daily):
        pkg, used, launches = match.groups()
        if pkg in packages and pkg not in rows:
            rows[pkg] = {"foreground": used, "launches": int(launches)}
    return rows


def dropbox(adb, since_epoch=None):
    out = adb.shell("dumpsys dropbox", check=False)
    entries = []
    for line in out.splitlines():
        m = re.match(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (data_app_\w+|SYSTEM_TOMBSTONE\w*|system_app_\w+)", line.strip())
        if m:
            entries.append({"time": m.group(1), "tag": m.group(2)})
    return entries


def collect(adb, out_dir, packages=()):
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    packages = set(packages) | {PACKAGE}
    report = {
        "serial": adb.serial,
        "collectedLocal": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
        "model": adb.getprop("ro.product.model"),
        "android": adb.getprop("ro.build.version.release"),
        "appVersion": app.installed_version(adb),
        "status": _safe(lambda: app.status(adb)),
        "lastResult": _safe(lambda: app.last_result(adb)),
        "usageToday": _safe(lambda: usage_for(adb, packages)),
        "dropbox": _safe(lambda: dropbox(adb)[-30:]),
        "battery": adb.shell("dumpsys battery", check=False),
        "deviceidle": adb.shell("dumpsys deviceidle | grep -E 'mState=|mLightState=|Whitelist'", check=False),
    }
    batterystats = adb.shell("dumpsys batterystats --charged %s" % PACKAGE, check=False, timeout=180)
    base = os.path.join(out_dir, "%s-%s" % (adb.serial, stamp))
    with open(base + ".json", "w") as f:
        json.dump(report, f, indent=1)
    with open(base + "-batterystats.txt", "w") as f:
        f.write(batterystats)
    with open(base + ".md", "w") as f:
        f.write(summary(report))
    return base


def summary(report):
    lines = ["# Matrix Rain collection: %s (%s, Android %s)" % (report["serial"], report["model"], report["android"]), ""]
    st = report.get("status") or {}
    lr = report.get("lastResult") or {}
    lines.append("- App version: %s" % report.get("appVersion"))
    lines.append("- Current: %s / %s / %s" % (st.get("Mode"), st.get("State"), st.get("Detail")))
    lines.append("- Last result: run %s %s / %s / %s" % (str(lr.get("RunId", ""))[:8], lr.get("Mode"), lr.get("State"), lr.get("Detail")))
    for p in lr.get("Phases") or []:
        lines.append("  - %s %s -> %s" % (p.get("Phase"), p.get("StartedUtc"), p.get("EndedUtc")))
    lines += ["", "## Usage today (device usagestats)", "", "| Package | Foreground | Launches |", "|---|---|---|"]
    for pkg, row in sorted((report.get("usageToday") or {}).items()):
        lines.append("| %s | %s | %s |" % (pkg, row["foreground"], row["launches"]))
    lines += ["", "## Recent dropbox entries (crashes, ANRs, tombstones)", ""]
    for e in report.get("dropbox") or []:
        lines.append("- %s %s" % (e["time"], e["tag"]))
    lines += ["", "Compare these with UEM device details and Intelligence DEX for the same device and UTC phase times.", ""]
    return "\n".join(lines)


def _safe(fn):
    try:
        return fn()
    except Exception as e:  # collection is best effort; record why a section is missing
        return {"error": str(e)}
