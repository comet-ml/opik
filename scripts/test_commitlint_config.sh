#!/usr/bin/env bash
# Tests for commitlint.config.mjs: which headers pass and fail, in commit mode and
# in PR-title mode (OPIK_COMMITLINT_PR_TITLE=1). Runs as the commitlint-config-tests
# pre-commit hook, which puts the pinned commitlint on PATH.
# Run from the repo root: pre-commit run commitlint-config-tests --all-files
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fails=0
lint() { # lint <mode: commit|title> <message>
	if [ "$1" = title ]; then
		printf '%s\n' "$2" | OPIK_COMMITLINT_PR_TITLE=1 commitlint --config commitlint.config.mjs >/dev/null 2>&1
	else
		printf '%s\n' "$2" | commitlint --config commitlint.config.mjs >/dev/null 2>&1
	fi
}
accepts() { # accepts <mode> <message>
	if lint "$1" "$2"; then
		echo "  ok: $1 accepts: $2"
	else
		echo "  FAIL: $1 should accept: $2"
		fails=$((fails + 1))
	fi
}
rejects() { # rejects <mode> <message>
	if lint "$1" "$2"; then
		echo "  FAIL: $1 should reject: $2"
		fails=$((fails + 1))
	else
		echo "  ok: $1 rejects: $2"
	fi
}

echo "PR title mode:"
accepts title "[OPIK-1234] [BE] feat: add create trace endpoint"
accepts title "[issue-123] [FE] fix: guard empty state"
accepts title "[NA] [BE] Update model prices file"
accepts title "[DND-1779] [CI][GHA] tags written back to back"
accepts title "[CUST-1] [QA] any bracketed tag is accepted"
rejects title "fix(ci): no ticket prefix"
rejects title "Revision 2: no ticket prefix"
rejects title "[#9002] [SDK] fix: issue number without the issue- prefix"
rejects title "[OPIK-] [BE] feat: ticket without a number"
rejects title "wip"
# Bot-commit ignores must not exempt a human PR title.
rejects title "Update versions to 1.2.3 and bump base version to 1.2.4"
rejects title "Bump lodash from 4.17.20 to 4.17.21"
rejects title "Merge branch 'main' into feature"

echo "commit mode:"
accepts commit "[OPIK-1234] [BE] feat: add create trace endpoint"
accepts commit "fix(api): handle null feedback score"
accepts commit "test: cover trace creation"
accepts commit "Revision 2: rename after review"
accepts commit "Merge branch 'main' into danield/OPIK-1-x"
accepts commit "Update versions to 1.2.3 and bump base version to 1.2.4"
accepts commit "Optimised images with calibre/image-actions"
accepts commit "Bump lodash from 4.17.20 to 4.17.21"
rejects commit "wip stuff"
rejects commit "address review comments"
rejects commit "Revision two: not a number"
rejects commit "[OPIK-] [BE] feat: ticket without a number"

if [ "$fails" -gt 0 ]; then
	echo "$fails commitlint config test(s) failed."
	exit 1
fi
echo "All commitlint config tests passed."
