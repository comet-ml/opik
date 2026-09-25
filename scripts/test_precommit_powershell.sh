#!/usr/bin/env bash
# Tests for the PowerShell check (scripts/precommit-powershell-check.{sh,ps1}).
#
# The checker is a gate: the failure paths matter more than the passing one, so
# they are asserted explicitly here rather than assumed. Fixtures are written to
# a temp dir, never into the repo, so a failing run leaves no stray .ps1 behind.
#
# Run from the repo root: scripts/test_precommit_powershell.sh
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
check_exit() { # check_exit <name> <expected-code> <actual-code>
	if [ "$2" = "$3" ]; then
		echo "  ok: $1"
	else
		echo "  FAIL: $1 (expected exit $2, got $3)"
		fails=$((fails + 1))
	fi
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "precommit-powershell-check.sh:"

# --- No PowerShell available: the wrapper must skip, not fail ----------------
# A Linux/macOS contributor without pwsh should still be able to commit; the
# windows-latest job is the gate that actually has to run.
#
# Simulated by shadowing pwsh with a stub that reports "not found" to
# `command -v`, rather than by emptying PATH -- the wrapper needs a working
# PATH to run at all (its own shebang resolves through it).
real_bin="$tmp/real-bin"
mkdir -p "$real_bin"
for t in bash sh env grep printf cat dirname pwd; do
	p=$(command -v "$t" 2>/dev/null) && ln -sf "$p" "$real_bin/$t"
done
out=$(PATH="$real_bin" scripts/precommit-powershell-check.sh opik.ps1 2>&1 || true)
rc=$(PATH="$real_bin" scripts/precommit-powershell-check.sh opik.ps1 >/dev/null 2>&1; echo $?)
check "skips when pwsh is absent" "skipping PowerShell checks locally" "$out"
check "points at the CI gate instead" "windows-latest" "$out"
check_exit "skip path exits 0" 0 "$rc"

if ! command -v pwsh >/dev/null 2>&1; then
	echo "  (pwsh not installed — skipping the checks that need it)"
	if [ "$fails" -eq 0 ]; then
		echo "All PowerShell check tests passed."
		exit 0
	fi
	echo "$fails test(s) failed."
	exit 1
fi

# --- Clean file: passes ------------------------------------------------------
cat >"$tmp/clean.ps1" <<'PS'
function Get-Greeting {
    param([string]$Name)
    return "hello $Name"
}
PS
rc=$(scripts/precommit-powershell-check.sh "$tmp/clean.ps1" >/dev/null 2>&1; echo $?)
check_exit "clean file passes" 0 "$rc"

# --- Syntax error: parse check must fail ------------------------------------
# The check's whole reason to exist: a .ps1 that does not parse must not reach
# Windows users. Asserted here so the gate is known to be able to fail.
cat >"$tmp/broken.ps1" <<'PS'
function Broken-Thing {
    Write-Host "unterminated
PS
out=$(scripts/precommit-powershell-check.sh "$tmp/broken.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/broken.ps1" >/dev/null 2>&1; echo $?)
check "reports the parse failure" "parse error" "$out"
check "names the offending file" "broken.ps1" "$out"
check_exit "syntax error exits 1" 1 "$rc"

# --- Analyzer violation: lint must fail -------------------------------------
# Distinct from the parse case: this file is syntactically valid, so only the
# analyzer half can catch it. Invoke-Expression is flagged at Warning severity.
cat >"$tmp/lint.ps1" <<'PS'
function Invoke-Thing {
    Invoke-Expression "Get-Date"
}
PS
out=$(scripts/precommit-powershell-check.sh "$tmp/lint.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/lint.ps1" >/dev/null 2>&1; echo $?)
check "reports the analyzer finding" "PSAvoidUsingInvokeExpression" "$out"
check_exit "analyzer violation exits 1" 1 "$rc"

# --- Missing path: must reject, not silently pass ---------------------------
# A typo'd or stale path previously fell through to "nothing to check" and
# reported success, which is the one failure mode a gate must never have.
out=$(scripts/precommit-powershell-check.sh "$tmp/does-not-exist.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/does-not-exist.ps1" >/dev/null 2>&1; echo $?)
check "reports the missing path" "Path not found" "$out"
check_exit "missing path exits 2" 2 "$rc"

# --- Suppressed rules stay suppressed ---------------------------------------
# The committed baseline depends on these exclusions; if one silently stopped
# applying, the gate would go red on untouched code.
cat >"$tmp/suppressed.ps1" <<'PS'
function Stop-Things {
    Write-Host "stopping"
}
PS
rc=$(scripts/precommit-powershell-check.sh "$tmp/suppressed.ps1" >/dev/null 2>&1; echo $?)
check_exit "excluded rules do not fail the gate" 0 "$rc"

# --- The repo's own scripts are green ---------------------------------------
rc=$(scripts/precommit-powershell-check.sh >/dev/null 2>&1; echo $?)
check_exit "repo PowerShell files pass full discovery" 0 "$rc"

if [ "$fails" -eq 0 ]; then
	echo "All PowerShell check tests passed."
else
	echo "$fails test(s) failed."
	exit 1
fi
