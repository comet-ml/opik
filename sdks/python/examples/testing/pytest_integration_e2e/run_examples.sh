#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${SCRIPT_DIR}"

export PYTHONPATH="${SDK_DIR}/src:${PYTHONPATH:-}"
export OPIK_PROJECT_NAME="${OPIK_PROJECT_NAME:-pytest-integration-e2e}"

PYTHON_BIN="${PYTHON_BIN:-}"
if [[ -z "${PYTHON_BIN}" ]]; then
  if command -v pyenv >/dev/null 2>&1; then
    PYTHON_BIN="$(pyenv which python)"
  else
    PYTHON_BIN="$(command -v python)"
  fi
fi

echo "Running pytest integration E2E examples..."
echo "Project: ${OPIK_PROJECT_NAME}"
echo "Python binary: ${PYTHON_BIN}"
TEST_FILES=(test_*.py)

echo "Included test files: ${TEST_FILES[*]}"
PYTEST_BASE_ARGS=(-m pytest -p no:opik -p opik.plugins.pytest -vv -s -rA --show-capture=no "${TEST_FILES[@]}")
printf "Pytest args: "
printf "%q " "${PYTEST_BASE_ARGS[@]}" "$@"
echo

"${PYTHON_BIN}" "${PYTEST_BASE_ARGS[@]}" "$@"
