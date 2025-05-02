import opik

from typing import Any, Callable, Dict, List, Optional
from opik_optimizer.optimization_dsl import MetricConfig
from opik.evaluation.metrics import score_result
from opik_optimizer.optimization_config import mappers


def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[Dict[str, Any]], Dict[str, Any]],
    metric_config: MetricConfig,
    num_threads: int,
    dataset_item_ids: Optional[List[str]] = None,
    project_name: Optional[str] = None,
    num_test: Optional[int] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
) -> float:
    """
    Evaluate a task on a dataset.

    Args:
        dataset: A list of dictionaries representing the dataset.
        metric_config: The metric configuration to use for evaluation.
        evaluated_task: A function that takes a dataset item dict as input and returns a dictionary with output(s).
        dataset_item_ids: Optional list of dataset item IDs to evaluate.
        project_name: Optional project name for evaluation.
        num_test: Optional number of test examples to perform the evaluation and then stop.
        num_threads: Number of threads to use for evaluation.
        experiment_config: The dictionary with parameters that describe experiment

    Returns:
        float: The average score of the evaluated task.
    """
    items = dataset.get_items(dataset_item_ids)
    if not items:
        print("[DEBUG] Empty dataset, returning 0.0")
        return 0.0

    if dataset_item_ids:
        items = [item for item in items if item.get("id") in dataset_item_ids]

    if num_test:
        items = items[:num_test]

    # TODO: move to debug logger
    # print(f"[DEBUG] Starting evaluation with task: {evaluated_task}")
    # print(f"[DEBUG] Items to evaluate: {items}")
    # print(f"[DEBUG] Metric config inputs: {metric_config.inputs}")
    # print(f"[DEBUG] Number of threads: {num_threads}")
    # print(f"[DEBUG] Project name: {project_name}")

    scoring_key_mapping = {
        key: value if isinstance(value, str) else value.__name__
        for key, value in metric_config.inputs.items()
    }
    scoring_key_mapping["output"] = "_llm_task_output"

    result = opik.evaluate(
        dataset=dataset,
        task=evaluated_task,
        project_name=project_name,
        scoring_key_mapping=scoring_key_mapping,
        dataset_item_ids=dataset_item_ids,
        scoring_metrics=[metric_config.metric],
        task_threads=num_threads,
        nb_samples=num_test,
        experiment_config=experiment_config,
    )

    if not result.test_results:
        return 0.0

    # We may allow score aggregation customization.
    score_results: List[score_result.ScoreResult] = [
        test_result.score_results[0] for test_result in result.test_results
    ]
    if not score_results:
        return 0.0

    avg_score = sum([score_result_.value for score_result_ in score_results]) / len(
        score_results
    )

    return avg_score
