import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "detect_changes.py"
SPEC = importlib.util.spec_from_file_location("detect_changes", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ChangeSelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for path in (
            "contracts",
            "ios/GlafiraBridgeKit",
            "windows-agent/GLAFIRA.Agent.Worker",
        ):
            (self.root / path).mkdir(parents=True, exist_ok=True)
        (self.root / "settings.gradle.kts").write_text(
            'rootProject.name = "test"\n', encoding="utf-8"
        )
        (self.root / "ios/GlafiraBridgeKit/Package.swift").write_text(
            "// test\n", encoding="utf-8"
        )
        (self.root / "windows-agent/GLAFIRA.Agent.Worker/GLAFIRA.Agent.Worker.csproj").write_text(
            "<Project />\n", encoding="utf-8"
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_docs_only_change_skips_all_platform_suites(self):
        selected = MODULE.classify_paths(["docs/architecture.md"], "pull_request", self.root)
        self.assertFalse(any(selected.values()))

    def test_workflow_only_change_runs_linter_and_all_present_platform_suites(self):
        selected = MODULE.classify_paths(
            [".github/workflows/android.yml"], "pull_request", self.root
        )
        self.assertTrue(selected["workflow_lint"])
        self.assertTrue(selected["android"])
        self.assertTrue(selected["ios"])
        self.assertTrue(selected["agent"])
        self.assertTrue(selected["contracts"])
        self.assertFalse(selected["android_acceptance"])

    def test_ci_script_change_runs_linter_and_all_present_platform_suites(self):
        selected = MODULE.classify_paths(
            [".github/scripts/detect_changes.py"], "pull_request", self.root
        )
        self.assertTrue(selected["workflow_lint"])
        self.assertTrue(selected["android"])
        self.assertTrue(selected["ios"])
        self.assertTrue(selected["agent"])
        self.assertTrue(selected["contracts"])
        self.assertFalse(selected["android_acceptance"])

    def test_android_core_change_selects_android_and_contracts(self):
        selected = MODULE.classify_paths(
            ["app/src/main/java/com/micronizationlab/glafira/GlafiraCorePolicy.kt"],
            "pull_request",
            self.root,
        )
        self.assertTrue(selected["android"])
        self.assertTrue(selected["contracts"])
        self.assertFalse(selected["ios"])
        self.assertFalse(selected["agent"])
        self.assertTrue(selected["android_acceptance"])

    def test_bridge_change_requires_instrumentation_harness(self):
        path = "app/src/main/java/com/micronizationlab/glafira/GlafiraBridgeHostService.kt"
        selected = MODULE.classify_paths([path], "pull_request", self.root)
        self.assertTrue(selected["android"])
        self.assertFalse(selected["android_acceptance"])
        harness = self.root / ".github/scripts/run-android-instrumentation.sh"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text("#!/usr/bin/env bash\\n", encoding="utf-8")
        selected = MODULE.classify_paths([path], "pull_request", self.root)
        self.assertTrue(selected["android_acceptance"])

    def test_contract_change_selects_every_present_platform(self):
        selected = MODULE.classify_paths(
            ["contracts/v1/glafira-task-agent.schema.json"],
            "pull_request",
            self.root,
        )
        self.assertTrue(selected["contracts"])
        self.assertTrue(selected["android"])
        self.assertTrue(selected["ios"])
        self.assertTrue(selected["agent"])

    def test_scheduled_run_selects_full_sweep(self):
        selected = MODULE.classify_paths([], "schedule", self.root)
        self.assertTrue(selected["android"])
        self.assertTrue(selected["ios"])
        self.assertTrue(selected["agent"])
        self.assertTrue(selected["contracts"])
        self.assertFalse(selected["android_acceptance"])


if __name__ == "__main__":
    unittest.main()
