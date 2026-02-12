import logging
import math
from typing import Any, Literal, overload
from collections.abc import Callable

import inspect

import opik
from ..api_objects.types import MetricFunction
from ..utils.reporting import suppress_experiment_reporting
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation.metrics import base_metric, score_result
from ..metrics.multi_metric_objective import MultiMetricObjective

logger = logging.getLogger(__name__)

try:
    from opik import evaluate_on_dict_items as _opik_evaluate_on_dict_items
except Exception:  # pragma: no cover - older Opik SDK fallback
    _opik_evaluate_on_dict_items = None

_EVALUATE_ON_DICT_ITEMS_ACCEPTS_EXPERIMENT_CONFIG = (
    _opik_evaluate_on_dict_items is not None
    and "experiment_config"
    in inspect.signature(_opik_evaluate_on_dict_items).parameters
)


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
                if isinstance(metric, MultiMetricObjective):
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
    use_evaluate_on_dict_items: bool = False,
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
    use_evaluate_on_dict_items: bool = False,
) -> (
    opik_evaluation_result.EvaluationResult
    | opik_evaluation_result.EvaluationResultOnDictItems
): ...


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
    use_evaluate_on_dict_items: bool = False,
) -> (
    float
    | opik_evaluation_result.EvaluationResult
    | opik_evaluation_result.EvaluationResultOnDictItems
):
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
        use_evaluate_on_dict_items=use_evaluate_on_dict_items,
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
    use_evaluate_on_dict_items: bool = False,
) -> tuple[
    float,
    opik_evaluation_result.EvaluationResult
    | opik_evaluation_result.EvaluationResultOnDictItems
    | None,
]:
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
        use_evaluate_on_dict_items=use_evaluate_on_dict_items,
    )


def _normalize_id(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


def _filter_items_by_ids(
    *,
    items: list[dict[str, Any]],
    dataset_item_ids: list[str] | None,
    dataset_name: str,
) -> tuple[list[dict[str, Any]], list[str] | None]:
    if not dataset_item_ids:
        return items, None

    available_ids = {
        normalized_id
        for item in items
        if (normalized_id := _normalize_id(item.get("id"))) is not None
    }
    normalized_requested = [
        normalized_id
        for item_id in dataset_item_ids
        if (normalized_id := _normalize_id(item_id)) is not None
    ]
    missing_ids = [
        item_id for item_id in normalized_requested if item_id not in available_ids
    ]
    if missing_ids:
        logger.warning(
            "Dropping %s dataset_item_ids not present in dataset %s (showing first 5): %s",
            len(missing_ids),
            dataset_name,
            missing_ids[:5],
        )
    dataset_item_ids = [
        item_id for item_id in normalized_requested if item_id in available_ids
    ]
    if not dataset_item_ids:
        logger.warning(
            "All provided dataset_item_ids were missing; evaluating on full dataset instead."
        )
        return items, None

    filtered_items = [
        item for item in items if _normalize_id(item.get("id")) in dataset_item_ids
    ]
    logger.debug(
        "Evaluating %s items (filtered by dataset_item_ids).",
        len(filtered_items),
    )
    return filtered_items, dataset_item_ids


def _evaluate_on_dict_items(
    *,
    items: list[dict[str, Any]],
    task: Callable[[dict[str, Any]], dict[str, Any]],
    scoring_metrics: list[base_metric.BaseMetric],
    project_name: str | None,
    verbose: int,
    scoring_threads: int,
    experiment_config: dict[str, Any] | None,
) -> opik_evaluation_result.EvaluationResultOnDictItems:
    if _opik_evaluate_on_dict_items is None:
        raise RuntimeError(
            "opik.evaluate_on_dict_items is not available in this SDK version."
        )

    call_kwargs: dict[str, Any] = {
        "items": items,
        "task": task,
        "scoring_metrics": scoring_metrics,
        "project_name": project_name,
        "verbose": verbose,
        "scoring_threads": scoring_threads,
    }
    if _EVALUATE_ON_DICT_ITEMS_ACCEPTS_EXPERIMENT_CONFIG:
        call_kwargs["experiment_config"] = experiment_config
    return _opik_evaluate_on_dict_items(**call_kwargs)


def _run_evaluator(
    *,
    optimization_id: str | None,
    dataset: opik.Dataset,
    evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
    project_name: str | None,
    dataset_item_ids: list[str] | None,
    scoring_metrics: list[base_metric.BaseMetric],
    num_threads: int,
    n_samples: int | None,
    experiment_config: dict[str, Any] | None,
    verbose: int,
) -> opik_evaluation_result.EvaluationResult:
    if optimization_id is not None:
        return opik_evaluator.evaluate_optimization_trial(
            optimization_id=optimization_id,
            dataset=dataset,
            task=evaluated_task,
            project_name=project_name,
            dataset_item_ids=dataset_item_ids,
            scoring_metrics=scoring_metrics,
            task_threads=num_threads,
            nb_samples=n_samples,
            experiment_config=experiment_config,
            verbose=verbose,
        )
    return opik_evaluator.evaluate(
        dataset=dataset,
        task=evaluated_task,
        project_name=project_name,
        dataset_item_ids=dataset_item_ids,
        scoring_metrics=scoring_metrics,
        task_threads=num_threads,
        nb_samples=n_samples,
        experiment_config=experiment_config,
        verbose=verbose,
    )


def _extract_objective_scores(
    evaluation_result: opik_evaluation_result.EvaluationResult,
    objective_metric_name: str,
) -> list[score_result.ScoreResult]:
    objective_score_results: list[score_result.ScoreResult] = []
    for test_result in evaluation_result.test_results:
        for score_result_ in test_result.score_results:
            if score_result_.name == objective_metric_name:
                objective_score_results.append(score_result_)
                break
    return objective_score_results


def build_evaluation_report(
    *,
    evaluation_result: (
        opik_evaluation_result.EvaluationResult
        | opik_evaluation_result.EvaluationResultOnDictItems
        | None
    ),
    objective_metric_name: str,
) -> dict[str, Any]:
    """Build a compact report for objective-metric outcomes of one evaluation run.

    Report fields:
    - ``objective_metric``: Objective metric name used for extraction.
    - ``evaluated_items``: Number of evaluated test rows.
    - ``objective_scores``: Number of objective metric results found.
    - ``failed_objective_scores``: Count of objective scores with
      ``scoring_failed=True``.
    - ``failed_ratio``: ``failed_objective_scores / objective_scores`` when
      objective scores exist, otherwise ``0.0``.
    - ``failed_objective_samples``: Up to 5 failed objective score samples with
      ``name``, ``value``, ``reason``, and ``metadata``.

    Edge cases:
    - When ``evaluation_result`` is ``None``, this returns an empty report
      (0 items, 0 objective scores, empty failed samples).
    - ``failed_ratio`` is ``0.0`` when objective scores are absent.
    """
    if evaluation_result is None:
        return {
            "objective_metric": objective_metric_name,
            "evaluated_items": 0,
            "objective_scores": 0,
            "failed_objective_scores": 0,
            "failed_ratio": 0.0,
            "failed_objective_samples": [],
        }

    objective_scores = _extract_objective_scores(
        evaluation_result, objective_metric_name
    )
    failed = [score for score in objective_scores if score.scoring_failed]
    failed_samples = [
        {
            "name": score.name,
            "value": score.value,
            "reason": score.reason,
            "metadata": score.metadata,
        }
        for score in failed[:5]
    ]

    return {
        "objective_metric": objective_metric_name,
        "evaluated_items": len(evaluation_result.test_results),
        "objective_scores": len(objective_scores),
        "failed_objective_scores": len(failed),
        "failed_ratio": len(failed) / len(objective_scores)
        if objective_scores
        else 0.0,
        "failed_objective_samples": failed_samples,
    }


def _average_finite_scores(
    scores: list[score_result.ScoreResult], *, objective_metric_name: str
) -> float:
    finite_values = [
        score_result_.value
        for score_result_ in scores
        if (
            not score_result_.scoring_failed
            and score_result_.value is not None
            and math.isfinite(score_result_.value)
        )
    ]
    if not finite_values:
        logger.error(
            "No successful finite metric scores for metric '%s'; aborting evaluation.",
            objective_metric_name,
        )
        raise ValueError(
            f"No successful finite metric scores for metric '{objective_metric_name}'."
        )
    return sum(finite_values) / len(finite_values)


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
    use_evaluate_on_dict_items: bool,
) -> tuple[
    float,
    opik_evaluation_result.EvaluationResult
    | opik_evaluation_result.EvaluationResultOnDictItems
    | None,
]:
    items = dataset.get_items(n_samples)
    if not items:
        logger.debug("Empty dataset; returning 0.0")
        return 0.0, None

    items, dataset_item_ids = _filter_items_by_ids(
        items=items,
        dataset_item_ids=dataset_item_ids,
        dataset_name=getattr(dataset, "name", None) or "<unknown>",
    )

    eval_metrics = [_create_metric_class(metric)]
    if use_evaluate_on_dict_items:
        # TODO(opik-sdk): remove this branch once dict-item evaluation is the default.
        if _evaluate_on_dict_items is None:
            raise RuntimeError(
                "opik.evaluate_on_dict_items is not available in this SDK version."
            )
        dict_items = [
            {key: value for key, value in item.items() if key != "id"} for item in items
        ]
        evaluation_result = _evaluate_on_dict_items(
            items=dict_items,
            task=evaluated_task,
            scoring_metrics=eval_metrics,
            project_name=project_name,
            verbose=verbose,
            scoring_threads=num_threads,
            experiment_config=experiment_config,
        )
    else:
        evaluation_result = _run_evaluator(
            optimization_id=optimization_id,
            dataset=dataset,
            evaluated_task=evaluated_task,
            project_name=project_name,
            dataset_item_ids=dataset_item_ids,
            scoring_metrics=eval_metrics,
            num_threads=num_threads,
            n_samples=n_samples,
            experiment_config=experiment_config,
            verbose=verbose,
        )

    if not evaluation_result.test_results:
        return 0.0, evaluation_result

    # Filter score results to only include the objective metric
    objective_metric_name = metric.__name__
    objective_score_results = _extract_objective_scores(
        evaluation_result, objective_metric_name
    )

    if not objective_score_results:
        return 0.0, evaluation_result

    avg_score = _average_finite_scores(
        objective_score_results, objective_metric_name=objective_metric_name
    )
    return avg_score, evaluation_result
