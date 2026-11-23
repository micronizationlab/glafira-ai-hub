import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "release_manifest.py"
SPEC = importlib.util.spec_from_file_location("release_manifest", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = Path(__file__).resolve().parents[3]
PLAN_PATH = ROOT / "release/release-plan.json"


class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.plan = MODULE.load_plan(PLAN_PATH)
        self.agent = self.directory / "GLAFIRA-Agent-win-x64.zip"
        self.agent.write_bytes(b"agent-package-v1")
        self.artifacts = {"windows-agent": self.agent}
        self.manifest = MODULE.build_manifest(
            self.plan, self.artifacts, "a" * 40, "2026-11-23T00:00:00Z"
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_repository_versions_match_canonical_release_plan(self):
        MODULE.validate_source_versions(self.plan, ROOT)

    def test_manifest_binds_component_version_digest_and_size(self):
        package = self.manifest["packages"][0]
        self.assertEqual("windows-agent", package["component"])
        self.assertEqual("0.1.0", package["version"])
        self.assertEqual(len(b"agent-package-v1"), package["sizeBytes"])
        self.assertRegex(package["sha256"], r"^[0-9a-f]{64}$")
        MODULE.verify_manifest(self.manifest, self.plan, self.artifacts)

    def test_unsigned_ci_manifest_is_never_installable(self):
        self.assertEqual("CI_UNSIGNED", self.manifest["distribution"])
        self.assertFalse(self.manifest["installable"])
        self.assertEqual("REQUIRED_FOR_PRODUCTION",
                         self.manifest["packages"][0]["signature"]["requirement"])

    def test_partial_manifest_discloses_missing_components(self):
        self.assertFalse(self.manifest["coverage"]["complete"])
        self.assertEqual(["android", "ios", "windows-desktop"],
                         self.manifest["coverage"]["missingComponents"])

    def test_tampered_artifact_fails_verification(self):
        self.agent.write_bytes(b"tampered")
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "digest"):
            MODULE.verify_manifest(self.manifest, self.plan, self.artifacts)

    def test_wrong_artifact_name_is_rejected(self):
        wrong = self.directory / "renamed.zip"
        wrong.write_bytes(b"agent-package-v1")
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "artifact name"):
            MODULE.build_manifest(self.plan, {"windows-agent": wrong}, "a" * 40)

    def test_invalid_manifest_timestamp_is_rejected(self):
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "timestamp"):
            MODULE.build_manifest(
                self.plan, self.artifacts, "a" * 40, "2026-02-31T00:00:00Z"
            )

    def test_update_requires_strictly_monotonic_version_and_sequence(self):
        candidate = copy.deepcopy(self.manifest)
        candidate["releaseSequence"] = 2
        candidate["releaseVersion"] = "0.2.0"
        MODULE.check_update(self.manifest, candidate)
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "strictly increase"):
            MODULE.check_update(candidate, self.manifest)

    def test_update_rejects_package_identity_substitution(self):
        candidate = copy.deepcopy(self.manifest)
        candidate["releaseSequence"] = 2
        candidate["releaseVersion"] = "0.2.0"
        candidate["packages"][0]["packageId"] = "attacker.replacement"
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "identity"):
            MODULE.check_update(self.manifest, candidate)

    def test_noncanonical_plan_is_rejected(self):
        path = self.directory / "plan.json"
        invalid = copy.deepcopy(self.plan)
        invalid["releaseSequence"] = 0
        path.write_text(json.dumps(invalid), encoding="utf-8")
        with self.assertRaisesRegex(MODULE.ReleaseManifestError, "positive"):
            MODULE.load_plan(path)


if __name__ == "__main__":
    unittest.main()
