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

json="${1:-[]}"

printf '%s' "$json" | python3 -c '
import json, sys
items = json.load(sys.stdin)
if not items:
    sys.exit(0)
print("")
print("<details>")
print("<summary>⏭️ %d skipped (no matching files changed)</summary>\n" % len(items))
print("| Hook | Result |")
print("|------|:------:|")
for it in items:
    name = it.get("name") or it.get("id")
    print("| %s | ⏭️ |" % name)
print("")
print("</details>")
'
