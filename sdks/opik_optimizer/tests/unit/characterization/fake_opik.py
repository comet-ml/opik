"""Fakes for the Opik client / evaluator boundary used by characterization tests.

``fake_evaluate`` stands in for ``opik.evaluation.evaluator.evaluate`` and
``evaluate_optimization_trial``: it runs the optimizer's real task callable on
each selected dataset item, sequentially (so results never depend on thread
scheduling), scores it with the optimizer's real metric wrapper, and returns a
genuine ``EvaluationResult``.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

from opik.evaluation import evaluation_result, test_case, test_result
from opik.evaluation.metrics import score_result

OPTIMIZATION_ID = "opt-golden"


def _select_items(
    dataset: Any, nb_samples: int | None, dataset_item_ids: list[str] | None
) -> list[dict[str, Any]]:
    items = list(dataset.get_items())
    if dataset_item_ids:
        wanted = [str(i) for i in dataset_item_ids]
        by_id = {str(item.get("id")): item for item in items}
        return [by_id[i] for i in wanted if i in by_id]
    if nb_samples is not None:
        return items[:nb_samples]
    return items


def fake_evaluate(**kwargs: Any) -> evaluation_result.EvaluationResult:
    dataset = kwargs["dataset"]
    task = kwargs["task"]
    metrics = kwargs.get("scoring_metrics") or []
    items = _select_items(
        dataset, kwargs.get("nb_samples"), kwargs.get("dataset_item_ids")
    )

    results: list[test_result.TestResult] = []
    for item in items:
        item_content = dict(item)
        output = task(dict(item_content))
        inputs = {**item_content, **(output or {})}
        scores: list[score_result.ScoreResult] = []
        for metric in metrics:
            try:
                value = metric.score(**inputs)
            except Exception as exc:  # mirror Opik: failures become scoring_failed
                value = score_result.ScoreResult(
                    name=getattr(metric, "name", "metric"),
                    value=0.0,
                    scoring_failed=True,
                    reason=str(exc),
                )
            if isinstance(value, list):
                scores.extend(value)
            else:
                scores.append(value)
        results.append(
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id=f"trace-{item_content.get('id')}",
                    dataset_item_id=str(item_content.get("id")),
                    task_output=output or {},
                    dataset_item_content=item_content,
                ),
                score_results=scores,
                trial_id=0,
            )
        )

    return evaluation_result.EvaluationResult(
        experiment_id="exp-golden",
        dataset_id=str(getattr(dataset, "id", "dataset-golden")),
        experiment_name="golden",
        test_results=results,
        experiment_url=None,
        trial_count=1,
    )


def make_opik_client() -> MagicMock:
    client = MagicMock(name="FakeOpikClient")
    optimization = MagicMock(name="FakeOptimization")
    optimization.id = OPTIMIZATION_ID
    client.create_optimization.return_value = optimization
    client.get_optimization_by_id.return_value = optimization
    return client
