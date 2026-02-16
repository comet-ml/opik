#!/bin/bash
# Claude Code shell initialization
# Sources shell configs and sets up PATH for non-interactive bash sessions

# Homebrew (Apple Silicon and Intel)
[ -d "/opt/homebrew/bin" ] && export PATH="/opt/homebrew/bin:$PATH"
[ -d "/usr/local/bin" ] && export PATH="/usr/local/bin:$PATH"

# Source shell configs (suppress errors for shell-specific syntax)
for config in \
    "$HOME/.profile" \
    "$HOME/.bash_profile" \
    "$HOME/.bashrc" \
    "${ZDOTDIR:-$HOME}/.zprofile" \
    "${ZDOTDIR:-$HOME}/.zshrc"; do
    [ -f "$config" ] && source "$config" 2>/dev/null || true
done
