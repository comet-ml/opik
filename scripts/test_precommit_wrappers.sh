#!/usr/bin/env bash
# Smoke tests for the pre-commit wrapper scripts. Stubs the real tools (mvn / npx)
# on PATH so we assert the wrappers' arg-routing/regex logic WITHOUT running Maven
# or ESLint. Run from the repo root: scripts/test_precommit_wrappers.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fails=0
check() { # check <name> <expected-substring> <actual>
	if printf '%s' "$3" | grep -qF -- "$2"; then
		echo "  ok: $1"
	else
		echo "  FAIL: $1"
		echo "    expected to contain: $2"
		echo "    actual: $3"
		fails=$((fails + 1))
	fi
}
check_empty() { # check_empty <name> <actual> — asserts no output
	if [ -z "$2" ]; then
		echo "  ok: $1"
	else
		echo "  FAIL: $1 (expected empty, got: $2)"
		fails=$((fails + 1))
	fi
}

# Stub bin dir placed first on PATH; each stub echoes its name + args so we can
# assert what the wrapper would have invoked.
stub_dir=$(mktemp -d)
trap 'rm -rf "$stub_dir"' EXIT
for tool in mvn npx; do
	cat >"$stub_dir/$tool" <<EOF
#!/bin/sh
echo "$tool \$*"
EOF
	chmod +x "$stub_dir/$tool"
done
export PATH="$stub_dir:$PATH"

echo "precommit-spotless.sh:"
out=$(scripts/precommit-spotless.sh apps/opik-backend/src/main/java/com/comet/opik/Foo.java 2>&1)
check "passes -DspotlessFiles regex" "-DspotlessFiles=" "$out"
check "targets the changed file"     "Foo"              "$out"
check "escapes the dot in the regex" 'Foo\.java'        "$out"
check_empty "no-arg is a no-op"      "$(scripts/precommit-spotless.sh 2>&1)"

echo "precommit-fe-lint.sh:"
out=$(scripts/precommit-fe-lint.sh apps/opik-frontend/src/a.tsx apps/opik-frontend/src/b.css 2>&1)
check "routes .tsx to eslint"          "eslint"          "$out"
check "eslint uses --max-warnings=0"   "--max-warnings=0" "$out"
# FE pins eslint v8.57.0, which rejects the v9-only --no-warn-ignored flag
# (OPIK-7237). Assert the wrapper does NOT pass it.
check_empty "fe eslint omits --no-warn-ignored" \
	"$(printf '%s' "$out" | grep -o -- '--no-warn-ignored' || true)"
check "routes .css to stylelint"       "stylelint"       "$out"

echo "precommit-ts-sdk-lint.sh:"
out=$(scripts/precommit-ts-sdk-lint.sh sdks/typescript/src/opik/index.ts 2>&1)
check "strips sdks/typescript prefix"  "eslint"             "$out"
check "passes relative path"           "src/opik/index.ts"  "$out"
check "uses --no-warn-ignored"         "--no-warn-ignored"  "$out"

echo "precommit-detect-hooks.py:"
# Path matching against the real config: a python src change → the python hooks
# (and not optimizer/guardrails); a frontend non-plugin change → fe hooks but
# NOT no-private-fe-plugins; an unrelated file → no legs.
out=$(printf 'sdks/python/src/opik/foo.py\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "python change emits a python leg" '"id": "ruff"'        "$out"
check "python leg carries the file"      'sdks/python/src/opik/foo.py' "$out"
# leg_ids <stdin-json> → space-joined ids of the running legs (ignores skipped).
leg_ids() { python3 -c 'import json,sys; print(" ".join(l["id"] for l in json.load(sys.stdin)["legs"]))'; }
check_empty "respects exclude (rest_api → no legs)" \
	"$(printf 'sdks/python/src/opik/rest_api/x.py\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml | leg_ids)"
out=$(printf 'apps/opik-frontend/src/components/Foo.tsx\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "fe change emits fe-eslint"        '"id": "fe-eslint"'   "$out"
check_empty "fe non-plugin change omits no-private-fe-plugins" \
	"$(printf '%s' "$out" | leg_ids | grep -o 'no-private-fe-plugins' || true)"
check_empty "unrelated file → no legs" \
	"$(printf 'README.md\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml | leg_ids)"
# types: gate — a non-.py file under sdks/opik_optimizer must NOT emit the
# python-only hooks (ruff/mypy/pyupgrade), or they'd spawn jobs that Skip at
# runtime and drop from the timing table.
out=$(printf 'sdks/opik_optimizer/Makefile\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check_empty "non-.py optimizer file omits ruff (types: gate)" \
	"$(printf '%s' "$out" | python3 -c 'import json,sys; print("ruff" if any(l["id"]=="ruff" for l in json.load(sys.stdin)["legs"]) else "")')"
# detect emits a skipped list so the summary can show coverage.
out=$(printf 'sdks/python/src/opik/foo.py\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "emits a skipped array"          '"skipped"'   "$out"
check "java is skipped on a py change" 'spotless'    "$(printf '%s' "$out" | python3 -c 'import json,sys; print(" ".join(s["id"] for s in json.load(sys.stdin)["skipped"]))')"

echo "precommit-hook-desc.py:"
# Single shared resolver both tables use. Substring match, TSV order honoured
# (ruff-format must win over ruff).
out=$(printf '🤖 ruff-format — optimizer\n🐍 ruff — python sdk\n☕ spotless — java backend\n' | python3 scripts/precommit-hook-desc.py)
check "ruff-format wins over ruff"  "$(printf 'ruff-format — optimizer\tFormat Python code')" "$out"
check "ruff maps to lint"           "$(printf 'ruff — python sdk\tLint + autofix Python')"      "$out"
check "spotless maps to java"       "$(printf 'spotless — java backend\tFormat Java code')"      "$out"

echo "precommit-skipped-table.sh:"
check_empty "empty skipped → no output"  "$(scripts/precommit-skipped-table.sh '[]')"
sk=$(scripts/precommit-skipped-table.sh '[{"name":"☕ spotless — java backend","id":"spotless"}]')
check "lists the skipped hook"   "spotless — java backend"  "$sk"
check "uses a collapsible block" "<details>"                "$sk"

echo "precommit-filter-leg-log.sh:"
# A single-hook verbose run prints the matched hook (Passed/Failed) plus same-id
# siblings as Skipped; the filter keeps only the non-skipped block.
filt=$(printf '%s\n' \
	'🐍 trim trailing whitespace — python sdk......Passed' \
	'- hook id: trailing-whitespace' \
	'- duration: 0.02s' \
	'🤖 trim trailing whitespace — optimizer......(no files to check)Skipped' \
	'- hook id: trailing-whitespace' \
	| scripts/precommit-filter-leg-log.sh trailing-whitespace)
check "keeps the hook that ran"     "python sdk"  "$filt"
check "keeps its duration"          "0.02s"       "$filt"
check_empty "drops the Skipped sibling" "$(printf '%s' "$filt" | grep -o 'optimizer' || true)"

echo ""
if [ "$fails" -eq 0 ]; then echo "All wrapper smoke tests passed."; else echo "$fails test(s) FAILED."; exit 1; fi
