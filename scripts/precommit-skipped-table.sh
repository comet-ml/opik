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
# Shared description map, resolved next to this script so cwd doesn't matter.
desc_file="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/precommit-hook-descriptions.tsv"

printf '%s' "$json" | DESC_FILE="$desc_file" python3 -c '
import json, os, sys

# Descriptions come from the shared TSV so this table and the ran table agree.
# Keyword matched as a substring of the hook display name; file order honoured
# (most specific first).
desc_map = []
with open(os.environ["DESC_FILE"]) as fh:
    for line in fh:
        line = line.rstrip("\n")
        if not line or line.startswith("#") or "\t" not in line:
            continue
        kw, d = line.split("\t", 1)
        desc_map.append((kw, d))

def desc(name):
    for kw, d in desc_map:
        if kw in name:
            return d
    return ""

items = json.load(sys.stdin)
if not items:
    sys.exit(0)
print("")
print("<details>")
print("<summary>⏭️ %d skipped (no matching files changed)</summary>\n" % len(items))
print("| Hook | Description | Result |")
print("|------|-------------|:------:|")
for it in items:
    name = it.get("name") or it.get("id")
    print("| %s | %s | ⏭️ |" % (name, desc(name)))
print("")
print("</details>")
'
