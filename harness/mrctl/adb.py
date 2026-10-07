"""Thin adb wrapper. Every device command goes through Adb.run, so tests can substitute a fake."""

import os
import shlex
import shutil
import subprocess

PACKAGE = "com.aftersix.matrixrain"
SERVICE = PACKAGE + "/.service.WorkloadService"
STATUS_URI = "content://" + PACKAGE + ".status"


class AdbError(RuntimeError):
    pass


def find_adb():
    for candidate in (os.environ.get("ADB"), shutil.which("adb"), os.path.expanduser("~/Downloads/platform-tools/adb"),
                      os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")):
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    raise AdbError("adb not found: set ADB=/path/to/adb or add platform-tools to PATH")


class Adb:
    def __init__(self, serial, adb=None, timeout=120):
        self.serial = serial
        self.adb = adb or find_adb()
        self.timeout = timeout

    def run(self, *args, check=True, timeout=None):
        cmd = [self.adb, "-s", self.serial, *args]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout or self.timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError("timed out: " + " ".join(cmd[3:])) from e
        if check and proc.returncode != 0:
            raise AdbError("adb %s failed (%d): %s" % (" ".join(args), proc.returncode, (proc.stderr or proc.stdout).strip()))
        return proc.stdout

    def shell(self, command, check=True, timeout=None):
        return self.run("shell", command, check=check, timeout=timeout)

    def getprop(self, name):
        return self.shell("getprop " + shlex.quote(name)).strip()

    @property
    def wireless(self):
        """True when connected over the network; turning Wi-Fi off would cut the harness off."""
        return ":" in self.serial or self.serial.startswith("adb-")

    def detach(self, command, after_seconds):
        """Starts `command` on the device after a delay, detached from this adb session, so it runs even if the
        host disconnects. Used for restore watchdogs. Returns the watchdog's device PID (None if unknown)."""
        inner = "sleep %d; %s" % (int(after_seconds), command)
        out = self.shell("nohup setsid sh -c %s >/dev/null 2>&1 & echo $!" % shlex.quote(inner))
        pid = out.strip().splitlines()[-1] if out.strip() else ""
        return int(pid) if pid.isdigit() else None

    def cancel(self, pid):
        """Cancels a watchdog started by detach (its sleep and the pending restore)."""
        if pid:
            self.shell("pkill -P %d 2>/dev/null; kill %d 2>/dev/null" % (pid, pid), check=False)


def list_devices(adb=None):
    adb = adb or find_adb()
    out = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True, timeout=30).stdout
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            info = dict(p.split(":", 1) for p in parts[2:] if ":" in p)
            devices.append({"serial": parts[0], "state": parts[1], "model": info.get("model", "")})
    return devices
