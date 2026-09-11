#!/usr/bin/env bash
# Block committing non-public frontend plugins to this public repository.
#
# Private plugins (e.g. a closed-source overlay) are symlinked into
# apps/opik-frontend/src/plugins/ at dev/build time. They must never be
# committed here. The .gitignore allowlist prevents accidental `git add`;
# this hook is the backstop against `git add -f` or a weakened ignore.
set -euo pipefail

PLUGINS_PREFIX="apps/opik-frontend/src/plugins/"
ALLOWED_REGEX="^apps/opik-frontend/src/plugins/(\.gitignore$|comet/|development/)"

violations="$(git diff --cached --name-only --diff-filter=AM \
  | { grep "^${PLUGINS_PREFIX}" || true; } \
  | { grep -vE "${ALLOWED_REGEX}" || true; })"

if [ -n "${violations}" ]; then
  echo "ERROR: refusing to commit non-public plugin files to the public repo:"
  echo "${violations}" | sed 's/^/  - /'
  echo
  echo "Private plugins are symlinked in and must not be committed here."
  echo "If this is a genuinely PUBLIC plugin, allowlist it in both:"
  echo "  - apps/opik-frontend/src/plugins/.gitignore"
  echo "  - scripts/check-public-fe-plugins.sh (ALLOWED_REGEX)"
  exit 1
fi
