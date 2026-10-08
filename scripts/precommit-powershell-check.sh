#!/usr/bin/env bash
# pre-commit wrapper for the PowerShell static checks (parse + PSScriptAnalyzer).
#
# Delegates to scripts/precommit-powershell-check.ps1, the same implementation the
# windows-latest CI job runs, so the local hook and the CI gate can't drift.
#
# Engine choice: pwsh (PowerShell 7) when installed, on any OS. Otherwise, on a
# native Windows shell (Git Bash / MSYS / Cygwin), the built-in Windows
# PowerShell 5.1 -- every supported Windows ships it, and CI runs the checker
# under both engines, so either is a valid local gate. Not under WSL: its
# powershell.exe is the Windows host's, which cannot see Linux paths.
#
# PowerShell is not a given on a Linux/macOS dev machine, and requiring it to
# commit a change to an unrelated file would be hostile. When no engine is found
# the hook skips with a note; the windows-latest job in CI is the authoritative
# gate, so nothing reaches main unchecked.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_SCRIPT="${SCRIPT_DIR}/precommit-powershell-check.ps1"

if command -v pwsh >/dev/null 2>&1; then
    exec pwsh -NoProfile -File "$CHECK_SCRIPT" "$@"
fi

case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN*)
    if command -v powershell.exe >/dev/null 2>&1; then
        # powershell.exe is a native Windows binary: hand it a Windows path.
        # Bypass is process-scoped; Windows client editions default to
        # Restricted, which would refuse to run the checker at all.
        exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$CHECK_SCRIPT")" "$@"
    fi
    ;;
esac

echo "pwsh not found — skipping PowerShell checks locally."
echo "These run on windows-latest in CI regardless (.github/workflows/powershell_checks.yml)."
echo "To run them here: brew install powershell  (or see https://aka.ms/powershell)"
exit 0
