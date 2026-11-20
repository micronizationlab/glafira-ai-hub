#!/usr/bin/env python3
"""Change-aware CI selector shared by the workflow and its unit tests."""

from __future__ import annotations

import fnmatch
import os
import subprocess
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_NAMES = (
    "android",
    "android_acceptance",
    "ios",
    "agent",
    "contracts",
    "workflow_lint",
)


def classify_paths(
    changed_paths: Iterable[str],
    event_name: str,
    root: Path = ROOT,
) -> dict[str, bool]:
    selected = {name: False for name in OUTPUT_NAMES}
    for raw_path in changed_paths:
        path = raw_path.replace("\\", "/")
        if (
            path.startswith(("app/", "desktop-bridge/", "gradle/"))
            or path in {"gradlew", "gradle.properties", "build.gradle.kts", "settings.gradle.kts"}
        ):
            selected["android"] = True
            selected["contracts"] |= (root / "contracts").is_dir()
        elif path.startswith("ios/"):
            selected["ios"] = True
            selected["contracts"] |= (root / "contracts").is_dir()
        elif path.startswith("windows-agent/"):
            selected["agent"] = True
            selected["contracts"] |= (root / "contracts").is_dir()
        elif path.startswith("contracts/"):
            selected["contracts"] = True
            selected["android"] |= (root / "settings.gradle.kts").is_file()
            selected["ios"] |= (root / "ios/GlafiraBridgeKit/Package.swift").is_file()
            selected["agent"] |= (
                root / "windows-agent/GLAFIRA.Agent.Worker/GLAFIRA.Agent.Worker.csproj"
            ).is_file()
        elif path.startswith(".github/workflows/") or path.startswith(".github/scripts/"):
            # CI control-plane changes must exercise the suites whose selection/execution
            # they can affect. A green linter alone is not validation of a changed pipeline.
            selected["workflow_lint"] = True
            selected["android"] = True
            selected["ios"] = (root / "ios/GlafiraBridgeKit/Package.swift").is_file()
            selected["agent"] = (
                root / "windows-agent/GLAFIRA.Agent.Worker/GLAFIRA.Agent.Worker.csproj"
            ).is_file()
            selected["contracts"] = (root / "contracts").is_dir()
            selected["android_acceptance"] = (
                root / ".github/scripts/run-android-instrumentation.sh"
            ).is_file()

        if (
            path.startswith("app/src/androidTest/")
            or path == "app/src/main/AndroidManifest.xml"
            or fnmatch.fnmatch(
                path,
                "app/src/main/java/com/micronizationlab/glafira/*Bridge*",
            )
            or fnmatch.fnmatch(
                path,
                "app/src/main/java/com/micronizationlab/glafira/*Core*",
            )
            or path.startswith("desktop-bridge/")
            or path == ".github/scripts/run-android-instrumentation.sh"
        ):
            selected["android_acceptance"] = True

    if event_name in {"schedule", "workflow_dispatch"}:
        selected["android"] = True
        selected["ios"] = (root / "ios").is_dir()
        selected["agent"] = (root / "windows-agent").is_dir()
        selected["contracts"] = (root / "contracts").is_dir()
        selected["android_acceptance"] = True

    if selected["android_acceptance"] and not (
        root / ".github/scripts/run-android-instrumentation.sh"
    ).is_file():
        selected["android_acceptance"] = False

    return selected


def changed_paths_from_git() -> list[str]:
    event = os.environ["EVENT_NAME"]
    current_sha = os.environ["CURRENT_SHA"]
    if event == "pull_request":
        command = [
            "git", "diff", "--name-only",
            os.environ["PR_BASE"], os.environ["PR_HEAD"],
        ]
    elif (
        event == "push"
        and os.environ.get("PUSH_BEFORE")
        != "0000000000000000000000000000000000000000"
    ):
        command = [
            "git", "diff", "--name-only",
            os.environ["PUSH_BEFORE"], current_sha,
        ]
    else:
        command = ["git", "ls-tree", "-r", "--name-only", current_sha]
    result = subprocess.run(
        command, cwd=ROOT, check=True, capture_output=True, text=True
    )
    return [line for line in result.stdout.splitlines() if line]


def main() -> None:
    changed = changed_paths_from_git()
    selected = classify_paths(changed, os.environ["EVENT_NAME"])
    output_path = os.environ["GITHUB_OUTPUT"]
    with open(output_path, "a", encoding="utf-8") as output:
        for name, value in selected.items():
            output.write(f"{name}={str(value).lower()}\n")
    print("Changed paths:")
    for path in changed:
        print(f"  {path}")
    print("Selected suites:")
    for name, value in selected.items():
        print(f"  {name}={str(value).lower()}")


if __name__ == "__main__":
    main()
