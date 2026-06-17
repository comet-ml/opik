"""End-to-end regression test for Optimization Studio.

Drives the **real entrypoint** — ``process_optimizer_job`` (the function the RQ
worker calls), which sets up the gateway env and runs ``optimizer_runner.py`` as
an isolated subprocess — so the production wiring (gateway routing, the
``openai/`` model prefix, the ``ChatPrompt(model=...)`` construction, role
derivation) is actually exercised. It skips only the Java REST enqueue and the
RQ queue itself by calling the job handler directly.

The Anthropic key lives in the backend workspace (stored by the
``anthropic_workspace_key`` fixture from a CI secret); the optimizer reaches it
only through the gateway. Given a dataset and the prompt from the job context,
the test asserts the run is healthy (baseline + optimized prompt + no
regression) and uses the produced traces to confirm the configured model(s)
actually ran (not the gpt-5-nano default).

Bound the run via ``OPTIMIZER_MAX_TRIALS`` (set in CI) so it stays short.
"""

from typing import Any

import pytest

import opik
from opik import synchronization

from studio_helpers import assert_optimization_healthy, run_studio_job

pytestmark = pytest.mark.e2e

# Bare model id (the runner adds the "openai/" gateway prefix); resolves to the
# workspace Anthropic key server-side.
_TASK_MODEL = "claude-haiku-4-5-20251001"


def _studio_config(model: str, optimizer_type: str, dataset_name: str) -> dict[str, Any]:
    """A job-context config with a single USER message (the regression case)."""
    return {
        "dataset_name": dataset_name,
        "prompt": {
            "messages": [
                {
                    "role": "user",
                    "content": 'Classify the sentiment of this movie review as '
                    'exactly "positive" or "negative": {{text}}',
                }
            ]
        },
        "llm_model": {"model": model, "parameters": {}},
        "evaluation": {
            "metrics": [
                {
                    "type": "equals",
                    "parameters": {"reference_key": "label", "case_sensitive": False},
                }
            ]
        },
        "optimizer": {"type": optimizer_type, "parameters": {"seed": 42}},
    }


def _models_in_project(opik_client: opik.Opik, project_name: str) -> list[str]:
    return [
        (span.model or "")
        for span in opik_client.search_spans(project_name=project_name, max_results=1000)
    ]


def _wait_for_model(opik_client: opik.Opik, project_name: str, substring: str) -> None:
    assert synchronization.until(
        lambda: any(
            substring in model.lower()
            for model in _models_in_project(opik_client, project_name)
        ),
        sleep=1.0,
        max_try_seconds=30,
    ), (
        f"No span used a model matching '{substring}'; "
        f"saw {set(_models_in_project(opik_client, project_name))}"
    )


@pytest.mark.parametrize("optimizer_type", ["gepa", "hierarchical_reflective"])
def test_studio_optimization_runs_on_dataset_and_prompt(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_dataset: opik.Dataset,
    optimizer_type: str,
) -> None:
    studio_config = _studio_config(_TASK_MODEL, optimizer_type, seeded_dataset.name)

    result = run_studio_job(opik_client, project_name, seeded_dataset.name, studio_config)

    assert_optimization_healthy(result)

    # The configured model actually ran (the model-passing regression fell back
    # to openai/gpt-5-nano). Spans land in ClickHouse with eventual consistency.
    _wait_for_model(opik_client, project_name, "claude-haiku")
    models = _models_in_project(opik_client, project_name)
    # Healthy volume: it evaluated the dataset, not just a single call.
    assert sum("claude-haiku" in m.lower() for m in models) >= 2, (
        f"expected multiple model calls, saw {models}"
    )
    assert not any("gpt-5-nano" in m for m in models), (
        f"SDK default model leaked into traces: {models}"
    )
