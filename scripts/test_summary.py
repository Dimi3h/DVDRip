#!/usr/bin/env python3
"""Sums up JUnit XML results and fails when nothing ran, so an empty test run never passes.

Usage: test_summary.py <directory with TEST-*.xml files, searched recursively>
"""
import pathlib
import sys
import xml.etree.ElementTree as ET


def main(directory: str) -> int:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in sorted(pathlib.Path(directory).rglob("TEST-*.xml")):
        suites = ET.parse(path).getroot()
        for suite in suites.iter("testsuite"):
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            for case in suite.iter("testcase"):
                failed = case.find("failure") is not None or case.find("error") is not None
                print(("FAIL " if failed else "ok   ") + f"{case.get('classname')}.{case.get('name')}")
    print(", ".join(f"{value} {key}" for key, value in totals.items()))
    if totals["tests"] == 0:
        print("No tests ran.")
        return 1
    return 1 if totals["failures"] or totals["errors"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
