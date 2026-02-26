from __future__ import annotations

import logging
from typing import Any, Callable

import litellm
import opik
from opik.evaluation import evaluate_optimization_suite_trial

from opik_optimizer_framework.types import Candidate, TrialResult

logger = logging.getLogger(__name__)


def _build_litellm_task(
    prompt_messages: list[dict[str, str]],
    model: str,
    model_parameters: dict[str, Any],
) -> Callable:
    """Build a temporary litellm.completion() wrapper as the task function.

    Returns {"input": <dataset_item>, "output": <llm_response>} so that
    suite evaluators (LLMJudge) receive both input context and output.
    """

    def task(dataset_item: dict[str, Any]) -> dict[str, Any]:
        formatted_messages = []
        for msg in prompt_messages:
            content = msg["content"]
            for key, value in dataset_item.items():
                content = content.replace(f"{{{key}}}", str(value))
            formatted_messages.append({"role": msg["role"], "content": content})

        response = litellm.completion(
            model=model,
            messages=formatted_messages,
            **model_parameters,
        )
        return {
            "input": dataset_item,
            "output": response.choices[0].message.content,
        }

    return task


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

    task_fn = _build_litellm_task(
        candidate.config.prompt_messages,
        candidate.config.model,
        candidate.config.model_parameters,
    )

    dataset = client.get_dataset(dataset_name)

    experiment_config = {
        "configuration": {
            "prompt": {
                "messages": candidate.config.prompt_messages,
            },
        },
    }

    result = evaluate_optimization_suite_trial(
        optimization_id=optimization_id,
        dataset=dataset,
        task=task_fn,
        client=client,
        dataset_item_ids=dataset_item_ids,
        experiment_config=experiment_config,
        task_threads=4,
    )

    score = _extract_score(result)

    # Log experiment-level score matching the objective name so the
    # optimization progress chart can find it.
    _log_experiment_score(client, result.experiment_id, metric_type, score)

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
    """Extract the aggregate score from an evaluation result.

    For suite evaluations, averages all score_results across all test_results.
    Each test_result contains score_results from LLMJudge assertions.
    """
    if hasattr(result, "test_results") and result.test_results:
        all_scores = []
        for tr in result.test_results:
            for sr in tr.score_results:
                if hasattr(sr, "value") and not getattr(sr, "scoring_failed", False):
                    all_scores.append(sr.value)
        if all_scores:
            return sum(all_scores) / len(all_scores)
    return 0.0
