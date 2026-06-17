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
' "$src" | sort -t$'\t' -k1 -gr | awk -F'\t' '
  # What each hook does, matched by tool keyword in the display name. Keep these
  # in sync if hooks are added to .pre-commit-config.yaml.
  function desc(n) {
    if (n ~ /trim trailing whitespace/) return "Strip trailing whitespace"
    if (n ~ /fix end of files/)         return "Ensure files end in a newline"
    if (n ~ /ruff-format/)              return "Format Python code (ruff)"
    if (n ~ /ruff/)                     return "Lint + autofix Python (ruff)"
    if (n ~ /mypy/)                     return "Static type check"
    if (n ~ /pyupgrade/)                return "Modernize Python syntax"
    if (n ~ /check yaml/)               return "Validate YAML syntax"
    if (n ~ /check json/)               return "Validate JSON syntax"
    if (n ~ /check toml/)               return "Validate TOML syntax"
    if (n ~ /added large files/)        return "Block large files (>1MB)"
    if (n ~ /detect private key/)       return "Block committed private keys"
    if (n ~ /merge conflicts/)          return "Block merge-conflict markers"
    if (n ~ /case conflicts/)           return "Block case-only name clashes"
    if (n ~ /nbstripout/)               return "Strip notebook output"
    if (n ~ /markdownlint/)             return "Lint Markdown"
    if (n ~ /codespell/)                return "Fix common misspellings"
    if (n ~ /radon cc/)                 return "Cyclomatic-complexity gate"
    if (n ~ /radon raw/)                return "Raw size metrics gate"
    if (n ~ /xenon/)                    return "Fail on complexity thresholds"
    if (n ~ /lizard/)                   return "Cyclomatic-complexity gate"
    if (n ~ /vulture/)                  return "Find dead code"
    if (n ~ /helm-docs/)                return "Regenerate Helm chart README"
    if (n ~ /actionlint/)               return "Lint GitHub Actions workflows"
    if (n ~ /spotless/)                 return "Format Java code"
    if (n ~ /eslint/)                   return "Lint + autofix JS/TS"
    if (n ~ /typecheck/)                return "Whole-project tsc type check"
    if (n ~ /non-public FE plugins/)    return "Block non-public FE plugins"
    if (n ~ /wrapper smoke tests/)      return "Self-test the wrapper scripts"
    return ""
  }
  BEGIN {
    ran_n = 0; skip_n = 0; total = 0
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
