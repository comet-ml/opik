#!/usr/bin/env bash
# pre-commit wrapper for the PowerShell static checks (parse + PSScriptAnalyzer).
#
# Delegates to scripts/precommit-powershell-check.ps1, the same implementation the
# windows-latest CI job runs, so the local hook and the CI gate can't drift.
#
# PowerShell is not a given on a Linux/macOS dev machine, and requiring it to
# commit a change to an unrelated file would be hostile. When pwsh is missing the
# hook skips with a note; the windows-latest job in CI is the authoritative gate,
# so nothing reaches main unchecked.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_SCRIPT="${SCRIPT_DIR}/precommit-powershell-check.ps1"

if ! command -v pwsh >/dev/null 2>&1; then
    echo "pwsh not found — skipping PowerShell checks locally."
    echo "These run on windows-latest in CI regardless (.github/workflows/powershell_checks.yml)."
    echo "To run them here: brew install powershell  (or see https://aka.ms/powershell)"
    exit 0
fi

exec pwsh -NoProfile -File "$CHECK_SCRIPT" "$@"
