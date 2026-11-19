#!/usr/bin/env python3
"""Fail a CI gate if its JUnit reports are missing, failing, or only skipped."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(directory: Path) -> str:
    files = sorted(directory.rglob("TEST*.xml"))
    if not files:
        raise ValueError(f"No JUnit XML reports found in {directory}")
    executed = skipped = failed = 0
    for path in files:
        root = ET.parse(path).getroot()
        cases = list(root.iter("testcase"))
        for case in cases:
            if case.find("skipped") is not None:
                skipped += 1
            else:
                executed += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed += 1
        # Include suite-level errors (for example a test runner failing to start).
        for suite in root.iter("testsuite"):
            if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
                raise ValueError(f"JUnit suite reports failures/errors: {path}")
    if failed:
        raise ValueError(f"{failed} failing test cases in {directory}")
    if not executed:
        raise ValueError(f"No test cases executed in {directory} ({skipped} skipped)")
    return f"{directory}: {executed} executed, {skipped} skipped, {len(files)} reports"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        for directory in args.directories:
            print(verify(directory))
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"JUnit verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
