#!/usr/bin/env bash
# Symlink a worktree's machine-local Claude config to the main checkout.
#
# Claude Code resolves .claude/* and .mcp.json from its launch dir and does not
# walk up to the parent repo; these paths are gitignored, so a fresh worktree
# would otherwise load zero skills and zero MCP servers. Symlinking to the main
# checkout gives one shared source of truth. .claude/rules is left alone: it is
# branch-specific (tracked) and rule content is migrating to Skills anyway.
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/lib-git-checkout.sh"
resolve_checkout || { echo "Not in a git repository; skipping worktree Claude sync." >&2; exit 0; }
worktree_root="$repo_root"

[[ "$worktree_root" == "$main_checkout" ]] && exit 0

link_to_main() {
    local rel="$1"
    local target="$main_checkout/$rel"
    local link="$worktree_root/$rel"

    [[ -e "$target" ]] || { echo "  skip $rel (not in main checkout)"; return; }
    [[ -L "$link" && "$(readlink "$link")" == "$target" ]] && { echo "  ok   $rel"; return; }

    # Don't clobber real (non-symlink) worktree-local content.
    if [[ -e "$link" && ! -L "$link" ]]; then
        echo "  WARN $rel is a real path in the worktree; not replacing it." >&2
        return
    fi

    mkdir -p "$(dirname "$link")"
    rm -f "$link"
    ln -s "$target" "$link"
    echo "  link $rel"
}

echo "Linking worktree Claude config to main checkout ($main_checkout)..."
link_to_main ".claude/skills"
link_to_main ".claude/commands"
link_to_main ".claude/agents"
link_to_main ".mcp.json"
echo "Worktree Claude config linked. Skills, commands, agents and MCP servers are shared with the main checkout."
