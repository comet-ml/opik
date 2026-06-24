#!/usr/bin/env bash
# Parse `pre-commit run --verbose` output into a slowest-first Markdown table.
# Reads the pre-commit log from $1 (or stdin) and writes Markdown to stdout.
#
# pre-commit prints, per hook:
#   <name>.....<dots>.....(Passed|Failed|Skipped|no files to check)
#   - duration: <n>s            # only for hooks that actually ran
# We pair each hook line with its following duration line (if any).
set -euo pipefail

src="${1:-/dev/stdin}"
# Shared description map, resolved next to this script so cwd doesn't matter.
desc_file="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/precommit-hook-descriptions.tsv"

awk '
  function flush() {
    if (name != "") {
      # status: prefer explicit Passed/Failed; Skipped/no-files => skipped
      st = "skipped"; icon = "\xE2\x8F\xAD\xEF\xB8\x8F"               # ⏭️
      if (result ~ /Passed/) { st = "passed"; icon = "\xE2\x9C\x85" } # ✅
      if (result ~ /Failed/) { st = "failed"; icon = "\xE2\x9D\x8C" } # ❌
      printf "%s\t%s\t%s\t%.2f\t%s\n", dur+0, name, icon, dur+0, st
    }
  }
  # A hook result line ends with Passed/Failed/Skipped (possibly preceded by
  # "(no files to check)"). The name is everything before the run of dots.
  /\.\.\.\.*(Passed|Failed|Skipped)$/ {
    flush()
    line = $0
    # strip the trailing dotted-result; capture leading name (before 3+ dots)
    match(line, /\.{3,}/)
    name = substr(line, 1, RSTART-1)
    result = substr(line, RSTART)
    dur = 0
    next
  }
  /^- duration:/ {
    d = $0; sub(/^- duration:[ ]*/, "", d); sub(/s.*$/, "", d)
    dur = d
    next
  }
  END { flush() }
' "$src" | sort -t$'\t' -k1 -gr | awk -F'\t' -v descfile="$desc_file" '
  # Descriptions come from the shared TSV (precommit-hook-descriptions.tsv) so
  # this table and the skipped table stay in lockstep. Keyword is matched as a
  # substring of the hook display name; file order is honoured (most specific
  # first, e.g. ruff-format before ruff).
  function desc(n,   i) {
    for (i = 1; i <= dn; i++) if (index(n, dkw[i]) > 0) return ddesc[i]
    return ""
  }
  BEGIN {
    ran_n = 0; skip_n = 0; total = 0
    dn = 0
    while ((getline line < descfile) > 0) {
      if (line ~ /^#/ || line == "") continue
      tab = index(line, "\t")
      if (tab == 0) continue
      dn++
      dkw[dn] = substr(line, 1, tab - 1)
      ddesc[dn] = substr(line, tab + 1)
    }
    close(descfile)
  }
  {
    row = sprintf("| %s | %s | %s | %.2fs |", $2, desc($2), $3, $4)
    if ($5 == "skipped") { skip[skip_n++] = row }
    else { ran[ran_n++] = row; total += $4 }
  }
  END {
    # Hooks that ran — always visible, slowest-first.
    print "| Hook | Description | Result | Duration |"
    print "|------|-------------|:------:|---------:|"
    if (ran_n == 0) print "| _no hooks ran_ |  |  |  |"
    for (i = 0; i < ran_n; i++) print ran[i]
    printf "| **Total (%d ran)** |  |  | **%.2fs** |\n", ran_n, total

    # Skipped hooks — collapsed; counts in the summary so no need to expand.
    if (skip_n > 0) {
      print ""
      printf "<details>\n<summary>\xE2\x8F\xAD\xEF\xB8\x8F %d skipped (no matching files)</summary>\n\n", skip_n
      print "| Hook | Description | Result | Duration |"
      print "|------|-------------|:------:|---------:|"
      for (i = 0; i < skip_n; i++) print skip[i]
      print ""
      print "</details>"
    }
  }
'
