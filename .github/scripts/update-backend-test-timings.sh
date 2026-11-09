#!/usr/bin/env bash
# Refreshes backend-test-timings.tsv, the measured run time per integration test class that
# discover-backend-tests.sh balances the CI groups with.
#
#   update-backend-test-timings.sh RUN_ID   # integration Surefire reports uploaded by a Backend Tests run
#   update-backend-test-timings.sh DIR...   # Surefire reports on disk, e.g. apps/opik-backend/target/surefire-reports
#
# Classes in the reports replace their entries; every other entry is kept, so a partial run only
# updates what it ran. Classes whose source file no longer exists are dropped.
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
timings="$script_dir/backend-test-timings.tsv"

[[ $# -ge 1 ]] || { sed -n '2,9p' "$0"; exit 1; }

dirs=()
if [[ $# -eq 1 && $1 =~ ^[0-9]+$ ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  gh run download "$1" --repo comet-ml/opik --pattern 'backend-test-reports-integration-*' --dir "$tmp"
  dirs+=("$tmp")
else
  dirs=("$@")
fi

python3 - "$timings" "$repo_root/apps/opik-backend/src/test/java" "${dirs[@]}" <<'PY'
import collections
import os
import sys
import xml.etree.ElementTree as ET

timings, test_root, *dirs = sys.argv[1:]

# Per top-level class: its suite time, summed over reports, since a class split across groups by
# @Nested class reports once per group. Per direct @Nested class (Outer$Inner, nested deeper counts
# towards its direct ancestor): the sum of its test cases, which discover-backend-tests.sh splits by.
measured = collections.defaultdict(float)
for d in dirs:
    for root, _, files in os.walk(d):
        for name in files:
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            suite = ET.parse(os.path.join(root, name)).getroot()
            measured[suite.get("name", "").split("$")[0]] += float(suite.get("time") or 0)
            nested = collections.defaultdict(float)
            for case in suite.iter("testcase"):
                parts = case.get("classname", "").split("$")
                if len(parts) > 1:
                    nested["$".join(parts[:2])] += float(case.get("time") or 0)
            for cls, seconds in nested.items():
                measured[cls] += seconds

entries = {}
if os.path.exists(timings):
    with open(timings) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            cls, seconds = line.rstrip("\n").split("\t")
            entries[cls] = float(seconds)
entries.update(measured)

def exists(cls):
    return os.path.exists(os.path.join(test_root, *cls.split("$")[0].split(".")) + ".java")

kept = {cls: s for cls, s in entries.items() if exists(cls)}
with open(timings, "w") as f:
    f.write("# Seconds per integration test class, and per @Nested class (Outer$Inner), from Surefire reports.\n")
    f.write("# Written by update-backend-test-timings.sh; read by discover-backend-tests.sh to balance the CI groups.\n")
    for cls in sorted(kept):
        f.write(f"{cls}\t{kept[cls]:.0f}\n")
print(f"{len(measured)} classes measured, {len(kept)} written to {timings}")
PY
