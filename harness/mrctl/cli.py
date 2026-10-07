"""mrctl: bench harness for Matrix Rain on Android.

  mrctl devices
  mrctl provision -s SERIAL [--apk app.apk] [--usage-access] [--battery-exempt] [--stay-awake] [--yes]
  mrctl unprovision -s SERIAL [--yes]
  mrctl start -s SERIAL --mode cpu --seconds 600 [--baseline-seconds 60 ...] | --profile node03
  mrctl stop -s SERIAL [--run-id ID]
  mrctl status -s SERIAL [--watch SECONDS]
  mrctl fault -s SERIAL crash|anr|native|handled
  mrctl scenario check FILE
  mrctl scenario run FILE -s SERIAL [-s SERIAL ...] [--yes]
  mrctl collect -s SERIAL [--out DIR] [--package PKG ...]

Device-changing commands (provision, unprovision, start, fault, scenario run) only act on serials listed in
harness/devices.allow. provision, unprovision and scenario run print their plan and change nothing without --yes.
"""

import argparse
import json
import os
import sys
import threading
import time
import uuid

from . import app, collect, scenario
from .adb import Adb, AdbError, list_devices

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOW_FILE = os.environ.get("MRCTL_ALLOW", os.path.join(HERE, "devices.allow"))
OUT_DIR = os.path.join(HERE, "out")


def allowed_serials(path=None):
    try:
        with open(path or ALLOW_FILE) as f:
            return {line.split("#", 1)[0].strip() for line in f if line.split("#", 1)[0].strip()}
    except OSError:
        return set()


def require_allowed(serials):
    allow = allowed_serials()
    missing = [s for s in serials if s not in allow]
    if missing:
        raise SystemExit("refusing: %s not listed in %s (one lab-device serial per line)" % (", ".join(missing), ALLOW_FILE))


def parallel(serials, fn):
    """Runs fn(adb) on each serial in its own thread; prints per-device failures and returns the failure count."""
    failures = []

    def worker(serial):
        try:
            fn(Adb(serial))
        except Exception as e:
            failures.append(serial)
            print("[%s] FAILED: %s" % (serial, e), file=sys.stderr)

    threads = [threading.Thread(target=worker, args=(s,)) for s in serials]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return len(failures)


def cmd_devices(args):
    allow = allowed_serials()
    for d in list_devices():
        line = "%-22s %-12s %-14s allowed=%s" % (d["serial"], d["state"], d["model"], "yes" if d["serial"] in allow else "no")
        if d["state"] == "device":
            adb = Adb(d["serial"])
            line += " android=%s app=%s" % (adb.getprop("ro.build.version.release"), app.installed_version(adb) or "-")
        print(line)


def cmd_provision(args):
    require_allowed(args.serial)

    def one(adb):
        steps = app.provision_steps(adb, args.apk, args.usage_access, args.battery_exempt, args.stay_awake)
        print("[%s] provisioning plan:" % adb.serial)
        for desc, cmd, _ in steps:
            print("  - %s\n      %s" % (desc, " ".join(cmd) if isinstance(cmd, tuple) else cmd))
        if not args.yes:
            print("  (dry run: add --yes to apply)")
            return
        restores = []
        for desc, cmd, restore in steps:
            if isinstance(cmd, tuple):
                adb.run(*cmd, timeout=600)
            else:
                adb.shell(cmd)
            if restore:
                restores.append(restore)
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "provision-%s.json" % adb.serial), "w") as f:
            json.dump({"restores": restores, "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}, f, indent=1)
        print("[%s] provisioned; undo with: mrctl unprovision -s %s --yes" % (adb.serial, adb.serial))

    return parallel(args.serial, one)


def cmd_unprovision(args):
    require_allowed(args.serial)

    def one(adb):
        path = os.path.join(OUT_DIR, "provision-%s.json" % adb.serial)
        try:
            with open(path) as f:
                restores = json.load(f)["restores"]
        except (OSError, ValueError, KeyError):
            raise RuntimeError("no provisioning record at " + path)
        print("[%s] restore plan:" % adb.serial)
        for r in restores:
            print("  - " + r)
        if not args.yes:
            print("  (dry run: add --yes to apply)")
            return
        for r in reversed(restores):
            adb.shell(r, check=False)
        os.remove(path)
        print("[%s] restored" % adb.serial)

    return parallel(args.serial, one)


def run_values(args):
    values = {"mode": args.mode, "profile": args.profile, "seconds": args.seconds, "baselineSeconds": args.baseline_seconds,
              "intensity": args.intensity, "threads": args.threads, "memoryMiB": args.memory_mib, "diskMiB": args.disk_mib,
              "writeLimitMiB": args.write_limit_mib, "diskMiBPerSecond": args.disk_rate_mib, "downloadMiB": args.download_mib}
    return {k: v for k, v in values.items() if v is not None}


def cmd_start(args):
    require_allowed(args.serial)
    values = run_values(args)
    if "mode" not in values and "profile" not in values:
        raise SystemExit("start needs --mode or --profile")

    def one(adb):
        command_id = "mrctl-" + uuid.uuid4().hex[:12]
        before = app.status(adb).get("RunId")
        app.start(adb, command_id=command_id, **values)
        for _ in range(20):
            time.sleep(0.5)
            st = app.status(adb)
            if st.get("RunId") != before:
                print("[%s] started run %s: %s / %s" % (adb.serial, st.get("RunId"), st.get("Mode"), st.get("State")))
                return
            err = app.error(adb, command_id)
            if err:
                raise RuntimeError(err)
        raise RuntimeError("no acknowledgement within 10 s (check: adb logcat -s MatrixRain)")

    return parallel(args.serial, one)


def cmd_stop(args):
    require_allowed(args.serial)

    def one(adb):
        app.stop(adb, args.run_id)
        time.sleep(2)
        st = app.status(adb)
        print("[%s] %s / %s / %s" % (adb.serial, st.get("Mode"), st.get("State"), st.get("Detail")))

    return parallel(args.serial, one)


def cmd_status(args):
    def show(adb):
        st = app.status(adb)
        if args.json:
            print(json.dumps(st, indent=1))
            return
        line = "[%s] %s %s %s %s/%s %s" % (adb.serial, str(st.get("RunId", ""))[:8], st.get("Mode"), st.get("State"),
                                          st.get("PhaseIndex"), st.get("PhaseCount"), st.get("Detail"))
        if st.get("Stale"):
            line += "  [HEARTBEAT STALE %.0fs]" % (st.get("HeartbeatAgeSeconds") or -1)
        print(line)

    while True:
        failed = parallel(args.serial, show)
        if not args.watch:
            return failed
        time.sleep(args.watch)


def cmd_fault(args):
    require_allowed(args.serial)

    def one(adb):
        app.fault(adb, args.kind)
        print("[%s] requested %s (see: adb logcat -s MatrixRain)" % (adb.serial, args.kind))

    return parallel(args.serial, one)


def cmd_scenario(args):
    plan = scenario.load(args.file)
    if args.action == "check":
        print("%s: %d valid step(s)" % (plan["name"], len(plan["steps"])))
        for i, step in enumerate(plan["steps"]):
            print("  %d. %s" % (i + 1, scenario.describe(step)))
        return 0
    if not args.serial:
        raise SystemExit("scenario run needs -s SERIAL")
    require_allowed(args.serial)
    print("Scenario %s on %s:" % (plan["name"], ", ".join(args.serial)))
    for i, step in enumerate(plan["steps"]):
        print("  %d. %s" % (i + 1, scenario.describe(step)))
    print("Synthetic events (battery, network, crashes, reboots) appear in UEM/Intelligence like real ones: use tagged lab devices only.")
    if not args.yes:
        print("(dry run: add --yes to execute)")
        return 0

    def one(adb):
        runner = scenario.Runner(adb, log=lambda m: print("[%s] %s" % (adb.serial, m)), state_dir=OUT_DIR)
        runner.run(plan)
        print("[%s] scenario complete" % adb.serial)

    return parallel(args.serial, one)


def cmd_collect(args):
    def one(adb):
        base = collect.collect(adb, args.out, args.package or [])
        print("[%s] wrote %s.{json,md} and -batterystats.txt" % (adb.serial, base))

    return parallel(args.serial, one)


def parser():
    p = argparse.ArgumentParser(prog="mrctl", description="Matrix Rain bench harness", formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__)
    sub = p.add_subparsers(dest="command", required=True)

    def serials(sp, required=True):
        sp.add_argument("-s", "--serial", action="append", required=required, help="device serial (repeat for several)")

    sub.add_parser("devices").set_defaults(fn=cmd_devices)

    sp = sub.add_parser("provision"); serials(sp)
    sp.add_argument("--apk"); sp.add_argument("--usage-access", action="store_true")
    sp.add_argument("--battery-exempt", action="store_true"); sp.add_argument("--stay-awake", action="store_true")
    sp.add_argument("--yes", action="store_true"); sp.set_defaults(fn=cmd_provision)

    sp = sub.add_parser("unprovision"); serials(sp); sp.add_argument("--yes", action="store_true"); sp.set_defaults(fn=cmd_unprovision)

    sp = sub.add_parser("start"); serials(sp)
    sp.add_argument("--mode", choices=["baseline", "visualization", "cpu", "memory", "disk", "science", "workday", "network"])
    sp.add_argument("--profile", choices=["node%02d" % i for i in range(1, 7)])
    for flag in ("seconds", "baseline-seconds", "intensity", "threads", "memory-mib", "disk-mib", "write-limit-mib", "disk-rate-mib", "download-mib"):
        sp.add_argument("--" + flag, type=int)
    sp.set_defaults(fn=cmd_start)

    sp = sub.add_parser("stop"); serials(sp); sp.add_argument("--run-id"); sp.set_defaults(fn=cmd_stop)

    sp = sub.add_parser("status"); serials(sp)
    sp.add_argument("--watch", type=int, metavar="SECONDS"); sp.add_argument("--json", action="store_true"); sp.set_defaults(fn=cmd_status)

    sp = sub.add_parser("fault"); serials(sp); sp.add_argument("kind", choices=["crash", "anr", "native", "handled"]); sp.set_defaults(fn=cmd_fault)

    sp = sub.add_parser("scenario"); sp.add_argument("action", choices=["check", "run"]); sp.add_argument("file"); serials(sp, required=False)
    sp.add_argument("--yes", action="store_true"); sp.set_defaults(fn=cmd_scenario)

    sp = sub.add_parser("collect"); serials(sp); sp.add_argument("--out", default=OUT_DIR); sp.add_argument("--package", action="append")
    sp.set_defaults(fn=cmd_collect)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        return args.fn(args) or 0
    except (AdbError, scenario.ScenarioError, ValueError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        # Scenario runners restore in their finally blocks; on-device watchdogs cover anything left.
        print("interrupted", file=sys.stderr)
        return 130
