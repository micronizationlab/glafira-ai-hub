#!/usr/bin/env bash
set -Eeuo pipefail

./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache --rerun-tasks --stacktrace

python3 - <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path("app/build/outputs/androidTest-results/connected")
reports = sorted(root.rglob("*.xml")) if root.exists() else []
if not reports:
    print(f"No instrumentation JUnit XML reports found under {root}", file=sys.stderr)
    raise SystemExit(1)

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for report in reports:
    try:
        document = ET.parse(report).getroot()
    except ET.ParseError as error:
        print(f"Invalid instrumentation report {report}: {error}", file=sys.stderr)
        raise SystemExit(1)
    suites = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
    for suite in suites:
        for key in totals:
            totals[key] += int(suite.attrib.get(key, "0"))

print(
    "Instrumentation JUnit summary: "
    f"{totals['tests']} test(s), {totals['failures']} failure(s), "
    f"{totals['errors']} error(s), {totals['skipped']} skipped"
)
if totals["tests"] < 1 or any(totals[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit(1)
PY
