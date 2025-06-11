import logging
from typing import Any, Callable, Dict, List, Optional

import opik
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation.metrics import base_metric, score_result

logger = logging.getLogger(__name__)

def _create_metric_class(metric: Callable):
    class MetricClass(base_metric.BaseMetric):
        def __init__(self):
            self.name = metric.__name__

        def score(self, llm_output, **kwargs) -> score_result.ScoreResult:
            try:
                metric_val = metric(dataset_item=kwargs, llm_output=llm_output)
                if isinstance(metric_val , score_result.ScoreResult):
                    return score_result.ScoreResult(
                        name = self.name,
                        value = metric_val.value,
                        scoring_failed=metric_val.scoring_failed,
                        metadata=metric_val.metadata,
                        reason=metric_val.reason
                    )
                else:
                    return score_result.ScoreResult(
                        name = self.name,
                        value = metric_val,
                        scoring_failed=False
                    )
            except Exception:
                return score_result.ScoreResult(
                    name = self.name,
                    value = 0,
                    scoring_failed=True
                )

    return MetricClass()

def evaluate(
    dataset: opik.Dataset,
    evaluated_task: Callable[[Dict[str, Any]], Dict[str, Any]],
    metric: Callable,
    num_threads: int,
    optimization_id: Optional[str] = None,
    dataset_item_ids: Optional[List[str]] = None,
    project_name: Optional[str] = None,
    n_samples: Optional[int] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
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
    """
    items = dataset.get_items(n_samples)
    if not items:
        print("[DEBUG] Empty dataset, returning 0.0")
        return 0.0

    if dataset_item_ids:
        items = [item for item in items if item.get("id") in dataset_item_ids]

    eval_metrics = [_create_metric_class(metric)]
    
    if optimization_id is not None:
        result = opik_evaluator.evaluate_optimization_trial(
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
        result = opik_evaluator.evaluate(
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
