from __future__ import annotations

import inspect

import pytest
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer.core import evaluation
from opik_optimizer.metrics import MultiMetricObjective, SpanDuration


def test_create_metric_class_exposes_task_span_for_span_metrics() -> None:
    objective = MultiMetricObjective(
        metrics=[SpanDuration(target=6.0, name="duration")],
        weights=[1.0],
        name="objective",
    )

    metric_wrapper = evaluation._create_metric_class(objective)

    assert "task_span" in inspect.signature(metric_wrapper.score).parameters


def test_create_metric_class_keeps_regular_signature_for_non_span_metrics() -> None:
    def accuracy_metric(dataset_item: dict[str, object], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 1.0

    metric_wrapper = evaluation._create_metric_class(accuracy_metric)

    assert "task_span" not in inspect.signature(metric_wrapper.score).parameters


def test_validate_objective_scores_raises_on_failed_scores() -> None:
    with pytest.raises(ValueError, match="failed on 1/2"):
        evaluation._validate_objective_scores(
            [
                ScoreResult(name="objective", value=1.0, scoring_failed=False),
                ScoreResult(
                    name="objective",
                    value=0.0,
                    scoring_failed=True,
                    reason="missing task_span",
                ),
            ],
            objective_metric_name="objective",
        )


def test_validate_objective_scores_raises_when_no_scores_present() -> None:
    with pytest.raises(ValueError, match="produced no scores"):
        evaluation._validate_objective_scores([], objective_metric_name="objective")

