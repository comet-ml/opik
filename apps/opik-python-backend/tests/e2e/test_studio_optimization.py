"""End-to-end regression tests for Optimization Studio.

Each test drives the **real entrypoint** — ``process_optimizer_job`` (the
function the RQ worker calls), via the ``run_studio_optimization`` fixture —
which sets up the gateway env and runs ``optimizer_runner.py`` as an isolated
subprocess. So the production wiring (gateway routing, the ``openai/`` model
prefix, the ``ChatPrompt(model=...)`` construction, role derivation) is actually
exercised. Only the Java REST enqueue and the RQ queue itself are skipped.

The Anthropic key lives in the backend workspace (stored by the
``anthropic_workspace_key`` fixture from a CI secret); the optimizer reaches it
only through the gateway, never directly.

Coverage:
- the supported optimizers (GEPA, hierarchical reflective) with an ``equals``
  metric, asserting a healthy run and confirming via traces that the configured
  model actually ran (not the SDK default);
- the ``code`` metric variant, which runs user-supplied Python through the
  executor inside the optimization subprocess.

Bound the run via ``OPTIMIZER_MAX_TRIALS`` (set in CI) so it stays short.
"""

import re
from typing import Any, Callable

import pytest

import opik
from opik import synchronization

from llm_constants import (
    ANTHROPIC_CLAUDE_HAIKU,
    ANTHROPIC_CLAUDE_HAIKU_SHORT,
    OPENAI_GPT_NANO,
)

pytestmark = pytest.mark.e2e

RunStudioOptimization = Callable[[str, str, dict[str, Any]], dict[str, Any]]

# The dataset variable the prompt substitutes; the optimized prompt must keep it
# (the FE-style `{{text}}` is converted to optimizer-style `{text}` before the run).
_PROMPT_VARIABLE = "text"
_PROMPT_MESSAGE = {
    "role": "user",
    "content": 'Classify the sentiment of this movie review as exactly '
    '"positive" or "negative": {{' + _PROMPT_VARIABLE + '}}',
}

# A user-authored BaseMetric for the code-metric variant: scores 1.0 when the
# gold label appears in the model's output. `kwargs` carries the dataset item
# fields (here, `label`).
_CODE_METRIC = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class LabelMatch(BaseMetric):
    def __init__(self, name: str = "label_match"):
        super().__init__(name=name)

    def score(self, output: str, **kwargs) -> ScoreResult:
        label = str(kwargs.get("label", "")).strip().lower()
        matched = bool(label) and label in (output or "").lower()
        return ScoreResult(
            name=self.name,
            value=1.0 if matched else 0.0,
            reason=f"label {label!r} {'found' if matched else 'missing'}",
        )
'''


def _studio_config(
    model: str, dataset_name: str, optimizer_type: str, metric: dict[str, Any]
) -> dict[str, Any]:
    """A job-context config with a single USER message (the regression case)."""
    return {
        "dataset_name": dataset_name,
        "prompt": {"messages": [_PROMPT_MESSAGE]},
        "llm_model": {"model": model, "parameters": {}},
        "evaluation": {"metrics": [metric]},
        "optimizer": {"type": optimizer_type, "parameters": {"seed": 42}},
    }


def _assert_optimization_healthy(result: dict[str, Any]) -> None:
    """Signals that the optimization actually ran end-to-end."""
    assert result is not None, "no result returned"
    assert "error" not in result, f"optimization errored: {result.get('error')}"
    assert result.get("status") != "cancelled", "optimization was cancelled"
    # Baseline established + a score produced, both in range.
    assert result.get("initial_score") is not None, "no baseline score (it didn't establish a baseline)"
    assert 0.0 <= result["initial_score"] <= 1.0, f"baseline {result['initial_score']} out of range"
    assert result.get("score") is not None, "no final score"
    assert 0.0 <= result["score"] <= 1.0, f"score {result['score']} out of range"
    # Optimization shouldn't make the prompt worse than the baseline.
    assert result["score"] >= result["initial_score"], (
        f"optimized score {result['score']} regressed below baseline {result['initial_score']}"
    )
    # A well-formed optimized prompt was produced: a non-empty list of
    # role/content messages that still carries the dataset variable. A mangled
    # or variable-less prompt would be unusable even with a healthy score.
    optimized_prompt = result.get("optimized_prompt")
    assert isinstance(optimized_prompt, list) and optimized_prompt, (
        f"optimized prompt is not a non-empty message list: {optimized_prompt!r}"
    )
    assert all(
        isinstance(message, dict)
        and isinstance(message.get("role"), str)
        and isinstance(message.get("content"), str)
        for message in optimized_prompt
    ), f"optimized prompt has malformed messages: {optimized_prompt!r}"
    assert any(
        re.search(r"\{+\s*" + _PROMPT_VARIABLE + r"\s*\}+", message["content"])
        for message in optimized_prompt
    ), f"optimized prompt dropped the {{{_PROMPT_VARIABLE}}} variable: {optimized_prompt!r}"


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


def _assert_only_configured_model_ran(opik_client: opik.Opik, project_name: str) -> None:
    """The configured model actually ran, and the SDK default never leaked (the
    model-passing regression fell back to it). Spans land in ClickHouse with
    eventual consistency, so wait for the expected model to appear."""
    _wait_for_model(opik_client, project_name, ANTHROPIC_CLAUDE_HAIKU_SHORT)
    models = _models_in_project(opik_client, project_name)
    # Healthy volume: it evaluated the dataset, not just a single call.
    assert sum(ANTHROPIC_CLAUDE_HAIKU_SHORT in m.lower() for m in models) >= 2, (
        f"expected multiple model calls, saw {models}"
    )
    assert not any(OPENAI_GPT_NANO in m for m in models), (
        f"SDK default model leaked into traces: {models}"
    )


@pytest.mark.parametrize("optimizer_type", ["gepa", "hierarchical_reflective"])
def test_studio_optimization_runs_on_dataset_and_prompt(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: RunStudioOptimization,
    optimizer_type: str,
) -> None:
    dataset_name = seeded_sentiment_classification_dataset.name
    metric = {
        "type": "equals",
        "parameters": {"reference_key": "label", "case_sensitive": False},
    }
    studio_config = _studio_config(ANTHROPIC_CLAUDE_HAIKU, dataset_name, optimizer_type, metric)

    result = run_studio_optimization(project_name, dataset_name, studio_config)

    _assert_optimization_healthy(result)
    _assert_only_configured_model_ran(opik_client, project_name)


def test_studio_optimization_with_code_metric(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: RunStudioOptimization,
) -> None:
    dataset_name = seeded_sentiment_classification_dataset.name
    metric = {"type": "code", "parameters": {"code": _CODE_METRIC}}
    studio_config = _studio_config(ANTHROPIC_CLAUDE_HAIKU, dataset_name, "gepa", metric)

    result = run_studio_optimization(project_name, dataset_name, studio_config)

    # A healthy run only happens if the user's BaseMetric executed via the
    # executor and produced scores end-to-end.
    _assert_optimization_healthy(result)
    _assert_only_configured_model_ran(opik_client, project_name)
