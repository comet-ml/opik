#!/usr/bin/env bash
# Parse `pre-commit run --verbose` output into a slowest-first Markdown table.
# Reads the pre-commit log from $1 (or stdin) and writes Markdown to stdout.
#
# pre-commit prints, per hook:
#   <name>.....<dots>.....(Passed|Failed|Skipped|no files to check)
#   - duration: <n>s            # only for hooks that actually ran
# We pair each hook line with its following duration line (if any).
#
# In the matrix CI design every fragment fed here is a single hook that RAN
# (precommit-filter-leg-log.sh strips Skipped siblings), so this renders only
# ran hooks; the skipped list is owned by precommit-skipped-table.sh. Both
# resolve descriptions through the shared precommit-hook-desc.py.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="${1:-/dev/stdin}"

# Pass 1: pair each hook header with its duration → "dur<TAB>name<TAB>icon".
parsed="$(awk '
  function flush() {
    if (name != "") {
      icon = "\xE2\x9C\x85"                            # ✅ default (passed)
      if (result ~ /Failed/) icon = "\xE2\x9D\x8C"      # ❌
      printf "%s\t%s\t%s\n", dur+0, name, icon
    }
  }
  /\.\.\.\.*(Passed|Failed|Skipped)$/ {
    flush()
    line = $0
    match(line, /\.{3,}/)
    name = substr(line, 1, RSTART-1)
    result = substr(line, RSTART)
    dur = 0
    next
  }
  /^- duration:/ { d = $0; sub(/^- duration:[ ]*/, "", d); sub(/s.*$/, "", d); dur = d; next }
  END { flush() }
' "$src")"

# Resolve descriptions for the hook names via the shared resolver (single
# matching implementation; this table and the skipped table stay in lockstep).
descs="$(printf '%s\n' "$parsed" | cut -f2 | python3 "$here/precommit-hook-desc.py")"

# Pass 2: join descriptions, sort slowest-first, render. Read the name→desc map
# first, then the parsed rows (distinguished by NF: map has 2 fields, rows 3).
printf '%s\n===ROWS===\n%s\n' "$descs" "$parsed" | awk -F'\t' '
  BEGIN { in_rows = 0; total = 0; n = 0 }
  /^===ROWS===$/ { in_rows = 1; next }
  !in_rows { if (NF >= 1) desc[$1] = $2; next }
  {
    if ($2 == "") next
    rdur[n] = $1 + 0; rname[n] = $2; ricon[n] = $3; n++
    total += $1 + 0
  }
  END {
    # slowest-first by duration (simple insertion sort; n is tiny)
    for (i = 1; i < n; i++) {
      for (j = i; j > 0 && rdur[j-1] < rdur[j]; j--) {
        td=rdur[j];rdur[j]=rdur[j-1];rdur[j-1]=td
        tn=rname[j];rname[j]=rname[j-1];rname[j-1]=tn
        ti=ricon[j];ricon[j]=ricon[j-1];ricon[j-1]=ti
      }
    }
    print "| Hook | Description | Result | Duration |"
    print "|------|-------------|:------:|---------:|"
    if (n == 0) print "| _no hooks ran_ |  |  |  |"
    for (i = 0; i < n; i++)
      printf "| %s | %s | %s | %.2fs |\n", rname[i], desc[rname[i]], ricon[i], rdur[i]
    printf "| **Total (%d ran)** |  |  | **%.2fs** |\n", n, total
  }
'
