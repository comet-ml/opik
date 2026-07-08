#!/usr/bin/env bash
# Refresh the agent-config surfaces the user opted into (detected by folder
# presence -- we never create one they haven't set up). In the main checkout,
# regenerate from .agents/; in a worktree, relink the shared config instead.
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/lib-git-checkout.sh"
resolve_checkout || { echo "Not in a git repository; skipping agent-config sync." >&2; exit 0; }

if [[ "$repo_root" != "$main_checkout" ]]; then
    if [[ -x "$repo_root/scripts/sync-worktree-claude.sh" ]]; then
        "$repo_root/scripts/sync-worktree-claude.sh"
    else
        echo "Worktree has no scripts/sync-worktree-claude.sh (older branch); rebase or run from a branch that has it." >&2
    fi
    exit 0
fi

cd "$main_checkout"

# Each surface is independent: warn on failure rather than let set -e abort the rest.
ran_any=0
[[ -d ".claude" ]] && { make claude || echo "WARN: make claude failed." >&2; ran_any=1; }
[[ -L ".cursor" ]] && { make cursor || echo "WARN: make cursor failed." >&2; ran_any=1; }
{ [[ -L ".codex" || -f "AGENTS.override.md" ]]; } && { make codex || echo "WARN: make codex failed." >&2; ran_any=1; }

[[ "$ran_any" -eq 0 ]] && echo "No agent-config surfaces opted in; nothing to sync." || true
