#!/usr/bin/env python3
"""Fail CI unless every requested JUnit report directory contains executed tests."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    roots = [Path(arg) for arg in sys.argv[1:]]
    if not roots:
        print("Usage: verify-junit-reports.py <report-dir> [<report-dir> ...]", file=sys.stderr)
        return 2

    total_suites = 0
    total_cases = 0
    failures = 0
    errors = 0
    skipped = 0

    for root in roots:
        if not root.exists():
            print(f"ERROR: expected JUnit report directory is missing: {root}", file=sys.stderr)
            return 1
        reports = sorted(root.rglob("TEST-*.xml"))
        if not reports:
            print(f"ERROR: no TEST-*.xml reports found under: {root}", file=sys.stderr)
            return 1
        root_cases = 0
        for report in reports:
            try:
                document = ET.parse(report).getroot()
            except (ET.ParseError, OSError) as exc:
                print(f"ERROR: cannot parse {report}: {exc}", file=sys.stderr)
                return 1
            suites = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
            for suite in suites:
                total_suites += 1
                cases = list(suite.findall("testcase"))
                root_cases += len(cases)
                total_cases += len(cases)
                failures += int(suite.attrib.get("failures", "0"))
                errors += int(suite.attrib.get("errors", "0"))
                skipped += int(suite.attrib.get("skipped", "0"))
        if root_cases == 0:
            print(f"ERROR: reports exist but contain zero test cases: {root}", file=sys.stderr)
            return 1
        print(f"Verified {root}: {len(reports)} report file(s), {root_cases} test case(s)")

    print(
        f"JUnit summary: {total_suites} suite(s), {total_cases} test case(s), "
        f"{failures} failure(s), {errors} error(s), {skipped} skipped"
    )
    if failures or errors:
        print("ERROR: JUnit reports contain failures or errors.", file=sys.stderr)
        return 1
    if total_cases <= skipped:
        print("ERROR: all reported tests are skipped; no test actually executed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
