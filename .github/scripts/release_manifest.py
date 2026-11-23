#!/usr/bin/env python3
"""Build and verify non-installable CI release manifests.

Production installers must add and verify platform signatures before changing the
distribution/installable fields. This tool deliberately cannot bless an artifact
as production-installable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
SHA = re.compile(r"^[0-9a-f]{40}$")
COMPONENT_FIELDS = {"platform", "packageId", "version", "buildNumber", "artifactName"}
PLATFORMS = {"ANDROID", "IOS", "WINDOWS"}


class ReleaseManifestError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseManifestError(message)


def _read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseManifestError(f"Cannot read JSON {path}: {error}") from error
    _require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def load_plan(path: Path) -> dict:
    plan = _read_json(path)
    required = {
        "schemaVersion", "releaseVersion", "releaseSequence",
        "bridgeProtocolVersion", "minimumSupportedReleaseSequence", "components",
    }
    _require(set(plan) == required, "release plan fields are not canonical")
    _require(plan["schemaVersion"] == 1, "unsupported release plan schemaVersion")
    _require(SEMVER.fullmatch(plan["releaseVersion"] or "") is not None,
             "releaseVersion must be canonical SemVer")
    for field in ("releaseSequence", "bridgeProtocolVersion", "minimumSupportedReleaseSequence"):
        _require(type(plan[field]) is int and plan[field] > 0, f"{field} must be a positive integer")
    _require(plan["minimumSupportedReleaseSequence"] <= plan["releaseSequence"],
             "minimumSupportedReleaseSequence cannot exceed releaseSequence")
    components = plan["components"]
    _require(isinstance(components, dict) and components, "components must not be empty")
    package_ids: set[str] = set()
    artifact_names: set[str] = set()
    for name, component in components.items():
        _require(re.fullmatch(r"[a-z][a-z0-9-]*", name) is not None,
                 f"invalid component name: {name}")
        _require(isinstance(component, dict) and set(component) == COMPONENT_FIELDS,
                 f"component {name} fields are not canonical")
        _require(component["platform"] in PLATFORMS, f"component {name} platform is invalid")
        _require(re.fullmatch(r"[A-Za-z0-9.-]+", component["packageId"] or "") is not None,
                 f"component {name} packageId is invalid")
        _require(component["packageId"] not in package_ids, "packageId values must be unique")
        package_ids.add(component["packageId"])
        _require(component["version"] == plan["releaseVersion"],
                 f"component {name} version must equal releaseVersion")
        _require(type(component["buildNumber"]) is int and component["buildNumber"] > 0,
                 f"component {name} buildNumber must be positive")
        artifact = component["artifactName"]
        _require(Path(artifact).name == artifact and artifact not in {"", ".", ".."},
                 f"component {name} artifactName must be a file name")
        _require(artifact not in artifact_names, "artifactName values must be unique")
        artifact_names.add(artifact)
    return plan


def validate_source_versions(plan: dict, root: Path) -> None:
    android = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    _require(re.search(rf'versionName\s*=\s*"{re.escape(plan["releaseVersion"])}"', android) is not None,
             "Android versionName differs from release plan")
    _require(re.search(rf'versionCode\s*=\s*{plan["components"]["android"]["buildNumber"]}\b', android) is not None,
             "Android versionCode differs from release plan")

    xcode = (root / "ios/GLAFIRA.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    ios = plan["components"]["ios"]
    _require(xcode.count(f'MARKETING_VERSION = {ios["version"]};') == 2,
             "iOS MARKETING_VERSION must match in Debug and Release")
    _require(xcode.count(f'CURRENT_PROJECT_VERSION = {ios["buildNumber"]};') == 2,
             "iOS CURRENT_PROJECT_VERSION must match in Debug and Release")

    for name, project in (
        ("windows-desktop", root / "windows-desktop/GLAFIRA.Desktop.App/GLAFIRA.Desktop.App.csproj"),
        ("windows-agent", root / "windows-agent/GLAFIRA.Agent.Worker/GLAFIRA.Agent.Worker.csproj"),
    ):
        text = project.read_text(encoding="utf-8")
        version = plan["components"][name]["version"]
        build = plan["components"][name]["buildNumber"]
        _require(f"<Version>{version}</Version>" in text, f"{name} Version differs from release plan")
        _require(f"<FileVersion>{version}.{build}</FileVersion>" in text,
                 f"{name} FileVersion differs from release plan")


def _artifact_map(values: list[str]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for value in values:
        name, separator, raw_path = value.partition("=")
        _require(bool(separator and name and raw_path), "artifact must be COMPONENT=PATH")
        _require(name not in result, f"duplicate artifact component: {name}")
        result[name] = Path(raw_path)
    _require(bool(result), "at least one artifact is required")
    return result


def _digest(path: Path) -> tuple[str, int]:
    _require(path.is_file(), f"artifact does not exist: {path}")
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    _require(size > 0, f"artifact is empty: {path}")
    return digest.hexdigest(), size


def build_manifest(plan: dict, artifacts: dict[str, Path], source_revision: str,
                   created_at: str | None = None) -> dict:
    _require(SHA.fullmatch(source_revision) is not None,
             "sourceRevision must be a lowercase 40-character commit SHA")
    unknown = set(artifacts) - set(plan["components"])
    _require(not unknown, f"unknown artifact components: {sorted(unknown)}")
    packages = []
    for name in sorted(artifacts):
        definition = plan["components"][name]
        path = artifacts[name]
        _require(path.name == definition["artifactName"],
                 f"artifact name for {name} must be {definition['artifactName']}")
        digest, size = _digest(path)
        packages.append({
            "component": name,
            **definition,
            "sha256": digest,
            "sizeBytes": size,
            "signature": {
                "status": "UNVERIFIED",
                "requirement": "REQUIRED_FOR_PRODUCTION",
            },
        })
    missing = sorted(set(plan["components"]) - set(artifacts))
    timestamp = created_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    _require(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", timestamp) is not None,
             "createdAt must be a whole-second UTC timestamp")
    try:
        datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ReleaseManifestError("createdAt is not a real UTC timestamp") from error
    return {
        "schemaVersion": 1,
        "releaseVersion": plan["releaseVersion"],
        "releaseSequence": plan["releaseSequence"],
        "bridgeProtocolVersion": plan["bridgeProtocolVersion"],
        "minimumSupportedReleaseSequence": plan["minimumSupportedReleaseSequence"],
        "sourceRevision": source_revision,
        "createdAt": timestamp,
        "distribution": "CI_UNSIGNED",
        "installable": False,
        "coverage": {"complete": not missing, "missingComponents": missing},
        "packages": packages,
    }


def verify_manifest(manifest: dict, plan: dict, artifacts: dict[str, Path]) -> None:
    expected = build_manifest(plan, artifacts, manifest.get("sourceRevision", ""), manifest.get("createdAt"))
    _require(manifest == expected, "manifest content or artifact digest does not match release plan")
    _require(manifest["distribution"] == "CI_UNSIGNED" and manifest["installable"] is False,
             "unsigned CI manifest must never be installable")


def check_update(current: dict, candidate: dict) -> None:
    for value, label in ((current, "current"), (candidate, "candidate")):
        _require(type(value.get("releaseSequence")) is int, f"{label} releaseSequence is invalid")
        _require(SEMVER.fullmatch(value.get("releaseVersion", "")) is not None,
                 f"{label} releaseVersion is invalid")
        _require(isinstance(value.get("packages"), list), f"{label} packages are invalid")
    _require(candidate["releaseSequence"] > current["releaseSequence"],
             "candidate releaseSequence must strictly increase")
    _require(tuple(map(int, candidate["releaseVersion"].split("."))) >
             tuple(map(int, current["releaseVersion"].split("."))),
             "candidate releaseVersion must strictly increase")
    current_ids = {item["component"]: item["packageId"] for item in current["packages"]}
    candidate_ids = {item["component"]: item["packageId"] for item in candidate["packages"]}
    _require(current_ids == candidate_ids, "candidate component/package identity set changed")
    _require(candidate.get("minimumSupportedReleaseSequence", 0) <= candidate["releaseSequence"],
             "candidate compatibility floor exceeds its release sequence")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate-plan")
    validate.add_argument("--plan", type=Path, required=True)
    validate.add_argument("--root", type=Path, required=True)
    build = sub.add_parser("build-manifest")
    build.add_argument("--plan", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--source-revision", required=True)
    build.add_argument("--created-at")
    build.add_argument("--artifact", action="append", default=[])
    verify = sub.add_parser("verify-manifest")
    verify.add_argument("--plan", type=Path, required=True)
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--artifact", action="append", default=[])
    update = sub.add_parser("check-update")
    update.add_argument("--current", type=Path, required=True)
    update.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "validate-plan":
        validate_source_versions(load_plan(args.plan), args.root)
    elif args.command == "build-manifest":
        manifest = build_manifest(load_plan(args.plan), _artifact_map(args.artifact),
                                  args.source_revision, args.created_at)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    elif args.command == "verify-manifest":
        verify_manifest(_read_json(args.manifest), load_plan(args.plan), _artifact_map(args.artifact))
    else:
        check_update(_read_json(args.current), _read_json(args.candidate))


if __name__ == "__main__":
    try:
        main()
    except ReleaseManifestError as error:
        raise SystemExit(f"release manifest validation failed: {error}") from error
