#!/usr/bin/env bash
# Regression tests for the agent-config sync tooling (convert-mcp.sh,
# sync-agent-configs.sh, sync-worktree-claude.sh). Runs entirely in a throwaway
# temp git repo -- it never touches the real checkout, its .claude/, or its git
# hooks -- so it is safe to run in CI. No test framework needed; plain bash.
#
# Usage: scripts/test-agent-configs.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS="$REPO_ROOT/scripts"
pass=0 fail=0

ok()   { printf '  ok   %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  FAIL %s\n' "$1"; fail=$((fail + 1)); }

# --- convert-mcp.sh: generates on first setup, never overrides an existing file ---
test_convert_mcp() {
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    printf '{"mcpServers":{"Foo":{"command":"x","args":[],"env":{}}}}' > "$tmp/in.json"

    # First setup: no output file -> generates.
    "$SCRIPTS/convert-mcp.sh" "$tmp/in.json" "$tmp/out.json" >/dev/null 2>&1
    if [[ -f "$tmp/out.json" ]] && jq -e '.mcpServers.Foo' "$tmp/out.json" >/dev/null 2>&1; then
        ok "convert-mcp generates on first setup"
    else
        bad "convert-mcp did not generate on first setup"
    fi

    # Existing file with a personal token -> left byte-for-byte untouched.
    printf '{"mcpServers":{"Mine":{"command":"c","args":[],"env":{"TOKEN":"secret"}}}}' > "$tmp/exist.json"
    local before; before="$(cat "$tmp/exist.json")"
    "$SCRIPTS/convert-mcp.sh" "$tmp/in.json" "$tmp/exist.json" >/dev/null 2>&1
    if [[ "$(cat "$tmp/exist.json")" == "$before" ]]; then
        ok "convert-mcp never overrides an existing .mcp.json"
    else
        bad "convert-mcp modified an existing .mcp.json"
    fi
}

# --- sync-agent-configs.sh: only opted-in surfaces are touched ---
test_opt_in_detection() {
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    (
        cd "$tmp"
        git init -q
        # No .claude/.cursor/.codex -> nothing opted in -> no-op, exit 0.
        out="$("$SCRIPTS/sync-agent-configs.sh" 2>&1)"
        echo "$out" | grep -q "nothing to sync" && exit 0 || { echo "$out"; exit 1; }
    )
    if [[ $? -eq 0 ]]; then
        ok "sync-agent-configs is a no-op when no surface is opted in"
    else
        bad "sync-agent-configs did not no-op on an unconfigured repo"
    fi
}

# --- sync-worktree-claude.sh: symlinks a worktree's config to the main checkout ---
test_worktree_symlink() {
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    (
        cd "$tmp"
        git init -q main >/dev/null 2>&1
        cd main
        git config user.email t@t; git config user.name t
        mkdir -p .claude/skills; echo x > .claude/skills/s.md
        echo '{"mcpServers":{}}' > .mcp.json
        git commit -q --allow-empty -m init
        git worktree add -q ../wt -b wt >/dev/null 2>&1
        cp "$SCRIPTS/sync-worktree-claude.sh" ../wt/scripts_stub.sh 2>/dev/null || true
        cd ../wt
        "$SCRIPTS/sync-worktree-claude.sh" >/dev/null 2>&1
        # .claude/skills should now be a symlink resolving to the main checkout's copy.
        [[ -L .claude/skills ]] && [[ -f .claude/skills/s.md ]] || exit 1
        [[ -L .mcp.json ]] || exit 1
        # rules must NOT be symlinked (branch-local).
        [[ -L .claude/rules ]] && exit 1
        exit 0
    )
    if [[ $? -eq 0 ]]; then
        ok "sync-worktree-claude symlinks skills+mcp to main (rules left alone)"
    else
        bad "sync-worktree-claude did not link the worktree correctly"
    fi
}

echo "🤖🔄🧪 agent-config sync tests:"
test_convert_mcp
test_opt_in_detection
test_worktree_symlink

echo "  ---"
echo "  $pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
