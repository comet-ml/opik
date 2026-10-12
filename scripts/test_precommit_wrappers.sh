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
# Private TMPDIR so wrappers that cache under it (precommit-pmd.sh) can neither
# see a real cache left by a developer's earlier run nor leave one behind. Without
# this the suite passes locally on ambient state and fails on a clean CI runner.
tmp_home=$(mktemp -d)
trap 'rm -rf "$stub_dir" "$tmp_home"' EXIT
export TMPDIR="$tmp_home"
for tool in mvn npx; do
	cat >"$stub_dir/$tool" <<EOF
#!/bin/sh
echo "$tool \$*"
EOF
	chmod +x "$stub_dir/$tool"
done
# `java` additionally writes the empty-but-valid report its caller demands:
# precommit-pmd.sh treats a missing report as a hard failure (a gate that can't
# read its own output must not pass), so echoing the args alone would abort.
cat >"$stub_dir/java" <<'EOF'
#!/bin/sh
echo "java $*"
for arg in "$@"; do
	case "$prev" in --report-file)
		printf '<?xml version="1.0" encoding="UTF-8"?>\n<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="stub" timestamp="stub"></pmd>\n' >"$arg" ;;
	esac
	prev=$arg
done
EOF
chmod +x "$stub_dir/java"
export PATH="$stub_dir:$PATH"

echo "precommit-spotless.sh:"
out=$(scripts/precommit-spotless.sh apps/opik-backend/src/main/java/com/comet/opik/Foo.java 2>&1)
check "passes -DspotlessFiles regex" "-DspotlessFiles=" "$out"
check "targets the changed file"     "Foo"              "$out"
check "escapes the dot in the regex" 'Foo\.java'        "$out"
check_empty "no-arg is a no-op"      "$(scripts/precommit-spotless.sh 2>&1)"

echo "precommit-pmd.sh:"
# The lib cache is pre-created so the wrapper skips Maven resolution and goes
# straight to the (stubbed) java invocation. The jar names must satisfy the
# wrapper's cache_ready() check — a generic stub.jar would send it into real
# Maven resolution, which the stubbed mvn can't fulfil, aborting the suite.
pmd_version=$(sed -n 's/^PMD_VERSION="\(.*\)"$/\1/p' scripts/precommit-pmd.sh)
pmd_lib="${TMPDIR:-/tmp}/opik-pmd-${pmd_version}"
mkdir -p "$pmd_lib"
: >"$pmd_lib/pmd-cli-${pmd_version}.jar"
: >"$pmd_lib/pmd-java-${pmd_version}.jar"
out=$(scripts/precommit-pmd.sh apps/opik-backend/src/main/java/com/comet/opik/Foo.java 2>&1)
check "invokes the PMD CLI"          "net.sourceforge.pmd.cli.PmdCli" "$out"
check "passes the repo ruleset"      "apps/opik-backend/pmd-ruleset.xml" "$out"
check "passes a --file-list"         "--file-list"                    "$out"
check "asks PMD for suppressed hits" "--show-suppressed"              "$out"
check_empty "no-arg is a no-op"      "$(scripts/precommit-pmd.sh 2>&1)"

# Suppressions of the FQN rule must state a reason. The validator reads PMD's XML
# report (--show-suppressed), so these cases drive it with synthetic reports —
# hermetic, and no real PMD needed. Covers every documented reason form plus the
# malformed shapes earlier grep-based versions waved through (OPIK-7832 review).
echo "precommit-pmd-suppressions.py:"
pmd_fix=$(mktemp -d)
sup=scripts/precommit-pmd-suppressions.py
MSG="Inline fully-qualified name 'Date': import the type at the top of the file"

# report <file> <suppressiontype> <usermsg> — writes a one-suppression XML report.
report() {
	cat >"$pmd_fix/report.xml" <<XEOF
<?xml version="1.0" encoding="UTF-8"?>
<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.26.0" timestamp="2026-01-01T00:00:00">
<suppressedviolation filename="$1" suppressiontype="$2" msg="$MSG" usermsg="$3"></suppressedviolation>
</pmd>
XEOF
}

# --- // NOPMD: the reason arrives already parsed by PMD, in usermsg ---
report "/x/C.java" "//nopmd" ""
check_empty "rejects bare // NOPMD" "$(python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"
report "/x/C.java" "//nopmd" " - "
check_empty "rejects // NOPMD with only a dash" "$(python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"
report "/x/C.java" "//nopmd" " - collides with the imported java.util.Date"
check "allows // NOPMD with a reason" "ok" "$(python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"

# --- @SuppressWarnings: usermsg is always empty, so the source is consulted ---
# name:::java-source — rejected (no explicit suppression note next to the annotation)
while IFS=':::' read -r name src; do
	[ -n "$name" ] || continue
	printf '%b' "$src" >"$pmd_fix/$name.java"
	report "$pmd_fix/$name.java" "@suppresswarnings" ""
	check_empty "rejects $name" "$(python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"
done <<'CASES'
bare-annotation:::class C {\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
annotation-after-multiplication:::class C {\n    int x = 2 * 3;\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
annotation-after-empty-comment:::class C {\n    //\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
annotation-after-code-with-comment-marker:::class C {\n    int value = 1; /* */\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
annotation-after-unrelated-javadoc:::class C {\n    /** Documents the class. */\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
CASES

# name:::java-source — accepted (an explicit marker carrying prose)
while IFS=':::' read -r name src; do
	[ -n "$name" ] || continue
	printf '%b' "$src" >"$pmd_fix/$name.java"
	report "$pmd_fix/$name.java" "@suppresswarnings" ""
	check "allows $name" "ok" "$(python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"
done <<'CASES'
marker-comment-above:::class C {\n    // NOPMD - collides with the imported java.util.Date\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
marker-comment-on-annotation:::class C {\n    @SuppressWarnings("PMD.InlineFullyQualifiedName") // NOPMD - collides with java.util.Date\n    java.sql.Date d;\n}\n
rule-named-in-comment-above:::class C {\n    // InlineFullyQualifiedName: collides with the imported java.util.Date\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n
multi-value-annotation-with-marker:::class C {\n    // NOPMD - collides with the imported java.util.Date\n    @SuppressWarnings({"PMD.InlineFullyQualifiedName", "unchecked"})\n    java.sql.Date d;\n}\n
CASES

# Suppressions of OTHER rules never reach this check: PMD only reports suppressions
# it honoured for rules in our ruleset, so a foreign msg must be ignored.
cat >"$pmd_fix/report.xml" <<XEOF
<?xml version="1.0" encoding="UTF-8"?>
<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.26.0" timestamp="2026-01-01T00:00:00">
<suppressedviolation filename="/x/C.java" suppressiontype="//nopmd" msg="Avoid unused local variables such as 'x'." usermsg=""></suppressedviolation>
</pmd>
XEOF
check "ignores another rule's bare NOPMD" "ok" "$(python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"

# Fail closed: a report that can't be read means suppressions were never checked.
check_empty "fails closed on a missing report" \
	"$(python3 "$sup" "$pmd_fix/does-not-exist.xml" >/dev/null 2>&1 && echo accepted)"
printf 'not xml' >"$pmd_fix/bad.xml"
check_empty "fails closed on a malformed report" \
	"$(python3 "$sup" "$pmd_fix/bad.xml" >/dev/null 2>&1 && echo accepted)"
check_empty "fails closed with no argument" \
	"$(python3 "$sup" >/dev/null 2>&1 && echo accepted)"

# Going-forward-only scoping: an annotation suppression is validated only when the
# current change touches it (OPIK-7832 review). The scope normally comes from
# `git diff`; here it is injected via PMD_SUPPRESSION_CHANGED_LINES so the cases
# stay hermetic. An earlier version built a nested fixture repo, which twice
# leaked commits into the surrounding repository — a test must not be able to
# write to the repo it is testing.
echo "precommit-pmd-suppressions.py (changed-line scoping):"

# L.java layout: 1 class, 2 legacy annotation, 3 legacy decl,
#                4 marker comment, 5 new annotation, 6 new decl, 7 }
printf 'class L {\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date old;\n    // NOPMD - collides with the imported java.util.Date\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date added;\n}\n' >"$pmd_fix/L.java"
report "$pmd_fix/L.java" "@suppresswarnings" ""

# The legacy annotation (line 2, no marker) is only in scope when the change
# touches lines 1-3; the marked one (lines 4-6) is always fine.
check "legacy annotation passes when untouched" "ok" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=7 python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"
check "legacy annotation passes on an unrelated edit" "ok" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=4,5,6 python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"
check_empty "legacy annotation flagged when its declaration is edited" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=3 python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"
check_empty "legacy annotation flagged when the annotation itself is edited" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=2 python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"
check_empty "legacy annotation flagged when the line above is edited" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=1 python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"

# Scope unknown (no repo / no git) must validate everything, never skip.
check_empty "validates all annotations when scope is unknown" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=unknown python3 "$sup" "$pmd_fix/report.xml" >/dev/null 2>&1 && echo accepted)"

# A file whose only suppression carries a marker passes at any scope.
printf 'class M {\n    // NOPMD - collides with the imported java.util.Date\n    @SuppressWarnings("PMD.InlineFullyQualifiedName")\n    java.sql.Date d;\n}\n' >"$pmd_fix/M.java"
report "$pmd_fix/M.java" "@suppresswarnings" ""
check "a marked annotation passes even when in scope" "ok" \
	"$(PMD_SUPPRESSION_CHANGED_LINES=2,3,4 python3 "$sup" "$pmd_fix/report.xml" 2>/dev/null && echo ok)"
rm -rf "$pmd_fix"

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
