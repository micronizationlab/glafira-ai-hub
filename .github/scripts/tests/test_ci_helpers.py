"""Host-only regressions for CI deadlines and truthful test-result gates."""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify_junit", SCRIPTS / "verify-junit.py")
junit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(junit)


class JUnitGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)

    def report(self, text):
        (self.directory / "TEST-example.xml").write_text(text)

    def test_missing_reports_fail(self):
        with self.assertRaisesRegex(ValueError, "No JUnit"):
            junit.verify(self.directory)

    def test_skipped_only_and_empty_suites_fail(self):
        for cases in ('<testcase><skipped/></testcase>', ''):
            with self.subTest(cases=cases):
                self.report(f'<testsuite>{cases}</testsuite>')
                with self.assertRaisesRegex(ValueError, "No test cases executed"):
                    junit.verify(self.directory)

    def test_case_and_runner_failures_fail(self):
        for xml in ('<testsuite><testcase><failure/></testcase></testsuite>',
                    '<testsuite errors="1"><testcase/></testsuite>'):
            with self.subTest(xml=xml):
                self.report(xml)
                with self.assertRaises(ValueError):
                    junit.verify(self.directory)

    def test_success_reports_executed_and_skipped_counts(self):
        self.report('<testsuite><testcase/><testcase><skipped/></testcase></testsuite>')
        self.assertIn('1 executed, 1 skipped', junit.verify(self.directory))

    def test_malformed_report_returns_nonzero(self):
        self.report('<testsuite>')
        result = subprocess.run(['python3', str(SCRIPTS / 'verify-junit.py'),
                                 str(self.directory)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn('JUnit verification failed', result.stderr)


FAKE_TOOL = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import sys
import time

tool = Path(sys.argv[0]).name
args = sys.argv[1:]
scenario = os.environ['CI_TEST_SCENARIO']
with open(os.environ['CI_TEST_COMMANDS'], 'a') as log:
    log.write(tool + ' ' + ' '.join(args) + '\n')
if tool in ('sdkmanager', 'avdmanager'):
    sys.exit(0)
if tool == 'emulator':
    if '-accel-check' in args:
        print('KVM acceleration test')
        sys.exit(9 if scenario == 'no-acceleration' else 0)
    if scenario == 'emulator-exits':
        sys.exit(42)
    Path(os.environ['CI_TEST_EMULATOR_PID']).write_text(str(os.getpid()))
    if scenario == 'stalled-device':
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    time.sleep(60)
if tool == 'adb':
    assert args[:2] == ['-s', 'emulator-5554'], args
    args = args[2:]
    if args == ['shell', 'getprop', 'sys.boot_completed']:
        print('0' if scenario in ('stalled-device', 'emulator-exits') else '1\r')
    elif args == ['shell', 'pm', 'path', 'android']:
        if scenario != 'package-unavailable':
            print('package:/system/framework/framework-res.apk')
    elif args == ['emu', 'kill'] and scenario == 'stalled-device':
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        time.sleep(60)
    elif args == ['logcat', '-b', 'all', '-v', 'threadtime']:
        time.sleep(60)
    sys.exit(0)
if tool == 'gradle':
    if scenario == 'gradle-fails':
        sys.exit(17)
    if scenario != 'missing-results':
        reports = Path('app/build/outputs/androidTest-results/connected/debug')
        reports.mkdir(parents=True)
        case = '<testcase><skipped/></testcase>' if scenario == 'skipped-results' else '<testcase/>'
        (reports / 'TEST-device.xml').write_text('<testsuite>' + case + '</testsuite>')
    sys.exit(0)
'''


class EmulatorHarnessTest(unittest.TestCase):
    def run_scenario(self, scenario, expected_status):
        with tempfile.TemporaryDirectory(prefix='glafira ci ') as temporary:
            root = Path(temporary)
            scripts = root / '.github/scripts'
            scripts.mkdir(parents=True)
            for name in ('run-android-instrumentation.sh', 'verify-junit.py'):
                shutil.copyfile(SCRIPTS / name, scripts / name)
            tool = root / 'fake-tool'
            tool.write_text(FAKE_TOOL)
            tool.chmod(0o755)
            sdk = root / 'sdk'
            for name in ('cmdline-tools/latest/bin/sdkmanager',
                         'cmdline-tools/latest/bin/avdmanager',
                         'emulator/emulator', 'platform-tools/adb'):
                path = sdk / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.symlink_to(tool)
            binary = root / 'bin'
            binary.mkdir()
            (binary / 'gradle').symlink_to(tool)
            env = dict(os.environ, ANDROID_SDK_ROOT=str(sdk),
                       PATH=str(binary) + os.pathsep + os.environ['PATH'],
                       BOOT_TIMEOUT_SECONDS='1', PACKAGE_TIMEOUT_SECONDS='1',
                       ADB_TIMEOUT_SECONDS='1', CLEANUP_TIMEOUT_SECONDS='1',
                       POLL_SECONDS='0.1', CI_TEST_SCENARIO=scenario,
                       CI_TEST_COMMANDS=str(root / 'commands.txt'),
                       CI_TEST_EMULATOR_PID=str(root / 'emulator.pid'))
            result = subprocess.run(['bash', str(scripts / 'run-android-instrumentation.sh')],
                                    env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(expected_status, result.returncode,
                             result.stdout + result.stderr)
            status_file = root / 'app/build/outputs/androidTest-exit-status.txt'
            self.assertIn(f'Original test step exit status: {expected_status}',
                          status_file.read_text())
            pid_file = root / 'emulator.pid'
            if pid_file.exists():
                with self.assertRaises(ProcessLookupError):
                    os.kill(int(pid_file.read_text()), 0)
            return (root / 'commands.txt').read_text()

    def test_success_requires_hardware_acceleration_and_tests(self):
        commands = self.run_scenario('success', 0)
        self.assertIn('-accel on', commands)
        self.assertIn('gradle :app:connectedDebugAndroidTest --stacktrace', commands)

    def test_failed_acceleration_does_not_run_tests(self):
        self.assertNotIn('gradle ', self.run_scenario('no-acceleration', 9))

    def test_stalled_boot_and_shutdown_are_bounded(self):
        self.assertNotIn('gradle ', self.run_scenario('stalled-device', 1))

    def test_early_emulator_exit_fails(self):
        self.assertNotIn('gradle ', self.run_scenario('emulator-exits', 1))

    def test_package_manager_deadline_fails(self):
        self.assertNotIn('gradle ', self.run_scenario('package-unavailable', 1))

    def test_gradle_failure_survives_cleanup(self):
        self.run_scenario('gradle-fails', 17)

    def test_missing_results_fail(self):
        self.run_scenario('missing-results', 1)

    def test_skipped_only_results_fail(self):
        self.run_scenario('skipped-results', 1)


if __name__ == '__main__':
    unittest.main()
