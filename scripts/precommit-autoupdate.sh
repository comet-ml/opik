#!/usr/bin/env bash
# Bump the remote hooks' pinned `rev:`s, then re-scan the whole repo with ONLY
# the bumped hooks. A new linter version can add rules that fail files no diff
# touched, and the bump PR's own Code Quality run lints just the changed config.
# Fixer hooks leave their fixes in the worktree so they ship in the bump PR.
#
# Writes a markdown report (bumped revs + per-hook re-scan result) to
# <report-file>. Findings are reported, not fatal: the PR is the place to fix
# them. Driven by .github/workflows/precommit_autoupdate.yml; runs locally too.
#
# Usage: scripts/precommit-autoupdate.sh <report-file>
set -euo pipefail

report="${1:?Usage: precommit-autoupdate.sh <report-file>}"
cd "$(git rev-parse --show-toplevel)"

old=$(mktemp)
log=$(mktemp)
first=$(mktemp)
trap 'rm -f "$old" "$log" "$first"' EXIT
cp .pre-commit-config.yaml "$old"

uvx pre-commit autoupdate | tee "$log"
bumped=$(python3 scripts/precommit-bumped-hooks.py --sync-entry-tags "$old" .pre-commit-config.yaml)

if [ -z "$bumped" ]; then
	echo "No hook revs or entries changed." >"$report"
	exit 0
fi

{
	echo "### Bumped revs"
	echo '```'
	# A repo recurs once per scope; autoupdate reports each occurrence.
	grep -F 'updating' "$log" | awk '!seen[$0]++' || true
	echo '```'
	echo
	echo "### Whole-repo re-scan (bumped hooks only)"
	echo
	echo "| Hook | Result |"
	echo "|---|---|"
} >"$report"

details=""
for id in $bumped; do
	# A fixer hook fails the run that rewrites files and passes the next one, so
	# a second run tells "auto-fixed" apart from findings that need a human. The
	# worktree snapshot keeps a transient first-run failure from posing as a fix.
	before=$(git diff | git hash-object --stdin)
	if uvx pre-commit run "$id" --all-files --hook-stage manual >"$first" 2>&1; then
		result="✅ passed"
		cp "$first" "$log"
	else
		changed=$([ "$(git diff | git hash-object --stdin)" != "$before" ] && echo yes || true)
		if uvx pre-commit run "$id" --all-files --hook-stage manual >"$log" 2>&1; then
			if [ -n "$changed" ]; then
				result="🔧 auto-fixed (fixes included in this PR)"
			else
				result="⚠️ passed only on retry, no files changed (flaky?)"
			fi
		else
			result="❌ findings"
			# Tail only: the PR body is capped at 65536 chars; the full log is in
			# the run. The fence outgrows any backtick run in the output, so hook
			# output can't close it and spill Markdown into the PR body.
			tail_log=$(tail -n 40 "$log" | cut -c1-200)
			longest=$(printf '%s' "$tail_log" | { grep -oE '`+' || true; } | awk '{ if (length > m) m = length } END { print m + 0 }')
			fence=$(printf '%*s' $((longest >= 3 ? longest + 1 : 3)) '' | tr ' ' '`')
			details+=$'\n'"<details><summary>${id}</summary>"$'\n\n'"${fence}"$'\n'"${tail_log}"$'\n'"${fence}"$'\n</details>\n'
		fi
		echo "::group::${id} — first run"
		cat "$first"
		echo "::endgroup::"
	fi
	echo "::group::${id} — ${result}"
	cat "$log"
	echo "::endgroup::"
	echo "| \`${id}\` | ${result} |" >>"$report"
done

if [ -n "$details" ]; then
	printf '\n### Findings\n%s' "$details" >>"$report"
fi
