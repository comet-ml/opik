import time
from typing import List

from .types import LLMTask
from .metrics import base_metric
from ..api_objects.dataset import dataset
from ..api_objects.experiment import experiment_item
from ..api_objects import opik_client

from . import task_runner, test_result, scoring_runner, scores_logger, report


def evaluate(
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    experiment_name: str,
    verbose: int = 1,
    task_threads: int = 16,
    scoring_threads: int = 16,
) -> List[test_result.TestResult]:
    """
    Performs task evaluation on a given dataset.

    Args:
        dataset: An Opik dataset instance

        task: A callable object that takes DatasetItem as input and returns
            dictionary which will later be used for scoring

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.

        task_threads: amount of thread workers to run tasks. If set to 1, no additional
            threads are created, all tasks executed in the current thread sequentially.
            are executed sequentially in the current thread.
            Use more than 1 worker if your task object is compatible with sharing across threads.

        scoring_threads: amount of thread workers to compute metric scores. If set to 1,
            no additional threads are created, all metrics are computed in the
            current thread sequentially.
            Use more than 1 worker if your metrics are compatible with sharing across threads.
    """
    client = opik_client.get_client_cached()
    start_time = time.time()

    test_cases = task_runner.run(
        client=client,
        dataset_=dataset,
        task=task,
        workers=task_threads,
        verbose=verbose,
    )

    test_results = scoring_runner.run(
        test_cases=test_cases,
        scoring_metrics=scoring_metrics,
        workers=scoring_threads,
        verbose=verbose,
    )

    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(dataset.name, total_time, test_results)

    scores_logger.log_scores(client=client, test_results=test_results)

    experiment = client.create_experiment(
        name=experiment_name, dataset_name=dataset.name
    )
    experiment_items = [
        experiment_item.ExperimentItem(
            dataset_item_id=result.test_case.dataset_item_id,
            trace_id=result.test_case.trace_id,
        )
        for result in test_results
    ]

    experiment.insert(experiment_items=experiment_items)

    client.flush()
    return test_results
