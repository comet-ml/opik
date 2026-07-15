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

# Missing the colon after the class definition: a plain syntax error the
# build-time `compile()`/`ast.parse` check (OPIK-7172) must reject before any
# LLM call is made.
_SYNTAX_ERROR_CODE_METRIC = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class BrokenMetric(BaseMetric)
    def __init__(self, name: str = "broken"):
        super().__init__(name=name)

    def score(self, output: str, **kwargs) -> ScoreResult:
        return ScoreResult(name=self.name, value=0.0, reason="never runs")
'''

# A strict (non-**kwargs) `score()` signature whose `gold_label` parameter has
# no same-named column in the dataset (the item source exposes `label`), so it
# only resolves via the rename-capable `arguments` map
# ({"gold_label": "label"}). Exercises the arguments-map contract (OPIK-7172)
# through a real subprocess run, not just the metrics-factory unit tests.
_RENAMED_CODE_METRIC = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class LabelMatchRenamed(BaseMetric):
    def __init__(self, name: str = "label_match_renamed"):
        super().__init__(name=name)

    def score(self, output: str, gold_label: str) -> ScoreResult:
        label = str(gold_label or "").strip().lower()
        matched = bool(label) and label in (output or "").lower()
        return ScoreResult(
            name=self.name,
            value=1.0 if matched else 0.0,
            reason=f"gold_label {label!r} {'found' if matched else 'missing'}",
        )
'''

# A strict `score()` signature whose `reference` parameter is mapped (via
# `arguments`) to a dataset column that does not exist. The backend can't
# validate this at build time (no dataset access when a code metric is
# built): at scoring time the mapped column never resolves, so `reference`
# never lands in `score()`'s kwargs. Because this is a strict (no-`**kwargs`)
# signature, `isolated_metric` restricts `data` to `output` + the mapped params
# only (OPIK-7172), so `score(output=...)` raises a `TypeError` for the missing
# required `reference`. That failure is caught per item and reported as an
# explicit `ScoreResult(0.0, reason="Error: ...")` (see `run_user_code`) rather
# than a silent, unexplained 0.0 or a crashed run.
_MISSING_COLUMN_CODE_METRIC = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class RequiresMissingColumn(BaseMetric):
    def __init__(self, name: str = "requires_missing_column"):
        super().__init__(name=name)

    def score(self, output: str, reference: str) -> ScoreResult:
        # Never reached in this test: `score(**data)` always fails first (see
        # the contract note above).
        return ScoreResult(name=self.name, value=1.0)
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
    # An error result raises inside process_optimizer_job, so it never reaches
    # here; a cancellation returns a dict, so guard against that one explicitly.
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


def _wait_for_optimization_status(
    opik_client: opik.Opik, optimization_id: str, expected_status: str
) -> Any:
    """Poll the persisted optimization record until it reaches ``expected_status``.

    The record is a ClickHouse ReplacingMergeTree row (versioned re-insert), so
    a status update isn't guaranteed to be visible the instant
    ``update_optimizations_by_id`` returns; poll rather than reading once.
    Returns the fetched optimization on success.
    """
    fetched: dict[str, Any] = {}

    def _matches() -> bool:
        fetched["optimization"] = (
            opik_client.rest_client.optimizations.get_optimization_by_id(
                optimization_id
            )
        )
        return fetched["optimization"].status == expected_status

    assert synchronization.until(_matches, sleep=1.0, max_try_seconds=30), (
        f"optimization {optimization_id} never reached status "
        f"'{expected_status}' (last seen: "
        f"{getattr(fetched.get('optimization'), 'status', None)!r})"
    )
    return fetched["optimization"]


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


def test_studio_optimization_code_metric_syntax_error_surfaces_as_error(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: RunStudioOptimization,
) -> None:
    """A syntax error in the user's code is rejected at build time — before any
    LLM call — and the reason reaches the persisted optimization record, not
    just the subprocess log stream (OPIK-7172).
    """
    dataset_name = seeded_sentiment_classification_dataset.name
    metric = {"type": "code", "parameters": {"code": _SYNTAX_ERROR_CODE_METRIC}}
    studio_config = _studio_config(ANTHROPIC_CLAUDE_HAIKU, dataset_name, "gepa", metric)

    # `MetricFactory.build` raises `InvalidMetricError` inside
    # `optimization_lifecycle`, which marks the run as failed before
    # re-raising; `process_optimizer_job` then raises on the subprocess's
    # "error" result.
    with pytest.raises(Exception, match="invalid Python code"):
        run_studio_optimization(project_name, dataset_name, studio_config)

    optimization_id = run_studio_optimization.last_optimization_id
    assert optimization_id, "fixture did not record the created optimization id"

    optimization = _wait_for_optimization_status(opik_client, optimization_id, "error")
    assert optimization.status == "error"
    assert optimization.error_info, "error_info was not persisted on the failed run"
    # error_info is now the structured ErrorInfo object (exception_type/message/
    # traceback), matching the type spans/traces use (OPIK-7172). The build
    # failure reason is carried in the message.
    error_text = f"{optimization.error_info.message} {optimization.error_info.traceback}"
    assert "invalid Python code" in error_text, (
        f"error_info did not surface the build failure: {optimization.error_info!r}"
    )


def test_studio_optimization_with_code_metric_arguments_map_rename(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: RunStudioOptimization,
) -> None:
    """The rename-capable `arguments` map (`score()` param -> dataset column)
    resolves end-to-end through a real optimization subprocess: `gold_label`
    has no same-named dataset column, so the metric only builds/scores
    correctly because `{"gold_label": "label"}` is honored.

    Crucially this uses a STRICT signature `score(self, output, gold_label)`
    (no `**kwargs`) while the dataset carries an EXTRA unmapped column (`text`,
    consumed by the prompt). Under the pre-fix behavior `text` was splatted into
    `score(**data)` as an unexpected keyword -> TypeError -> swallowed to 0.0 for
    every item, which `_assert_optimization_healthy` accepts (0.0 >= 0.0). So we
    additionally assert a NON-TRIVIAL score: the build-time `accepts_var_keyword`
    detection (OPIK-7172) must restrict `data` to `output` + `gold_label` so the
    metric actually matches labels and scores above zero.
    """
    dataset_name = seeded_sentiment_classification_dataset.name
    metric = {
        "type": "code",
        "parameters": {
            "code": _RENAMED_CODE_METRIC,
            "arguments": {"gold_label": "label"},
        },
    }
    studio_config = _studio_config(ANTHROPIC_CLAUDE_HAIKU, dataset_name, "gepa", metric)

    result = run_studio_optimization(project_name, dataset_name, studio_config)

    _assert_optimization_healthy(result)
    # Non-trivial correctness: a masked 0.0 (extra `text` column colliding with
    # the strict signature) would satisfy _assert_optimization_healthy but leave
    # the baseline at exactly 0.0. A working rename on these clear-cut sentiment
    # examples must match at least one label -> baseline strictly above zero.
    assert result.get("initial_score", 0.0) > 0.0, (
        f"rename map produced a trivial 0.0 baseline — extra 'text' column likely "
        f"collided with the strict score() signature: {result.get('initial_score')!r}"
    )
    _assert_only_configured_model_ran(opik_client, project_name)


def test_studio_optimization_with_code_metric_missing_mapped_column(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: RunStudioOptimization,
) -> None:
    """An `arguments` map entry pointing at a column absent from the dataset
    can't be validated at build time (the code metric builder has no dataset
    access), so it degrades to a defined, explained per-item failure rather
    than silently reporting a healthy-looking run: every item's `score(**data)`
    call raises (the mapped `reference` never resolves), which is caught and
    reported as `ScoreResult(0.0, reason="Error: ...")` — never a crash, and
    never an unexplained/silent score (OPIK-7172; mirrors the OPIK-7160
    anti-pattern other reference-based metrics guard against at build time).
    """
    dataset_name = seeded_sentiment_classification_dataset.name
    metric = {
        "type": "code",
        "parameters": {
            "code": _MISSING_COLUMN_CODE_METRIC,
            "arguments": {"reference": "does_not_exist_in_dataset"},
        },
    }
    studio_config = _studio_config(ANTHROPIC_CLAUDE_HAIKU, dataset_name, "gepa", metric)

    result = run_studio_optimization(project_name, dataset_name, studio_config)

    # The run completes (the per-item scoring failure never crashes the whole
    # optimization) but the metric can never produce anything but 0: it's a
    # deterministic, explained degradation, not an accidental "healthy" score.
    assert result is not None, "no result returned"
    assert result.get("status") != "cancelled", "optimization was cancelled"
    assert result.get("initial_score") == 0.0, (
        f"expected a deterministic 0.0 baseline (every item's score() call is "
        f"missing 'reference'), got {result.get('initial_score')!r}"
    )
    assert result.get("score") == 0.0, (
        f"expected a deterministic 0.0 final score, got {result.get('score')!r}"
    )
