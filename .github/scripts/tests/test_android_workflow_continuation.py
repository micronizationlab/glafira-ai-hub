from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / "workflows" / "android.yml"


def job_block(source: str, job_name: str) -> str:
    match = re.search(
        rf"^  {re.escape(job_name)}:\n(.*?)(?=^  [a-z0-9-]+:\n|\Z)",
        source,
        flags=re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Missing workflow job: {job_name}")
    return match.group(1)


class AndroidWorkflowContinuationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text(encoding="utf-8")

    def test_apk_build_is_independent_of_jvm_test_result(self):
        block = job_block(self.source, "android-apk")
        self.assertIn("needs: [changes]", block)
        self.assertIn("if: needs.changes.outputs.android == 'true'", block)
        self.assertNotIn("needs.android-tests.result", block)

    def test_emulator_acceptance_is_independent_of_jvm_test_result(self):
        block = job_block(self.source, "android-emulator")
        self.assertIn("needs: [changes]", block)
        self.assertIn("if: needs.changes.outputs.android_acceptance == 'true'", block)
        self.assertNotIn("needs.android-tests.result", block)

    def test_junit_verification_runs_even_after_test_command_fails(self):
        self.assertRegex(
            self.source,
            r"name: Verify JVM JUnit reports contain executed tests\n\s+if: always\(\)",
        )
        self.assertRegex(
            self.source,
            r"name: Verify emulator JUnit reports contain executed tests\n\s+if: always\(\)",
        )

    def test_exact_head_summary_waits_for_all_android_results(self):
        block = job_block(self.source, "ci-summary")
        self.assertIn("if: always()", self.source)
        self.assertIn("android-tests", block)
        self.assertIn("android-apk", block)
        self.assertIn("android-emulator", block)


if __name__ == "__main__":
    unittest.main()
