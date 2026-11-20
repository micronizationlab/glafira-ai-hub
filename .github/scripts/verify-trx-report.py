#!/usr/bin/env python3
"""Verify that a .NET TRX report records real, passing test execution."""
from __future__ import annotations
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify-trx-report.py <results-directory>", file=sys.stderr)
        return 2
    root = Path(sys.argv[1])
    reports = sorted(root.glob("*.trx")) if root.is_dir() else []
    if not reports:
        print(f"ERROR: no TRX reports found in {root}", file=sys.stderr)
        return 1
    totals = {"total": 0, "executed": 0, "passed": 0, "failed": 0, "notExecuted": 0}
    for report in reports:
        try:
            document = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as exc:
            print(f"ERROR: cannot parse {report}: {exc}", file=sys.stderr)
            return 1
        counters = next((node for node in document.iter() if node.tag.endswith("Counters")), None)
        if counters is None:
            print(f"ERROR: TRX report has no result counters: {report}", file=sys.stderr)
            return 1
        for key in totals:
            totals[key] += int(counters.attrib.get(key, "0"))
    print("TRX summary: " + ", ".join(f"{key}={value}" for key, value in totals.items()))
    if totals["total"] <= 0 or totals["executed"] <= 0:
        print("ERROR: no Agent tests actually executed.", file=sys.stderr)
        return 1
    if totals["failed"] != 0 or totals["passed"] != totals["executed"]:
        print("ERROR: Agent test report contains failures or non-passing executions.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
