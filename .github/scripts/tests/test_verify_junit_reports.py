import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "verify-junit-reports.py"


class VerifyJUnitReportsTests(unittest.TestCase):
    def run_verifier(self, directory: Path):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(directory)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_accepts_executed_passing_test(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-example.xml").write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="Example" name="works"/>'
                '</testsuite>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("1 test case(s)", result.stdout)

    def test_rejects_missing_reports(self):
        with tempfile.TemporaryDirectory() as temp:
            result = self.run_verifier(Path(temp))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("no TEST-*.xml", result.stderr)

    def test_rejects_zero_test_cases(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-empty.xml").write_text(
                '<testsuite tests="0" failures="0" errors="0"/>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("zero test cases", result.stderr)

    def test_rejects_all_skipped(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-skipped.xml").write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="1">'
                '<testcase classname="Example" name="skipped"><skipped/></testcase>'
                '</testsuite>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("skipped test cases", result.stderr)

    def test_rejects_partial_skips(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-partial-skips.xml").write_text(
                '<testsuite tests="2" failures="0" errors="0" skipped="1">'
                '<testcase classname="Example" name="works"/>'
                '<testcase classname="Example" name="skipped"><skipped/></testcase>'
                '</testsuite>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("skipped test cases", result.stderr)


    def test_rejects_missing_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            missing = Path(temp) / "missing"
            result = self.run_verifier(missing)
            self.assertEqual(1, result.returncode)
            self.assertIn("directory is missing", result.stderr)

    def test_rejects_malformed_xml(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-broken.xml").write_text("<testsuite><testcase>", encoding="utf-8")
            result = self.run_verifier(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("cannot parse", result.stderr)

    def test_rejects_suite_level_error(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-error.xml").write_text(
                '<testsuite tests="1" failures="0" errors="1">'
                '<testcase classname="Example" name="error"><error/></testcase>'
                '</testsuite>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("contain failures or errors", result.stderr)

    def test_each_requested_directory_must_have_reports(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            valid = root / "valid"
            empty = root / "empty"
            valid.mkdir()
            empty.mkdir()
            (valid / "TEST-ok.xml").write_text(
                '<testsuite tests="1"><testcase name="ok"/></testsuite>',
                encoding="utf-8",
            )
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(valid), str(empty)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("no TEST-*.xml", result.stderr)

    def test_rejects_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-failed.xml").write_text(
                '<testsuite tests="1" failures="1" errors="0" skipped="0">'
                '<testcase classname="Example" name="broken"><failure/></testcase>'
                '</testsuite>',
                encoding="utf-8",
            )
            result = self.run_verifier(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("contain failures or errors", result.stderr)


if __name__ == "__main__":
    unittest.main()
