#!/usr/bin/env bash
# Render the collapsible "skipped checks" section of the timing comment from the
# detect job's `skipped` JSON (hooks with no matching changed files, so no CI
# job ran). One row per skipped hook, ⏭️ result, no duration — coverage
# transparency so reviewers can see e.g. "Java skipped (no .java changed)".
#
# Usage: precommit-skipped-table.sh '<skipped-json-array>'
#   where the array is [{"name": "...", "id": "..."}, ...]
# Prints nothing when the array is empty.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
json="${1:-[]}"

# Names → descriptions via the shared resolver (precommit-hook-desc.py owns the
# single matching implementation; this script just lays out the table).
printf '%s' "$json" | HERE="$here" python3 -c '
import json, os, subprocess, sys

items = json.load(sys.stdin)
if not items:
    sys.exit(0)

names = [it.get("name") or it.get("id") for it in items]
resolver = os.path.join(os.environ["HERE"], "precommit-hook-desc.py")
out = subprocess.run(
    ["python3", resolver], input="\n".join(names), text=True, capture_output=True, check=True
).stdout
desc = {}
for line in out.splitlines():
    if "\t" in line:
        n, d = line.split("\t", 1)
        desc[n] = d

print("")
print("<details>")
print("<summary>⏭️ %d skipped (no matching files changed)</summary>\n" % len(items))
print("| Hook | Description | Result |")
print("|------|-------------|:------:|")
for n in names:
    print("| %s | %s | ⏭️ |" % (n, desc.get(n, "")))
print("")
print("</details>")
'
