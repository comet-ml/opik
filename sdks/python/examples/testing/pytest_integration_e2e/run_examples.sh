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

echo "Running pytest integration E2E examples..."
echo "Project: ${OPIK_PROJECT_NAME}"
echo "Episode artifact: ${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}"
echo "Pytest args: -vv -s -rA ${*:-}"

pytest -vv -s -rA test_*.py "$@"

if [[ -f "${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}" ]]; then
  echo
  echo "Episode artifact generated:"
  echo "  ${SCRIPT_DIR}/${OPIK_PYTEST_EPISODE_ARTIFACT_PATH}"
fi
