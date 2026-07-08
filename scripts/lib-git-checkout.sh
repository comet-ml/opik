# Shared helper: resolve the main checkout and current tree for a git repo,
# handling worktrees and symlinked path components. Source it (do not exec):
#
#   . "$(dirname "${BASH_SOURCE[0]}")/lib-git-checkout.sh"
#   resolve_checkout || exit 0   # exits 0 (via return) when not in a git repo
#
# After a successful call:
#   main_checkout  = the primary working tree (parent of the shared .git dir)
#   repo_root      = the current working tree (== main_checkout in the main
#                    checkout; a worktree path otherwise)
#
# Paths are physical (pwd -P) so a symlinked component -- e.g. macOS
# /tmp -> /private/tmp, or a symlinked home -- doesn't make the two differ
# textually when they're the same tree. Returns non-zero when not in a git repo.
resolve_checkout() {
    local gcd
    gcd="$(git rev-parse --git-common-dir 2>/dev/null || true)"
    [[ -n "$gcd" ]] || return 1
    gcd="$(cd "$(dirname "$gcd")" && pwd -P)/$(basename "$gcd")"
    main_checkout="$(dirname "$gcd")"
    repo_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
}
