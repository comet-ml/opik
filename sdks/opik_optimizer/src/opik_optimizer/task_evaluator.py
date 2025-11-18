import logging
from typing import Any
from collections.abc import Callable

import opik
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation.metrics import base_metric, score_result
from . import multi_metric_objective

logger = logging.getLogger(__name__)


def _create_metric_class(metric: Callable) -> base_metric.BaseMetric:
    class MetricClass(base_metric.BaseMetric):
        def __init__(self) -> None:
            self.name = metric.__name__

        def score(
            self, llm_output: str, **kwargs: Any
        ) -> score_result.ScoreResult | list[score_result.ScoreResult]:
            try:
                metric_val = metric(dataset_item=kwargs, llm_output=llm_output)

                if isinstance(metric, multi_metric_objective.MultiMetricObjective):
                    if (
                        hasattr(metric_val, "metadata")
                        and "raw_score_results" in metric_val.metadata
                    ):
                        return [metric_val, *metric_val.metadata["raw_score_results"]]
                    else:
                        return [metric_val]
                if isinstance(metric_val, score_result.ScoreResult):
                    return score_result.ScoreResult(
                        name=self.name,
                        value=metric_val.value,
                        scoring_failed=metric_val.scoring_failed,
                        metadata=metric_val.metadata,
                        reason=metric_val.reason,
                    )
                else:
                    return score_result.ScoreResult(
                        name=self.name, value=metric_val, scoring_failed=False
                    )
            except Exception:
                return score_result.ScoreResult(
                    name=self.name, value=0, scoring_failed=True
                )

    return MetricClass()


def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: Callable,
    num_threads: int,
    optimization_id: str | None = None,
    dataset_item_ids: list[str] | None = None,
    project_name: str | None = None,
    n_samples: int | None = None,
    experiment_config: dict[str, Any] | None = None,
    verbose: int = 1,
) -> float:
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

    Returns:
        float: The average score of the evaluated task.

    Note:
        If you need access to the raw evaluation result, use `evaluate_with_result`.
    """
    # NOTE: GEPA needs both the aggregate score and the raw Opik result so it can map
    # candidate trajectories back to GEPA's data structures. To avoid breaking every
    # optimizer call site, we keep this helper returning only the float and expose a
    # separate `evaluate_with_result` for the GEPA adapter. If more optimizers need
    # access to the full result we should refactor both functions to return a typed
    # dataclass (e.g., `EvaluationSummary` with `.score` and `.result`) or add an
    # overload that keeps the return type stable.
    score, _ = _evaluate_internal(
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
    return score


def evaluate_with_result(
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: Callable,
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


def _evaluate_internal(
    *,
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    metric: Callable,
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

    avg_score = sum(
        [score_result_.value for score_result_ in objective_score_results]
    ) / len(objective_score_results)

    return avg_score, evaluation_result
