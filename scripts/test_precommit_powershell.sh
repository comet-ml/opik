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
for t in bash sh env grep printf cat dirname pwd uname; do
	p=$(command -v "$t" 2>/dev/null) && ln -sf "$p" "$real_bin/$t"
done
out=$(PATH="$real_bin" scripts/precommit-powershell-check.sh opik.ps1 2>&1 || true)
rc=$(PATH="$real_bin" scripts/precommit-powershell-check.sh opik.ps1 >/dev/null 2>&1; echo $?)
check "skips when pwsh is absent" "skipping PowerShell checks locally" "$out"
check "points at the CI gate instead" "windows-latest" "$out"
check_exit "skip path exits 0" 0 "$rc"

# --- Engine selection: pwsh first, Windows PowerShell 5.1 as the fallback ----
# Stubs stand in for uname / pwsh / powershell.exe / cygpath, so the routing is
# asserted on any OS without a real Windows shell. Each stub engine just prints
# how it was invoked (printf, not echo: sh's echo would eat the \r in C:\repo).
stub() { # stub <dir> <name> <body>
	mkdir -p "$1"
	printf '#!/bin/sh\n%s\n' "$3" >"$1/$2"
	chmod +x "$1/$2"
}
win="$tmp/win-bin"
stub "$win" uname 'echo MINGW64_NT-10.0-19045'
stub "$win" powershell.exe 'printf "STUB powershell.exe %s\n" "$*"'
stub "$win" cygpath "printf '%s\\n' 'C:\\repo\\scripts\\precommit-powershell-check.ps1'"

out=$(PATH="$win:$real_bin" scripts/precommit-powershell-check.sh opik.ps1 2>&1 || true)
check "falls back to powershell.exe on Windows" "STUB powershell.exe" "$out"
check "passes it a Windows path to the checker" "-File C:\\repo\\scripts\\precommit-powershell-check.ps1 opik.ps1" "$out"
check "bypasses the default Restricted policy" "-ExecutionPolicy Bypass" "$out"

win_pwsh="$tmp/win-pwsh-bin"
stub "$win_pwsh" pwsh 'printf "STUB pwsh %s\n" "$*"'
out=$(PATH="$win_pwsh:$win:$real_bin" scripts/precommit-powershell-check.sh opik.ps1 2>&1 || true)
check "prefers pwsh when both engines exist" "STUB pwsh" "$out"

# WSL exposes the Windows host's powershell.exe on PATH, but it can't see Linux
# paths; the wrapper must not pick it up there.
wsl="$tmp/wsl-bin"
stub "$wsl" uname 'echo Linux'
stub "$wsl" powershell.exe 'printf "STUB powershell.exe %s\n" "$*"'
out=$(PATH="$wsl:$real_bin" scripts/precommit-powershell-check.sh opik.ps1 2>&1 || true)
check "ignores powershell.exe outside a Windows shell" "skipping PowerShell checks locally" "$out"

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

# --- Engine label: CI annotations and the summary name the engine ------------
# The two CI legs report the same findings; the label is what shows a finding
# only one leg reports is version-specific. Only the pwsh side is asserted
# here -- the 5.1 side can't be faked from 7, and the CI 5.1 leg exercises it.
out=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 -Annotate "$tmp/lint.ps1" 2>&1 || true)
check "annotations carry the engine label" "::[PowerShell 7." "$out"
out=$(scripts/precommit-powershell-check.sh "$tmp/clean.ps1" 2>&1 || true)
check "the summary names the engine" "passed under PowerShell 7." "$out"

# --- Missing analyzer: the install hint names the running engine ------------
# pwsh and Windows PowerShell keep modules apart, so a hint naming the wrong
# executable installs the analyzer where this engine can't see it. Hidden by
# pointing HOME at an empty dir, which moves pwsh's user module path; an
# all-users install stays visible, so this case skips itself there.
analyzer_version=$(grep -oE "\\\$RequiredAnalyzerVersion = '[0-9.]+'" scripts/precommit-powershell-check.ps1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
mkdir -p "$tmp/no-home"
out=$(HOME="$tmp/no-home" scripts/precommit-powershell-check.sh "$tmp/clean.ps1" 2>&1 || true)
rc=$(HOME="$tmp/no-home" scripts/precommit-powershell-check.sh "$tmp/clean.ps1" >/dev/null 2>&1; echo $?)
if printf '%s' "$out" | grep -qF "is not installed"; then
	check "the install hint names pwsh and the pinned version" \
		"Install it with: pwsh -Command \"Install-Module PSScriptAnalyzer -RequiredVersion $analyzer_version -Scope CurrentUser\"" "$out"
	check_exit "missing analyzer exits 2" 2 "$rc"
else
	echo "  (analyzer installed for all users — skipping the install-hint check)"
fi

# --- Annotation escaping: paths and messages can't break or forge commands ---
# Per GitHub's rules: `,` and `:` would truncate the file= property, `%` would
# be misdecoded, and a newline would end the command and start a new line the
# runner parses on its own -- here, a forged ::error:: annotation.
odd="$tmp/a,b:c%d.ps1"
cp "$tmp/lint.ps1" "$odd"
out=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 -Annotate "$odd" 2>&1 || true)
check "escapes , : and % in the file= property" "a%2Cb%3Ac%25d.ps1,line=" "$out"

no_forged() { # no_forged <name> <output>: no line may start a workflow command
	if printf '%s\n' "$2" | grep -qE '^(::error::|##\[error\])forged'; then
		echo "  FAIL: $1"
		fails=$((fails + 1))
	else
		echo "  ok: $1"
	fi
}
forged="$tmp/inj
##[error]forged.ps1"
cp "$tmp/lint.ps1" "$forged"
out=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 -Annotate "$forged" 2>&1 || true)
check "encodes a newline in the annotation" "inj%0A##[error]forged.ps1,line=" "$out"
no_forged "a newline in a path can't start a workflow command" "$out"

# PowerShell reads `x::y` as a provider path, so this one is rejected before it
# is analyzed -- the rejection message must not forge a command either.
forged_colons="$tmp/inj
::error::forged.ps1"
cp "$tmp/lint.ps1" "$forged_colons"
out=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 -Annotate "$forged_colons" 2>&1 || true)
check "rejects a provider-style path" "Path not found" "$out"
no_forged "the rejection message can't start a workflow command" "$out"

# Paths are literal, not wildcard patterns: [1] must not make a real file missing.
cp "$tmp/lint.ps1" "$tmp/a[1].ps1"
out=$(scripts/precommit-powershell-check.sh "$tmp/a[1].ps1" 2>&1 || true)
check "checks a file whose name contains brackets" "PSAvoidUsingInvokeExpression" "$out"

# --- 7-only syntax: must fail even though pwsh parses it ---------------------
# Users launch via Windows PowerShell 5.1, which cannot parse `??`. pwsh can, so
# the parse check passes this file; only PSUseCompatibleSyntax stands between it
# and a launcher that breaks for users.
cat >"$tmp/ps7only.ps1" <<'PS'
function Get-Port {
    param([string]$Port)
    return $Port ?? '5173'
}
PS
out=$(scripts/precommit-powershell-check.sh "$tmp/ps7only.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/ps7only.ps1" >/dev/null 2>&1; echo $?)
check "reports the 5.1 incompatibility" "PSUseCompatibleSyntax" "$out"
check_exit "7-only syntax exits 1" 1 "$rc"

# --- Version-specific commands: must fail on either target profile ----------
# A parameter or command is not syntax, so PSUseCompatibleSyntax passes these;
# only PSUseCompatibleCommands catches them. One fixture per target profile, so
# dropping either profile from the settings file fails a test. The assertions
# pin the profile version, not just the rule name: Get-WmiObject also trips
# PSAvoidUsingWMICmdlet, so exit 1 alone would not prove this rule fired.
cat >"$tmp/ps7cmd.ps1" <<'PS'
function Invoke-Each {
    param([int[]]$Items)
    $Items | ForEach-Object -Parallel { $_ * 2 }
}
PS
out=$(scripts/precommit-powershell-check.sh "$tmp/ps7cmd.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/ps7cmd.ps1" >/dev/null 2>&1; echo $?)
check "flags a 7-only parameter against the 5.1 profile" \
	"[PSUseCompatibleCommands] The parameter 'Parallel' is not available for command 'ForEach-Object' by default in PowerShell version '5.1" "$out"
check_exit "7-only parameter exits 1" 1 "$rc"

cat >"$tmp/ps51cmd.ps1" <<'PS'
function Get-OsName {
    return (Get-WmiObject -Class Win32_OperatingSystem).Caption
}
PS
out=$(scripts/precommit-powershell-check.sh "$tmp/ps51cmd.ps1" 2>&1 || true)
rc=$(scripts/precommit-powershell-check.sh "$tmp/ps51cmd.ps1" >/dev/null 2>&1; echo $?)
check "flags a 5.1-only command against the 7.0 profile" \
	"[PSUseCompatibleCommands] The command 'Get-WmiObject' is not available by default in PowerShell version '7.0" "$out"
check_exit "5.1-only command exits 1" 1 "$rc"

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

# --- Analyzer version is pinned consistently --------------------------------
# The workflow installs one version and the checker enforces another constant.
# If they drift, the gate validates against a rule set the committed baseline in
# PSScriptAnalyzerSettings.psd1 was never measured against.
wf_version=$(grep -oE 'ANALYZER_VERSION: *"[0-9.]+"' .github/workflows/powershell_checks.yml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
script_version=$(grep -oE "\\\$RequiredAnalyzerVersion = '[0-9.]+'" scripts/precommit-powershell-check.ps1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
check "workflow declares an analyzer version" "." "$wf_version"
if [ "$wf_version" = "$script_version" ]; then
	echo "  ok: workflow and checker pin the same analyzer version ($wf_version)"
else
	echo "  FAIL: analyzer version drift — workflow=$wf_version checker=$script_version"
	fails=$((fails + 1))
fi

# --- Two-engine matrix: both legs, and every step runs on the leg's shell ----
# The 5.1 leg is the only place the real 5.1 parser sees these scripts. Dropping
# it, or a step pinning its own shell, would quietly turn both legs into pwsh.
matrix=$(python3 - <<'PY'
import yaml
job = yaml.safe_load(open(".github/workflows/powershell_checks.yml"))["jobs"]["powershell-checks"]
problems = []
if sorted(job.get("strategy", {}).get("matrix", {}).get("shell", [])) != ["powershell", "pwsh"]:
    problems.append("matrix.shell is not exactly [pwsh, powershell]")
if job.get("defaults", {}).get("run", {}).get("shell") != "${{ matrix.shell }}":
    problems.append("defaults.run.shell does not use the matrix")
problems += ["step %r sets its own shell" % s.get("name") for s in job["steps"] if "shell" in s]
print("; ".join(problems) or "ok")
PY
)
if [ "$matrix" = "ok" ]; then
	echo "  ok: workflow runs every step under both pwsh and powershell"
else
	echo "  FAIL: two-engine matrix — $matrix"
	fails=$((fails + 1))
fi

# A caller passing a different version must be rejected, not silently accepted:
# this is what stops a PR moving the checker's constant away from the version CI
# actually installed.
rc=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 \
	-ExpectedAnalyzerVersion 9.9.9 "$tmp/clean.ps1" >/dev/null 2>&1; echo $?)
check_exit "mismatched -ExpectedAnalyzerVersion is rejected" 2 "$rc"

rc=$(pwsh -NoProfile -File scripts/precommit-powershell-check.ps1 \
	-ExpectedAnalyzerVersion "$script_version" "$tmp/clean.ps1" >/dev/null 2>&1; echo $?)
check_exit "matching -ExpectedAnalyzerVersion is accepted" 0 "$rc"

# --- Routing contract: config must match PowerShell paths -------------------
# The hooks' `files:` regexes are the routing contract. If an edit stops them
# matching PowerShell, the gate goes quiet rather than red, so assert the
# detector still emits both legs for a .ps1 change — including uppercase.
legs=$(printf 'opik.ps1\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "a .ps1 change routes to powershell-check" '"id": "powershell-check"' "$legs"
legs_upper=$(printf 'Tool.PS1\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "an uppercase .PS1 change routes too" '"id": "powershell-check"' "$legs_upper"
legs_cfg=$(printf '.pre-commit-config.yaml\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "a config change runs this self-test" '"id": "powershell-check-tests"' "$legs_cfg"
legs_wf=$(printf '.github/workflows/powershell_checks.yml\n' | python3 scripts/precommit-detect-hooks.py .pre-commit-config.yaml)
check "a workflow change runs this self-test" '"id": "powershell-check-tests"' "$legs_wf"

if [ "$fails" -eq 0 ]; then
	echo "All PowerShell check tests passed."
else
	echo "$fails test(s) failed."
	exit 1
fi
