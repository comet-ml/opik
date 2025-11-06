import logging
from typing import Callable, Dict, List

from . import evaluation_result
from .metrics import experiment_metric_result

LOGGER = logging.getLogger(__name__)


def compute_experiment_metrics(
    experiment_metrics: List[Callable],
    evaluation_result_: evaluation_result.EvaluationResult,
) -> Dict[str, Dict[str, float]]:
    """
    Compute experiment-level metrics and convert them to the backend format.

    Args:
        experiment_metrics: List of callable functions that compute experiment metrics
        evaluation_result_: The evaluation result containing all test results

    Returns:
        Dictionary in format {score_name: {metric_name: value}}
        Example: {"hallucination": {"avg": 0.85, "median": 0.9}}
    """
    all_metric_results: List[experiment_metric_result.ExperimentMetricResult] = []

    for metric_fn in experiment_metrics:
        try:
            result = metric_fn(evaluation_result_)

            # Handle both single result and list of results
            if isinstance(result, experiment_metric_result.ExperimentMetricResult):
                all_metric_results.append(result)
            elif isinstance(result, list):
                all_metric_results.extend(result)
            else:
                LOGGER.warning(
                    "Experiment metric function %s returned unexpected type %s. "
                    "Expected ExperimentMetricResult or List[ExperimentMetricResult].",
                    getattr(metric_fn, "__name__", "unknown"),
                    type(result).__name__,
                )
        except Exception as exception:
            LOGGER.error(
                "Failed to compute experiment metric %s: %s",
                getattr(metric_fn, "__name__", "unknown"),
                str(exception),
                exc_info=True,
            )

    return _convert_to_backend_format(all_metric_results)


def _convert_to_backend_format(
    metric_results: List[experiment_metric_result.ExperimentMetricResult],
) -> Dict[str, Dict[str, float]]:
    """
    Convert list of ExperimentMetricResult to nested dictionary format.

    Args:
        metric_results: List of experiment metric results

    Returns:
        Dictionary in format {score_name: {metric_name: value}}
    """
    result: Dict[str, Dict[str, float]] = {}

    for metric_result in metric_results:
        score_name = metric_result.score_name
        metric_name = metric_result.metric_name
        value = metric_result.value

        if score_name not in result:
            result[score_name] = {}

        # If there's a duplicate, warn and keep the first value
        if metric_name in result[score_name]:
            LOGGER.warning(
                "Duplicate metric %s for score %s. Keeping first value %.4f, ignoring %.4f",
                metric_name,
                score_name,
                result[score_name][metric_name],
                value,
            )
        else:
            result[score_name][metric_name] = value

    return result
