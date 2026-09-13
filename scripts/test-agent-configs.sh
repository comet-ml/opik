#!/usr/bin/env bash
# Regression tests for the agent-config sync tooling (convert-mcp.sh,
# sync-agent-configs.sh, agent-configs-manifest.sh). Runs entirely in throwaway
# temp git repos -- it never touches the real checkout, its .claude/, or its git
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

# --- agent-configs-manifest.sh: detects drift when .agents/ changes ---
test_manifest_drift() {
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    (
        cd "$tmp"
        git init -q
        git config user.email t@t; git config user.name t
        mkdir -p .agents/rules; echo "rule" > .agents/rules/a.mdc
        # Stub `git rev-parse --show-toplevel` isn't needed -- the script cd's to
        # toplevel itself, which is this temp repo.
        "$SCRIPTS/agent-configs-manifest.sh" write >/dev/null

        # In sync right after write.
        "$SCRIPTS/agent-configs-manifest.sh" check >/dev/null 2>&1 || exit 1

        # Edit .agents/ without re-writing the manifest -> check must FAIL.
        echo "changed" >> .agents/rules/a.mdc
        if "$SCRIPTS/agent-configs-manifest.sh" check >/dev/null 2>&1; then
            exit 1   # should have detected drift
        fi

        # Re-write manifest -> back in sync.
        "$SCRIPTS/agent-configs-manifest.sh" write >/dev/null
        "$SCRIPTS/agent-configs-manifest.sh" check >/dev/null 2>&1 || exit 1
        exit 0
    )
    if [[ $? -eq 0 ]]; then
        ok "manifest check detects .agents/ drift and clears after re-write"
    else
        bad "manifest check did not track .agents/ drift correctly"
    fi
}

# Build a minimal but REAL repo that `make agent-configs` can operate on: the
# actual Makefile + scripts, plus a tiny .agents/{rules,skills}. Echoes its path.
make_sandbox_repo() {
    local dir="$1"
    mkdir -p "$dir"
    cp "$REPO_ROOT/Makefile" "$dir/Makefile"
    mkdir -p "$dir/scripts"
    cp "$REPO_ROOT"/scripts/*.sh "$dir/scripts/" 2>/dev/null || true
    mkdir -p "$dir/.agents/rules" "$dir/.agents/skills/demo-skill"
    printf -- '---\nalwaysApply: true\n---\nrule body\n' > "$dir/.agents/rules/demo.mdc"
    printf -- '---\nname: demo-skill\ndescription: demo\n---\nSkill body.\n' \
        > "$dir/.agents/skills/demo-skill/SKILL.md"
}

# E2E: skills Claude Code would discover appear per-tree after `make agent-configs`,
# main and worktree stay independent, and a branch-local edit only affects its tree.
# Discoverability = the SKILL.md at the exact path Claude reads (<root>/.claude/skills/<n>/SKILL.md).
test_e2e_discoverability() {
    command -v make >/dev/null 2>&1 || { ok "e2e skipped (make unavailable)"; return; }
    local tmp; tmp="$(mktemp -d)"
    trap 'git -C "$tmp/main" worktree remove --force "$tmp/wt" 2>/dev/null; rm -rf "$tmp"' RETURN
    (
        set -e
        make_sandbox_repo "$tmp/main"
        cd "$tmp/main"
        git init -q; git config user.email t@t; git config user.name t
        git add -A; git commit -q -m init

        SK=".claude/skills/demo-skill/SKILL.md"

        # main: absent before, discoverable after opt-in sync
        [ ! -f "$SK" ]
        mkdir -p .claude
        make agent-configs >/dev/null 2>&1
        [ -f "$SK" ]

        # worktree: gets its OWN real (non-symlink) skill dir after its own sync
        git worktree add -q ../wt -b wt-e2e >/dev/null 2>&1
        cd ../wt
        [ ! -e "$SK" ]                      # not leaked from main
        mkdir -p .claude
        make agent-configs >/dev/null 2>&1
        [ -f "$SK" ]
        [ ! -L .claude/skills ]             # real per-tree dir, not a symlink to main

        # divergence: edit the skill in THIS worktree's .agents -> appears here only
        M="EDIT-MARKER-$$"
        echo "$M" >> .agents/skills/demo-skill/SKILL.md
        make agent-configs >/dev/null 2>&1
        grep -q "$M" "$SK"                                   # in the worktree
        ! grep -q "$M" "$tmp/main/$SK"                       # NOT in main
    )
    if [[ $? -eq 0 ]]; then
        ok "e2e: per-tree skills discoverable + independent (main vs worktree, with divergence)"
    else
        bad "e2e: per-tree discoverability/independence failed"
    fi
}

echo "🤖🔄🧪 agent-config sync tests:"
test_convert_mcp
test_opt_in_detection
test_manifest_drift
test_e2e_discoverability

echo "  ---"
echo "  $pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
