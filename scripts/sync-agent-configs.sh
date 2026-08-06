#!/usr/bin/env bash
# Regenerate the agent-config surfaces the user opted into (detected by folder
# presence -- we never create one they haven't set up), from THIS tree's
# .agents/. Runs the same way in the main checkout and in a worktree: each tree
# owns its own .claude/.cursor/.codex, generated from its own .agents/, so a
# worktree on a skill-editing branch gets that branch's skills (not main's).
set -euo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# Each surface is independent: warn on failure rather than let set -e abort the rest.
ran_any=0
[[ -d ".claude" ]] && { make claude || echo "WARN: make claude failed." >&2; ran_any=1; }
[[ -L ".cursor" ]] && { make cursor || echo "WARN: make cursor failed." >&2; ran_any=1; }
{ [[ -L ".codex" || -f "AGENTS.override.md" ]]; } && { make codex || echo "WARN: make codex failed." >&2; ran_any=1; }

if [[ "$ran_any" -eq 0 ]]; then
    echo "No agent-config surfaces opted in; nothing to sync."
else
    # Refresh the source-of-truth manifest so `make check` sees this tree as synced.
    "$(dirname "${BASH_SOURCE[0]}")/agent-configs-manifest.sh" write
fi
