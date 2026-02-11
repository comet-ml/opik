# Pytest Integration E2E Examples

This folder provides a simple end-to-end smoke test for the Opik pytest integration:

- `test_llm_unit_e2e.py`: basic `@llm_unit` tracking with parametrized tests
- `test_llm_episode_e2e.py`: scenario-based `@llm_episode` tracking with simulation output
- `run_examples.sh`: one-command runner

## Prerequisites

- Install SDK deps in your environment
- Configure Opik access:
  - Cloud: set `OPIK_API_KEY` (and optional `OPIK_WORKSPACE`)
  - Local Opik: set `OPIK_URL_OVERRIDE` (API key optional depending on setup)

## Run

```bash
cd sdks/python/examples/testing/pytest_integration_e2e
./run_examples.sh
```

This runs:

```bash
pytest -q -s test_llm_unit_e2e.py test_llm_episode_e2e.py
```

## Outputs

- Standard pytest output
- Opik pytest experiment logs
- Episode artifact JSON (default path):
  - `.opik/pytest_episode_report.json`

You can override defaults with environment variables, for example:

```bash
OPIK_PROJECT_NAME=my-e2e \
OPIK_PYTEST_EXPERIMENT_NAME_PREFIX=MySuite \
OPIK_PYTEST_EPISODE_ARTIFACT_PATH=.opik/my_episode_report.json \
./run_examples.sh
```
