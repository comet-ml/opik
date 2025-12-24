import logging
import math
from typing import Any, overload, Literal
from collections.abc import Callable

import opik
from .api_objects.types import MetricFunction
from .reporting_utils import suppress_experiment_reporting
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation.metrics import base_metric, score_result
from . import multi_metric_objective

logger = logging.getLogger(__name__)


def _create_metric_class(metric: MetricFunction) -> base_metric.BaseMetric:
    class MetricClass(base_metric.BaseMetric):
        def __init__(self) -> None:
            self.name = metric.__name__

        def score(
            self, llm_output: str, **kwargs: Any
        ) -> score_result.ScoreResult | list[score_result.ScoreResult]:
            try:
                metric_val = metric(dataset_item=kwargs, llm_output=llm_output)

                # Handle list[ScoreResult] return type first
                if isinstance(metric_val, list):
                    return metric_val

                # Handle MultiMetricObjective - always returns list (preserves original)
                if isinstance(metric, multi_metric_objective.MultiMetricObjective):
                    # MultiMetricObjective.__call__ always returns ScoreResult
                    if isinstance(metric_val, score_result.ScoreResult):
                        if (
                            metric_val.metadata is not None
                            and isinstance(metric_val.metadata, dict)
                            and "raw_score_results" in metric_val.metadata
                        ):
                            raw_results = metric_val.metadata["raw_score_results"]
                            if isinstance(raw_results, list):
                                return [metric_val, *raw_results]
                        # No raw_score_results - still return as list
                        return [metric_val]
                    # Type-safe fallback (shouldn't happen at runtime)
                    return [
                        score_result.ScoreResult(
                            name=self.name,
                            value=float(metric_val),
                            scoring_failed=False,
                        )
                    ]

                # Handle ScoreResult return type (non-MultiMetricObjective)
                if isinstance(metric_val, score_result.ScoreResult):
                    return score_result.ScoreResult(
                        name=self.name,
                        value=metric_val.value,
                        scoring_failed=metric_val.scoring_failed,
                        metadata=metric_val.metadata,
                        reason=metric_val.reason,
                    )

                # Handle float/int return type
                return score_result.ScoreResult(
                    name=self.name, value=float(metric_val), scoring_failed=False
                )
            except Exception:
                return score_result.ScoreResult(
                    name=self.name, value=0, scoring_failed=True
                )

    return MetricClass()


@overload
def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: MetricFunction,
    num_threads: int,
    optimization_id: str | None = None,
    dataset_item_ids: list[str] | None = None,
    project_name: str | None = None,
    n_samples: int | None = None,
    experiment_config: dict[str, Any] | None = None,
    verbose: int = 1,
    return_evaluation_result: Literal[False] = False,
) -> float: ...


@overload
def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: MetricFunction,
    num_threads: int,
    optimization_id: str | None = None,
    dataset_item_ids: list[str] | None = None,
    project_name: str | None = None,
    n_samples: int | None = None,
    experiment_config: dict[str, Any] | None = None,
    verbose: int = 1,
    return_evaluation_result: Literal[True] = True,
) -> opik_evaluation_result.EvaluationResult: ...


def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: MetricFunction,
    num_threads: int,
    optimization_id: str | None = None,
    dataset_item_ids: list[str] | None = None,
    project_name: str | None = None,
    n_samples: int | None = None,
    experiment_config: dict[str, Any] | None = None,
    verbose: int = 1,
    return_evaluation_result: bool = False,
) -> float | opik_evaluation_result.EvaluationResult:
    """
    Evaluate a task on a dataset.

    Args:
        dataset: A list of dictionaries representing the dataset.
        metric: A metric function, this function should have two arguments:
            dataset_item and llm_output
        evaluated_task: A function that takes a dataset item dict as input and returns a dictionary with output(s).
        dataset_item_ids: Optional list of dataset item IDs to evaluate.
        project_name: Optional project name for evaluation.
        n_samples: Optional number of test examples to perform the evaluation and then stop.
        num_threads: Number of threads to use for evaluation.
        experiment_config: The dictionary with parameters that describe experiment
        optimization_id: Optional optimization ID for the experiment.
        verbose: Whether to print debug information.
        return_evaluation_result: If True, return the full EvaluationResult instead of just the score.

    Returns:
        float or EvaluationResult: The average score of the evaluated task, or the full result if return_evaluation_result=True.

    Note:
        If you need access to the raw evaluation result, use `evaluate_with_result` or set `return_evaluation_result=True`.
    """
    score, result = _evaluate_internal(
        dataset=dataset,
        evaluated_task=evaluated_task,
        metric=metric,
        num_threads=num_threads,
        optimization_id=optimization_id,
        dataset_item_ids=dataset_item_ids,
        project_name=project_name,
        n_samples=n_samples,
        experiment_config=experiment_config,
        verbose=verbose,
    )

    if return_evaluation_result:
        if result is None:
            raise ValueError("EvaluationResult is None, cannot return it")
        return result
    return score


def evaluate_with_result(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: MetricFunction,
    num_threads: int,
    optimization_id: str | None = None,
    dataset_item_ids: list[str] | None = None,
    project_name: str | None = None,
    n_samples: int | None = None,
    experiment_config: dict[str, Any] | None = None,
    verbose: int = 1,
) -> tuple[float, opik_evaluation_result.EvaluationResult | None]:
    """
    Run evaluation and return both the aggregate score and the underlying Opik result.
    """
    return _evaluate_internal(
        dataset=dataset,
        evaluated_task=evaluated_task,
        metric=metric,
        num_threads=num_threads,
        optimization_id=optimization_id,
        dataset_item_ids=dataset_item_ids,
        project_name=project_name,
        n_samples=n_samples,
        experiment_config=experiment_config,
        verbose=verbose,
    )


@suppress_experiment_reporting
def _evaluate_internal(
    *,
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: MetricFunction,
    num_threads: int,
    optimization_id: str | None,
    dataset_item_ids: list[str] | None,
    project_name: str | None,
    n_samples: int | None,
    experiment_config: dict[str, Any] | None,
    verbose: int,
) -> tuple[float, opik_evaluation_result.EvaluationResult | None]:
    items = dataset.get_items(n_samples)
    if not items:
        print("[DEBUG] Empty dataset, returning 0.0")
        return 0.0, None

    if dataset_item_ids:
        # FIXME: In rare cases sometimes dataset ids are missing (cause unknown, skip those for now)
        available_ids = {item.get("id") for item in items}
        missing_ids = [
            item_id for item_id in dataset_item_ids if item_id not in available_ids
        ]
        if missing_ids:
            logger.warning(
                "Dropping %s dataset_item_ids not present in dataset %s (showing first 5): %s",
                len(missing_ids),
                getattr(dataset, "name", None) or "<unknown>",
                missing_ids[:5],
            )
        dataset_item_ids = [
            item_id for item_id in dataset_item_ids if item_id in available_ids
        ]
        if not dataset_item_ids:
            logger.warning(
                "All provided dataset_item_ids were missing; evaluating on full dataset instead."
            )
            dataset_item_ids = None
        else:
            items = [item for item in items if item.get("id") in dataset_item_ids]

    eval_metrics = [_create_metric_class(metric)]

    if optimization_id is not None:
        evaluation_result = opik_evaluator.evaluate_optimization_trial(
            optimization_id=optimization_id,
            dataset=dataset,
            task=evaluated_task,
            project_name=project_name,
            dataset_item_ids=dataset_item_ids,
            scoring_metrics=eval_metrics,
            task_threads=num_threads,
            nb_samples=n_samples,
            experiment_config=experiment_config,
            verbose=verbose,
        )
    else:
        evaluation_result = opik_evaluator.evaluate(
            dataset=dataset,
            task=evaluated_task,
            project_name=project_name,
            dataset_item_ids=dataset_item_ids,
            scoring_metrics=eval_metrics,
            task_threads=num_threads,
            nb_samples=n_samples,
            experiment_config=experiment_config,
            verbose=verbose,
        )

    if not evaluation_result.test_results:
        return 0.0, evaluation_result

    # Filter score results to only include the objective metric
    objective_metric_name = metric.__name__
    objective_score_results: list[score_result.ScoreResult] = []
    for test_result in evaluation_result.test_results:
        for score_result_ in test_result.score_results:
            if score_result_.name == objective_metric_name:
                objective_score_results.append(score_result_)
                break

    if not objective_score_results:
        return 0.0, evaluation_result

    # FIXME: Possible misconfiguration when we are comparing 0 to 0 and get inf+
    # We should avoid these from running in the first place by checking results
    # further up, but this is a simple fix to avoid ending up in a dead loop.
    finite_values = [
        score_result_.value
        for score_result_ in objective_score_results
        if score_result_.value is not None and math.isfinite(score_result_.value)
    ]
    if not finite_values:
        raise ValueError(
            f"All metric scores were non-finite for metric '{objective_metric_name}'."
        )

    avg_score = sum(finite_values) / len(finite_values)

    return avg_score, evaluation_result
