import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from mrctl import app, cli, scenario  # noqa: E402


class FakeAdb:
    def __init__(self, serial="SERIAL1", responses=None):
        self.serial = serial
        self.commands = []
        self.responses = responses or {}
        self.next_pid = 100

    @property
    def wireless(self):
        return ":" in self.serial

    def shell(self, command, check=True, timeout=None):
        self.commands.append(command)
        for prefix, reply in self.responses.items():
            if command.startswith(prefix):
                return reply(command) if callable(reply) else reply
        return ""

    def run(self, *args, check=True, timeout=None):
        self.commands.append(" ".join(args))
        return ""

    def getprop(self, name):
        return ""

    def detach(self, command, after_seconds):
        self.next_pid += 1
        self.commands.append("DETACH %d %s" % (after_seconds, command))
        return self.next_pid

    def cancel(self, pid):
        self.commands.append("CANCEL %d" % pid)


def runner(adb, **kw):
    return scenario.Runner(adb, log=lambda m: None, sleep=lambda s: None, **kw)


class ScenarioTests(unittest.TestCase):
    def test_battery_drain_arms_watchdog_steps_down_and_resets(self):
        adb = FakeAdb()
        runner(adb).run({"steps": [{"action": "battery-drain", "from": 20, "to": 18, "stepSeconds": 5}]})
        self.assertEqual(adb.commands[0], "DETACH 45 dumpsys battery reset")
        self.assertEqual(adb.commands[1:5], ["dumpsys battery unplug", "dumpsys battery set level 20",
                                             "dumpsys battery set level 19", "dumpsys battery set level 18"])
        self.assertEqual(adb.commands[5:], ["dumpsys battery reset", "CANCEL 101"])

    def test_wifi_restored_even_when_a_step_fails(self):
        adb = FakeAdb()
        r = runner(adb)

        def boom(step):
            raise RuntimeError("host lost")

        r._wait = boom
        with self.assertRaises(RuntimeError):
            r.run({"steps": [{"action": "airplane", "seconds": 30}, {"action": "wait", "seconds": 5}]})
        self.assertIn("cmd connectivity airplane-mode disable", adb.commands)
        # The watchdog was armed before the change was made.
        self.assertLess(adb.commands.index("DETACH 60 cmd connectivity airplane-mode disable"),
                        adb.commands.index("cmd connectivity airplane-mode enable"))

    def test_wifi_change_refused_over_wireless_adb(self):
        adb = FakeAdb(serial="192.168.1.20:5555")
        with self.assertRaises(scenario.ScenarioError):
            runner(adb).run({"steps": [{"action": "wifi-off", "seconds": 10}]})
        self.assertNotIn("cmd wifi set-wifi-enabled disabled", adb.commands)

    def test_protected_packages_are_refused(self):
        for pkg in ("com.airwatch.androidagent", "com.android.settings", "com.azure.authenticator", "com.zebra.zcm"):
            with self.assertRaises(scenario.ScenarioError, msg=pkg):
                scenario.validate({"action": "monkey", "package": pkg})
        scenario.validate({"action": "am-crash", "package": "com.Slack"})

    def test_reboot_cap(self):
        with tempfile.TemporaryDirectory() as d:
            r = runner(FakeAdb(responses={"getprop sys.boot_completed": "1\n"}), state_dir=d, reboots_per_day=2)
            r.run({"steps": [{"action": "reboot"}, {"action": "reboot"}]})
            with self.assertRaises(scenario.ScenarioError):
                r.run({"steps": [{"action": "reboot"}]})

    def test_validation(self):
        bad = [
            {"action": "battery-drain", "from": 10, "to": 20},
            {"action": "wifi-off", "seconds": 0},
            {"action": "wifi-off", "seconds": 601},
            {"action": "start", "mode": "cpu", "rm": "-rf"},
            {"action": "format-device"},
        ]
        for step in bad:
            with self.assertRaises(scenario.ScenarioError, msg=step):
                scenario.validate(step)

    def test_example_scenarios_are_valid(self):
        folder = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scenarios")
        for name in os.listdir(folder):
            scenario.load(os.path.join(folder, name))


class AppTests(unittest.TestCase):
    def test_start_builds_typed_extras(self):
        adb = FakeAdb()
        app.start(adb, command_id="c1", mode="cpu", seconds=60, threads=2)
        self.assertIn("-a com.aftersix.matrixrain.START --es mode cpu --ei seconds 60 --ei threads 2 --es commandId c1", adb.commands[0])
        with self.assertRaises(ValueError):
            app.start(adb, turbo=1)

    def test_status_reads_provider(self):
        adb = FakeAdb(responses={"content read": json.dumps({"State": "RUNNING", "RunId": "abc"})})
        self.assertEqual("RUNNING", app.status(adb)["State"])

    def test_provision_records_restores(self):
        adb = FakeAdb(responses={"settings get global stay_on_while_plugged_in": "0\n"})
        steps = app.provision_steps(adb, usage_access=True, battery_exempt=True, stay_awake=True)
        restores = [r for _, _, r in steps if r]
        self.assertIn("appops set com.aftersix.matrixrain SYSTEM_ALERT_WINDOW default", restores)
        self.assertIn("dumpsys deviceidle whitelist -com.aftersix.matrixrain", restores)
        self.assertIn("settings put global stay_on_while_plugged_in 0", restores)


class AllowListTests(unittest.TestCase):
    def test_unlisted_serial_refused(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("GOOD1  # lab\n# comment\n")
        try:
            self.assertEqual({"GOOD1"}, cli.allowed_serials(f.name))
            old = cli.ALLOW_FILE
            cli.ALLOW_FILE = f.name
            try:
                cli.require_allowed(["GOOD1"])
                with self.assertRaises(SystemExit):
                    cli.require_allowed(["GOOD1", "OTHER"])
            finally:
                cli.ALLOW_FILE = old
        finally:
            os.remove(f.name)


if __name__ == "__main__":
    unittest.main()
