#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${SCRIPT_DIR}"

if [[ $# -lt 1 ]]; then
  echo "Usage: ./run_safe_pytest.sh <test_file_or_nodeid> [additional pytest args]"
  echo "Example: ./run_safe_pytest.sh test_llm_episode_policy_routing_e2e.py"
  echo "Example: ./run_safe_pytest.sh test_llm_episode_e2e.py::test_refund_episode_ci_gate[scenario0]"
  exit 1
fi

TARGET="$1"
shift || true

export PYTHONPATH="${SDK_DIR}/src:${PYTHONPATH:-}"
export OPIK_PROJECT_NAME="${OPIK_PROJECT_NAME:-pytest-integration-e2e}"
export OPIK_PYTEST_EXPERIMENT_ENABLED="${OPIK_PYTEST_EXPERIMENT_ENABLED:-true}"
export OPIK_PYTEST_EXPERIMENT_DATASET_NAME="${OPIK_PYTEST_EXPERIMENT_DATASET_NAME:-tests}"
export OPIK_PYTEST_EXPERIMENT_NAME_PREFIX="${OPIK_PYTEST_EXPERIMENT_NAME_PREFIX:-Pytest-E2E}"
export OPIK_PYTEST_PASSED_SCORE_NAME="${OPIK_PYTEST_PASSED_SCORE_NAME:-Passed}"
export OPIK_PYTEST_EPISODE_ARTIFACT_ENABLED="${OPIK_PYTEST_EPISODE_ARTIFACT_ENABLED:-true}"
export OPIK_PYTEST_EPISODE_ARTIFACT_PATH="${OPIK_PYTEST_EPISODE_ARTIFACT_PATH:-.opik/pytest_episode_report.json}"
export OPIK_EXAMPLE_LOG_LEVEL="${OPIK_EXAMPLE_LOG_LEVEL:-INFO}"
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

echo "Running safe pytest target: ${TARGET}"
echo "Python binary: ${PYTHON_BIN}"
echo "Pytest plugin autoload disabled: ${PYTEST_DISABLE_PLUGIN_AUTOLOAD}"
echo "Pytest args: -m pytest -p opik.plugins.pytest -vv -s -rA --show-capture=no ${*:-}"

"${PYTHON_BIN}" -m pytest -p opik.plugins.pytest -vv -s -rA --show-capture=no "${TARGET}" "$@"
