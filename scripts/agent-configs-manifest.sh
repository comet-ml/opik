#!/usr/bin/env bash
# Source-of-truth hash for the agent configs. The manifest is a committed hash of
# everything under .agents/ that gets vendored into .claude/.cursor/.codex, so it
# travels with every branch and worktree. Two uses:
#
#   write  -- recompute and write .agents/.sync-manifest (run when .agents/ changes,
#             wired into `make agent-configs`)
#   check  -- compare the committed manifest against the current .agents/ and warn
#             if they differ (i.e. .agents/ was edited but not re-synced), and warn
#             if opted-in .claude/ output is missing (pulled but not regenerated).
#
# `check` exits non-zero on drift so it can gate pre-commit and CI. It never
# regenerates -- it only detects and tells you to run `make agent-configs`.
set -euo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

MANIFEST=".agents/.sync-manifest"
AGENTS_DIR=".agents"

# Deterministic hash of the vendored source: file paths + contents, sorted, so it
# is stable across machines and checkouts. Excludes the manifest itself and any
# generated/ subtree.
compute_hash() {
    find "$AGENTS_DIR" -type f \
        ! -name '.sync-manifest' \
        ! -path "$AGENTS_DIR/generated/*" \
        -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 shasum -a 256 \
    | shasum -a 256 \
    | cut -d' ' -f1
}

cmd="${1:-check}"
case "$cmd" in
    write)
        compute_hash > "$MANIFEST"
        echo "Wrote $MANIFEST ($(cut -c1-12 < "$MANIFEST")…)"
        ;;
    check)
        [[ -d "$AGENTS_DIR" ]] || { echo "No $AGENTS_DIR/ here; skipping agent-config check."; exit 0; }
        current="$(compute_hash)"
        committed="$(cat "$MANIFEST" 2>/dev/null || echo "")"

        if [[ "$current" != "$committed" ]]; then
            echo "⚠️  agent configs out of sync: $AGENTS_DIR/ changed but $MANIFEST is stale." >&2
            echo "    Run 'make agent-configs' to regenerate and refresh the manifest." >&2
            exit 1
        fi

        # Manifest matches source. If a surface is opted in but its output is
        # missing, this tree was pulled/created without regenerating.
        if [[ -d ".claude" ]] && [[ ! -d ".claude/skills" ]]; then
            echo "⚠️  .claude/ present but skills not generated in this tree." >&2
            echo "    Run 'make agent-configs' to regenerate." >&2
            exit 1
        fi

        echo "✓ agent configs in sync ($(printf '%s' "$current" | cut -c1-12)…)"
        ;;
    *)
        echo "Usage: agent-configs-manifest.sh [write|check]" >&2
        exit 2
        ;;
esac
