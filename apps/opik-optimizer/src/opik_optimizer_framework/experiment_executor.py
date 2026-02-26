from __future__ import annotations

import dataclasses
import logging
from typing import Any

import opik
from opik.evaluation import evaluate_optimization_suite_trial

from opik_optimizer_framework.tasks import create_task
from opik_optimizer_framework.types import Candidate, TrialResult

logger = logging.getLogger(__name__)


def run_experiment(
    client: object,
    candidate: Candidate,
    dataset_name: str,
    dataset_item_ids: list[str],
    optimization_id: str,
    metric_type: str,
    metric_parameters: dict[str, Any],
) -> TrialResult:
    """Execute an experiment for a candidate using the evaluation suite's evaluators.

    Returns a TrialResult with the experiment's aggregate score.
    """
    assert isinstance(client, opik.Opik)

    task = create_task(config=candidate.config)

    dataset = client.get_dataset(dataset_name)

    experiment_config = {
        "metric": metric_type,
        "dataset": dataset_name,
        "candidate_id": candidate.candidate_id,
        "step_index": candidate.step_index,
        "configuration": dataclasses.asdict(candidate.config),
    }

    result = evaluate_optimization_suite_trial(
        optimization_id=optimization_id,
        dataset=dataset,
        task=task,
        client=client,
        dataset_item_ids=dataset_item_ids,
        experiment_config=experiment_config,
        task_threads=4,
    )

    score = _extract_score(result)

    _log_experiment_score(client, result.experiment_id, "pass_rate", score)

    return TrialResult(
        candidate_id=candidate.candidate_id,
        step_index=candidate.step_index,
        score=score,
        metric_scores={metric_type: score},
        experiment_id=getattr(result, "experiment_id", None),
        experiment_name=getattr(result, "experiment_name", None),
        config_hash=candidate.config_hash,
        prompt_messages=candidate.config.prompt_messages,
        parent_candidate_ids=candidate.parent_candidate_ids,
    )


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


def _extract_score(result: Any) -> float:
    """Extract pass_rate from an evaluation suite result.

    Uses the SDK's build_suite_result to compute pass_rate with the
    canonical pass/fail algorithm (grouping by item, binary per run).
    """
    if not hasattr(result, "test_results") or not result.test_results:
        return 0.0

    from opik.api_objects.dataset.evaluation_suite.suite_result_constructor import (
        build_suite_result,
    )

    try:
        suite_result = build_suite_result(result)
        return suite_result.pass_rate
    except Exception:
        logger.warning("Failed to compute pass_rate via build_suite_result", exc_info=True)
        return 0.0
