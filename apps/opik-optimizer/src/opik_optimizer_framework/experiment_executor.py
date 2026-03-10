from __future__ import annotations

import logging
from typing import Any

import opik
from opik.evaluation import evaluate_optimization_suite_trial

from opik_optimizer_framework.tasks import MESSAGE_KEYS, create_task
from opik_optimizer_framework.types import Candidate, ScoringConfig, TrialResult

logger = logging.getLogger(__name__)


def run_experiment(
    client: object,
    candidate: Candidate,
    dataset_name: str,
    dataset_item_ids: list[str],
    optimization_id: str,
    metric_type: str,
    batch_index: int | None = None,
    num_items: int | None = None,
    capture_traces: bool | None = None,
    experiment_type: str | None = None,
    optimizer_type: str | None = None,
    optimizable_keys: list[str] | None = None,
    task_threads: int = 4,
    evaluator_model: str | None = None,
    scoring_config: ScoringConfig | None = None,
) -> TrialResult:
    """Execute an experiment, returning only the TrialResult."""
    trial, _ = run_experiment_with_details(
        client=client,
        candidate=candidate,
        dataset_name=dataset_name,
        dataset_item_ids=dataset_item_ids,
        optimization_id=optimization_id,
        metric_type=metric_type,
        batch_index=batch_index,
        num_items=num_items,
        capture_traces=capture_traces,
        experiment_type=experiment_type,
        optimizer_type=optimizer_type,
        optimizable_keys=optimizable_keys,
        task_threads=task_threads,
        evaluator_model=evaluator_model,
        scoring_config=scoring_config,
    )
    return trial


def run_experiment_with_details(
    client: object,
    candidate: Candidate,
    dataset_name: str,
    dataset_item_ids: list[str],
    optimization_id: str,
    metric_type: str,
    batch_index: int | None = None,
    num_items: int | None = None,
    capture_traces: bool | None = None,
    experiment_type: str | None = None,
    optimizer_type: str | None = None,
    optimizable_keys: list[str] | None = None,
    task_threads: int = 4,
    evaluator_model: str | None = None,
    scoring_config: ScoringConfig | None = None,
) -> tuple[TrialResult, Any]:
    """Execute an experiment and return both the TrialResult and the raw EvaluationResult.

    The raw result contains per-item test_results with score_results that
    include ``reason`` fields from LLM judge assertions.
    """
    assert isinstance(client, opik.Opik)

    task = create_task(config=candidate.config)

    dataset = client.get_dataset(dataset_name)

    config_for_metadata = dict(candidate.config)

    if "prompt" not in config_for_metadata:
        prompt_msgs = [
            {"role": role, "content": str(config_for_metadata[key])}
            for key, role in MESSAGE_KEYS
            if config_for_metadata.get(key) is not None
        ]
        if prompt_msgs:
            config_for_metadata["prompt"] = prompt_msgs

    experiment_config = {
        "metric": metric_type,
        "dataset": dataset_name,
        "candidate_id": candidate.candidate_id,
        "step_index": candidate.step_index,
        "parent_candidate_ids": candidate.parent_candidate_ids,
        "configuration": config_for_metadata,
    }
    if optimizer_type is not None:
        experiment_config["optimizer"] = optimizer_type
    if batch_index is not None:
        experiment_config["batch_index"] = batch_index
    if num_items is not None:
        experiment_config["num_items"] = num_items
    if capture_traces is not None:
        experiment_config["capture_traces"] = capture_traces

    for key in (optimizable_keys or []):
        value = candidate.config.get(key)
        if value is not None:
            experiment_config[key] = value

    result = evaluate_optimization_suite_trial(
        optimization_id=optimization_id,
        dataset=dataset,
        task=task,
        client=client,
        dataset_item_ids=dataset_item_ids,
        experiment_config=experiment_config,
        task_threads=task_threads,
        experiment_type=experiment_type,
        evaluator_model=evaluator_model,
    )

    optimization_score, pass_rate = _extract_score(result, scoring_config or ScoringConfig())

    _log_experiment_score(client, result.experiment_id, metric_type, pass_rate)

    trial = TrialResult(
        candidate_id=candidate.candidate_id,
        step_index=candidate.step_index,
        score=pass_rate,
        metric_scores={metric_type: pass_rate},
        experiment_id=getattr(result, "experiment_id", None),
        experiment_name=getattr(result, "experiment_name", None),
        config=candidate.config,
        parent_candidate_ids=candidate.parent_candidate_ids,
        internal_optimization_score=optimization_score,
    )
    return trial, result


def _log_experiment_score(
    client: opik.Opik,
    experiment_id: str | None,
    score_name: str,
    score_value: float,
) -> None:
    """Write an experiment-level score so the UI progress chart can display it."""
    if experiment_id is None:
        return
    try:
        from opik.rest_api.types.experiment_score import ExperimentScore

        client.rest_client.experiments.update_experiment(
            id=experiment_id,
            experiment_scores=[
                ExperimentScore(name=score_name, value=score_value),
            ],
        )
    except Exception:
        logger.warning("Failed to log experiment score", exc_info=True)


def _extract_score(result: Any, scoring_config: ScoringConfig) -> tuple[float, float]:
    """Extract optimization score and display pass_rate from a suite result.

    Returns ``(optimization_score, pass_rate)`` where:
    - ``optimization_score`` is the value used by the algorithm (blended or raw pass_rate)
    - ``pass_rate`` is the raw item-level pass_rate for UI display
    """
    if not hasattr(result, "test_results") or not result.test_results:
        return 0.0, 0.0

    from opik.api_objects.dataset.evaluation_suite.suite_result_constructor import (
        build_suite_result,
    )

    try:
        suite_result = build_suite_result(result)
    except Exception:
        logger.warning("Failed to compute suite result", exc_info=True)
        return 0.0, 0.0

    pass_rate = suite_result.pass_rate

    if scoring_config.strategy == "pass_rate":
        return pass_rate, pass_rate

    assertions_passed = 0
    assertions_total = 0
    for tr in result.test_results:
        for sr in tr.score_results:
            assertions_total += 1
            if (isinstance(sr.value, bool) and sr.value) or sr.value == 1:
                assertions_passed += 1

    assertion_rate = assertions_passed / assertions_total if assertions_total > 0 else 1.0

    assertion_weight = scoring_config.assertion_rate_weight
    if assertion_weight is None:
        num_items = len(suite_result.item_results)
        assertion_weight = 1.0 / (num_items + 1) if num_items > 0 else 0.0

    blended = scoring_config.pass_rate_weight * pass_rate + assertion_weight * assertion_rate
    return blended, pass_rate
