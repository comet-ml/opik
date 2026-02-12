#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${SCRIPT_DIR}"

export PYTHONPATH="${SDK_DIR}/src:${PYTHONPATH:-}"
export OPIK_PROJECT_NAME="${OPIK_PROJECT_NAME:-pytest-integration-e2e}"
export OPIK_PYTEST_EXPERIMENT_ENABLED="${OPIK_PYTEST_EXPERIMENT_ENABLED:-true}"
export OPIK_PYTEST_EXPERIMENT_DATASET_NAME="${OPIK_PYTEST_EXPERIMENT_DATASET_NAME:-tests}"
export OPIK_PYTEST_EXPERIMENT_NAME_PREFIX="${OPIK_PYTEST_EXPERIMENT_NAME_PREFIX:-Pytest-E2E}"
export OPIK_PYTEST_PASSED_SCORE_NAME="${OPIK_PYTEST_PASSED_SCORE_NAME:-Passed}"
export OPIK_PYTEST_EPISODE_ARTIFACT_ENABLED="${OPIK_PYTEST_EPISODE_ARTIFACT_ENABLED:-true}"
export OPIK_PYTEST_EPISODE_ARTIFACT_PATH="${OPIK_PYTEST_EPISODE_ARTIFACT_PATH:-.opik/pytest_episode_report.json}"
export OPIK_EXAMPLE_LOG_LEVEL="${OPIK_EXAMPLE_LOG_LEVEL:-DEBUG}"
export OPIK_DEFAULT_FLUSH_TIMEOUT="${OPIK_DEFAULT_FLUSH_TIMEOUT:-5}"
export OPIK_BACKGROUND_WORKERS="${OPIK_BACKGROUND_WORKERS:-1}"
export OPIK_FILE_UPLOAD_BACKGROUND_WORKERS="${OPIK_FILE_UPLOAD_BACKGROUND_WORKERS:-1}"
export OPIK_SENTRY_ENABLE="${OPIK_SENTRY_ENABLE:-false}"
export OPIK_ENABLE_LITELLM_MODELS_MONITORING="${OPIK_ENABLE_LITELLM_MODELS_MONITORING:-false}"
export PYTEST_DISABLE_PLUGIN_AUTOLOAD="${PYTEST_DISABLE_PLUGIN_AUTOLOAD:-1}"
unset PYTEST_ADDOPTS

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
echo "Episode artifact: ${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}"
echo "Example logger level: ${OPIK_EXAMPLE_LOG_LEVEL}"
echo "Flush timeout (s): ${OPIK_DEFAULT_FLUSH_TIMEOUT}"
echo "Background workers: ${OPIK_BACKGROUND_WORKERS}"
echo "Python binary: ${PYTHON_BIN}"
echo "Pytest plugin autoload disabled: ${PYTEST_DISABLE_PLUGIN_AUTOLOAD}"
echo "Included test files: test_*.py"
echo "Pytest args: -m pytest -p opik.plugins.pytest -vv -s -rA --show-capture=no test_*.py ${*:-}"

"${PYTHON_BIN}" -m pytest -p opik.plugins.pytest -vv -s -rA --show-capture=no test_*.py "$@"

if [[ -f "${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}" ]]; then
  echo
  echo "Episode artifact generated:"
  echo "  ${SCRIPT_DIR}/${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}"
fi
