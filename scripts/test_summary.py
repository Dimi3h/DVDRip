#!/usr/bin/env python3
"""Sums up JUnit XML results and fails when nothing ran, so an empty test run never passes.

Usage: test_summary.py [--no-skipped] <directory with TEST-*.xml files, searched recursively>

With --no-skipped, a skipped test fails the run too (CI gives every test what it needs).
"""
import pathlib
import sys
import xml.etree.ElementTree as ET


def main(directory: str, no_skipped: bool) -> int:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in sorted(pathlib.Path(directory).rglob("TEST-*.xml")):
        suites = ET.parse(path).getroot()
        for suite in suites.iter("testsuite"):
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            for case in suite.iter("testcase"):
                failed = case.find("failure") is not None or case.find("error") is not None
                skipped = case.find("skipped") is not None
                label = "FAIL " if failed else "SKIP " if skipped else "ok   "
                print(label + f"{case.get('classname')}.{case.get('name')}")
    print(", ".join(f"{value} {key}" for key, value in totals.items()))
    if totals["tests"] == 0:
        print("No tests ran.")
        return 1
    if no_skipped and totals["skipped"]:
        print("Tests were skipped.")
        return 1
    return 1 if totals["failures"] or totals["errors"] else 0


if __name__ == "__main__":
    arguments = sys.argv[1:]
    sys.exit(main(arguments[-1], "--no-skipped" in arguments[:-1]))
