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
check "eslint uses --no-warn-ignored"  "--no-warn-ignored" "$out"
check "routes .css to stylelint"       "stylelint"       "$out"

echo "precommit-ts-sdk-lint.sh:"
out=$(scripts/precommit-ts-sdk-lint.sh sdks/typescript/src/opik/index.ts 2>&1)
check "strips sdks/typescript prefix"  "eslint"             "$out"
check "passes relative path"           "src/opik/index.ts"  "$out"
check "uses --no-warn-ignored"         "--no-warn-ignored"  "$out"

echo ""
if [ "$fails" -eq 0 ]; then echo "All wrapper smoke tests passed."; else echo "$fails test(s) FAILED."; exit 1; fi
