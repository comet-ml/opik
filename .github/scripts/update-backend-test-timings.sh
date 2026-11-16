#!/usr/bin/env bash
# Refreshes backend-test-timings.tsv, the measured run time per integration test class that
# discover-backend-tests.sh balances the CI groups with.
#
#   update-backend-test-timings.sh RUN_ID...  # integration Surefire reports uploaded by Backend Tests runs
#   update-backend-test-timings.sh DIR...     # Surefire reports on disk, one directory per run
#
# Each run is one sample, and a class's time is its median over the runs that ran it: a stalled job,
# flaky reruns, or being the first class of a job (container start) inflate one run but not the median.
# Classes in no run keep their entries, so a partial run only updates what it ran. Classes whose source
# file no longer exists are dropped. When GITHUB_OUTPUT is set, writes drift_percent (how much the
# measured top-level times moved, as a share of their total) and runs (samples used). Exits 2 when no
# source has any test reports.
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
timings="$script_dir/backend-test-timings.tsv"

[[ $# -ge 1 ]] || { sed -n '2,13p' "$0"; exit 1; }

dirs=()
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for source in "$@"; do
  if [[ $source =~ ^[0-9]+$ ]]; then
    # A run from before the reports were uploaded, or whose artifacts expired, has nothing to download
    if gh run download "$source" --repo "${GITHUB_REPOSITORY:-comet-ml/opik}" \
        --pattern 'backend-test-reports-integration-*' --dir "$tmp/$source" > /dev/null 2>&1; then
      dirs+=("$tmp/$source")
    else
      echo "Run $source: no integration test reports, skipped" >&2
    fi
  else
    dirs+=("$source")
  fi
done
[[ ${#dirs[@]} -gt 0 ]] || { echo "No test reports to read" >&2; exit 2; }

python3 - "$timings" "$repo_root/apps/opik-backend/src/test/java" "${GITHUB_OUTPUT:-/dev/null}" "${dirs[@]}" <<'PY'
import collections
import os
import statistics
import sys
import xml.etree.ElementTree as ET

timings, test_root, output, *dirs = sys.argv[1:]


def read_run(directory):
    """Seconds per top-level class and per direct @Nested class (Outer$Inner) in one run's reports."""
    # A top-level class is its suite time, summed over reports, since a class split across groups by
    # @Nested class reports once per group. A direct @Nested class (nested deeper counts towards its
    # direct ancestor) is the sum of its test cases, which discover-backend-tests.sh splits by.
    measured = collections.defaultdict(float)
    for root, _, files in os.walk(directory):
        for name in files:
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            suite = ET.parse(os.path.join(root, name)).getroot()
            measured[suite.get("name", "").split("$")[0]] += float(suite.get("time") or 0)
            for case in suite.iter("testcase"):
                parts = case.get("classname", "").split("$")
                if len(parts) > 1:
                    measured["$".join(parts[:2])] += float(case.get("time") or 0)
    return measured


samples = collections.defaultdict(list)
for directory in dirs:
    for cls, seconds in read_run(directory).items():
        samples[cls].append(seconds)
measured = {cls: statistics.median(values) for cls, values in samples.items()}

entries = {}
if os.path.exists(timings):
    with open(timings) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            cls, seconds = line.rstrip("\n").split("\t")
            entries[cls] = float(seconds)
previous = dict(entries)
entries.update(measured)


def exists(cls):
    return os.path.exists(os.path.join(test_root, *cls.split("$")[0].split(".")) + ".java")


kept = {cls: s for cls, s in entries.items() if exists(cls)}
with open(timings, "w") as f:
    f.write("# Seconds per integration test class, and per @Nested class (Outer$Inner), from Surefire reports.\n")
    f.write("# Written by update-backend-test-timings.sh; read by discover-backend-tests.sh to balance the CI groups.\n")
    for cls in sorted(kept):
        f.write(f"{cls}\t{kept[cls]:.0f}\n")

top = [cls for cls in kept if "$" not in cls]
moved = sum(abs(kept[cls] - previous.get(cls, 0)) for cls in top)
drift = 100 * moved / max(sum(kept[cls] for cls in top), 1)
print(f"{len(dirs)} runs, {len(measured)} classes measured, {len(kept)} written to {timings}; "
      f"top-level times moved {moved:.0f}s ({drift:.1f}%)")
with open(output, "a") as f:
    f.write(f"drift_percent={drift:.0f}\nruns={len(dirs)}\n")
PY
