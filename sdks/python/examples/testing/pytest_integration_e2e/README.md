# Pytest Integration E2E Examples

This folder provides a simple end-to-end smoke test for the Opik pytest integration:

- `test_llm_episode_e2e.py`: scenario-based `@llm_episode` tracking with simulation output, budgets, scores, and detailed timestamped logs
- `test_llm_episode_policy_routing_e2e.py`: policy/routing episode scenarios with tool-action expectations
- `test_llm_episode_judges_e2e.py`: episode scoring with LLM-as-a-judge (`gpt-5-nano`) using one built-in metric (`AnswerRelevance`) and one custom `GEval` rubric
- `test_llm_episode_observability_budgets_e2e.py`: thread-level telemetry rollups (`search_spans` by `thread_id`) feeding token/latency/cost episode budgets
- `run_examples.sh`: one-command runner for all examples in this folder

`@llm_unit` deterministic examples were moved to fast unit tests:
- `sdks/python/tests/unit/plugins/pytest/test_llm_unit_contract_examples.py`
- `sdks/python/tests/unit/plugins/pytest/test_llm_unit_intent_examples.py`

## Prerequisites

- Install SDK deps in your environment
- Configure Opik access:
  - Cloud: set `OPIK_API_KEY` (and optional `OPIK_WORKSPACE`)
  - Local Opik: set `OPIK_URL_OVERRIDE` (API key optional depending on setup)
- For `test_llm_episode_judges_e2e.py`, set `OPENAI_API_KEY` to run `gpt-5-nano` judge calls
- `test_llm_episode_observability_budgets_e2e.py` is deterministic and does not require external model keys
  - Set `OPIK_EXAMPLE_REQUIRE_TELEMETRY=true` to make telemetry discovery (trace/span search) a hard CI failure instead of a warning

## Run

```bash
cd sdks/python/examples/testing/pytest_integration_e2e
./run_examples.sh
```

Default run executes:

```bash
pytest -vv -s -rA --show-capture=no \
  test_*.py
```

Example diagnostic logging defaults to `DEBUG`.
Set `OPIK_EXAMPLE_LOG_LEVEL=INFO` to keep summary-only logs.

## Outputs

- Standard pytest output
- Opik pytest experiment logs
- Optional episode artifact JSON (when enabled via config/env):
  - `.opik/pytest_episode_report.json`

You can override defaults with environment variables, for example:

```bash
OPIK_PROJECT_NAME=my-e2e \
OPIK_PYTEST_EXPERIMENT_NAME_PREFIX=MySuite \
OPIK_PYTEST_EPISODE_ARTIFACT_PATH=.opik/my_episode_report.json \
./run_examples.sh
```
