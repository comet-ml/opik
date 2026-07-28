#!/usr/bin/env bash
# Filter one matrix leg's `pre-commit run <id> --files ...` verbose output down
# to just the hook that actually ran.
#
# Hook ids aren't unique across scopes, so `pre-commit run <id> --files <paths>`
# runs the one scope whose files: regex matched and prints the same-id siblings
# as "(no files to check)Skipped". The summary table wants one row per linter
# that ran, so we keep only the non-skipped block: a hook name line ending in
# Passed/Failed plus its trailing `- hook id:` / `- duration:` lines.
#
# Usage: precommit-filter-leg-log.sh <hook-id> <raw-log>   (hook-id is currently
# informational; selection is by Passed/Failed result, which uniquely picks the
# scope that ran.)
set -euo pipefail

raw="${2:-/dev/stdin}"

awk '
  # A hook result line has pre-commit'\''s dotted leader and ends in
  # Passed/Failed/Skipped. Skipped is often preceded by "(no files to check)"
  # rather than dots, so require the 3+ dot run anywhere on the line, not
  # immediately before the result word (guards against diff text ending in a
  # result word under --show-diff-on-failure).
  /\.{3,}.*(Passed|Failed|Skipped)$/ {
    keep = ($0 ~ /(Passed|Failed)$/)   # drop Skipped sibling blocks
    if (keep) print
    next
  }
  # Detail lines (- hook id:, - duration:, diff output) belong to the current
  # block; emit them only when we kept its header.
  { if (keep) print }
' "$raw"
