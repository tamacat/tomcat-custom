"""Test gate: requires >= 1 test and 0 failures, errors and skipped tests in the surefire reports.

Usage: python3 tools/check-test-reports.py <surefire-reports-dir>...
Prints a one-line summary (Markdown list item) on stdout; exits 1 if the gate fails.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

tests = failures = errors = skipped = 0
for d in sys.argv[1:]:
    for path in glob.glob(os.path.join(d, "TEST-*.xml")):
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))

print(f"- tests: {tests}, failures: {failures}, errors: {errors}, skipped: {skipped}")
if tests < 1 or failures or errors or skipped:
    print("::error::the test gate needs >= 1 test and 0 failures, errors and skips", file=sys.stderr)
    sys.exit(1)
