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
# scoped_leg_ids: leg_ids minus betterleaks, which gates every file by design.
scoped_leg_ids() { leg_ids | tr ' ' '\n' | grep -vx 'betterleaks' | tr '\n' ' ' | sed 's/ *$//'; }
check_empty "respects exclude (rest_api → no legs)" \
	"$(printf 'sdks/python/src/opik/rest_api/x.py\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml | scoped_leg_ids)"
out=$(printf 'apps/opik-frontend/src/components/Foo.tsx\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "fe change emits fe-eslint"        '"id": "fe-eslint"'   "$out"
check_empty "fe non-plugin change omits no-private-fe-plugins" \
	"$(printf '%s' "$out" | leg_ids | grep -o 'no-private-fe-plugins' || true)"
out=$(printf 'README.md\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check_empty "unrelated file → no legs" "$(printf '%s' "$out" | scoped_leg_ids)"
check "any file emits the repo-wide betterleaks leg" '"id": "betterleaks"' "$out"
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
# swagger-resolve gates on spec inputs only (main sources/resources + pom), and
# needs the java toolchain to compile.
out=$(printf 'apps/opik-backend/src/main/resources/openapi_template.yml\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "spec input emits swagger-resolve"  '"id": "swagger-resolve"'  "$out"
check "swagger-resolve provisions java"   '"toolchain": "java"'      "$out"
check_empty "test-only java omits swagger-resolve" \
	"$(printf 'apps/opik-backend/src/test/java/com/comet/opik/FooTest.java\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml | leg_ids | grep -o 'swagger-resolve' || true)"

echo "precommit-hook-desc.py:"
# Single shared resolver both tables use. Substring match, TSV order honoured
# (ruff-format must win over ruff).
out=$(printf '🤖 ruff-format — optimizer\n🐍 ruff — python sdk\n☕ spotless — java backend\n' | python3 scripts/precommit-hook-desc.py)
check "ruff-format wins over ruff"  "$(printf 'ruff-format — optimizer\tFormat Python code')" "$out"
check "ruff maps to lint"           "$(printf 'ruff — python sdk\tLint + autofix Python')"      "$out"
check "spotless maps to java"       "$(printf 'spotless — java backend\tFormat Java code')"      "$out"

echo "hook-description coverage:"
# Every hook name in .pre-commit-config.yaml must resolve to a non-empty
# description, or it shows blank in the Code Quality timing comment. This is the
# guard for "added a hook but forgot its precommit-hook-descriptions.tsv entry".
# python reads the config directly (yaml) so a new hook can't slip the net.
missing=$(python3 - <<'PY'
import subprocess, sys, yaml
cfg = yaml.safe_load(open(".pre-commit-config.yaml"))
names = [h.get("name", h["id"]) for r in cfg.get("repos", []) for h in r.get("hooks", [])]
resolved = subprocess.run(
    ["python3", "scripts/precommit-hook-desc.py"],
    input="\n".join(names), capture_output=True, text=True, check=True,
).stdout.splitlines()
for line in resolved:
    name, _, desc = line.partition("\t")
    if name and not desc:
        print(name)
PY
)
check_empty "every configured hook has a description" "$missing"

echo "precommit-bumped-hooks.py:"
# Fixture configs: a repo whose hook id recurs across scopes (listed once), a
# repo left at its rev (not listed), a local repo (never bumped), and a pinned
# docker image entry (tag synced to the bumped rev).
bump_dir=$(mktemp -d)
cat >"$bump_dir/old.yaml" <<'EOF'
repos:
  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.1.0
    hooks:
      - id: ruff
  - repo: https://github.com/codespell-project/codespell
    rev: v2.0.0
    hooks:
      - id: codespell
  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.1.0
    hooks:
      - id: ruff
      - id: ruff-format
  - repo: local
    hooks:
      - id: spotless
        entry: scripts/precommit-spotless.sh
  - repo: https://github.com/hadolint/hadolint
    rev: v2.0.0
    hooks:
      - id: hadolint-docker
        entry: ghcr.io/hadolint/hadolint:v2.0.0 hadolint
EOF
sed 's/v0\.1\.0/v0.2.0/' "$bump_dir/old.yaml" >"$bump_dir/new.yaml"
bumped=$(python3 scripts/precommit-bumped-hooks.py "$bump_dir/old.yaml" "$bump_dir/new.yaml" | tr '\n' ' ')
# Delimited so the substring check is an exact match.
check "lists bumped ids once, in order" "|ruff ruff-format |" "|$bumped|"
check_empty "omits the unbumped repo" "$(printf '%s' "$bumped" | grep -o codespell || true)"
check_empty "no bump → no ids" \
	"$(python3 scripts/precommit-bumped-hooks.py "$bump_dir/old.yaml" "$bump_dir/old.yaml")"
# Bump hadolint's rev only; --sync-entry-tags must move the image tag with it.
awk '/hadolint\/hadolint$/{h=1} h && /rev:/{sub(/v2\.0\.0/, "v2.1.0"); h=0} {print}' \
	"$bump_dir/old.yaml" >"$bump_dir/new.yaml"
bumped=$(python3 scripts/precommit-bumped-hooks.py --sync-entry-tags "$bump_dir/old.yaml" "$bump_dir/new.yaml")
check "bumped docker hook is listed" "hadolint-docker" "$bumped"
check "entry tag synced to rev" "ghcr.io/hadolint/hadolint:v2.1.0 hadolint" "$(cat "$bump_dir/new.yaml")"
check "local entry untouched" "entry: scripts/precommit-spotless.sh" "$(cat "$bump_dir/new.yaml")"
check "codespell rev untouched" "rev: v2.0.0" "$(cat "$bump_dir/new.yaml")"
# A tag drifted from its rev (no rev bump): the sync rewrites the entry, so the
# hook must still be reported for a re-scan.
sed 's/hadolint:v2\.0\.0/hadolint:v1.9.0/' "$bump_dir/old.yaml" >"$bump_dir/drift.yaml"
cp "$bump_dir/drift.yaml" "$bump_dir/new.yaml"
check "tag-only resync is reported" "hadolint-docker" \
	"$(python3 scripts/precommit-bumped-hooks.py --sync-entry-tags "$bump_dir/drift.yaml" "$bump_dir/new.yaml")"
# Quoted revs must not leak their quotes into the image tag.
sed 's/rev: v2\.0\.0$/rev: "v2.1.0"/' "$bump_dir/old.yaml" >"$bump_dir/new.yaml"
python3 scripts/precommit-bumped-hooks.py --sync-entry-tags "$bump_dir/old.yaml" "$bump_dir/new.yaml" >/dev/null
check "quoted rev → unquoted tag" "ghcr.io/hadolint/hadolint:v2.1.0 hadolint" "$(cat "$bump_dir/new.yaml")"
# A config that fails validation (different repo list) is never rewritten.
sed -e 's|codespell-project/codespell|example/other|' -e 's/hadolint:v2\.0\.0/hadolint:v1.9.0/' \
	"$bump_dir/old.yaml" >"$bump_dir/new.yaml"
cp "$bump_dir/new.yaml" "$bump_dir/before.yaml"
python3 scripts/precommit-bumped-hooks.py --sync-entry-tags "$bump_dir/old.yaml" "$bump_dir/new.yaml" >/dev/null 2>&1 || true
check_empty "invalid config left unmodified" "$(diff "$bump_dir/before.yaml" "$bump_dir/new.yaml" || true)"
rm -rf "$bump_dir"

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
