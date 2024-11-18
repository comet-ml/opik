from typing import List, Dict, Any, Optional
import time

from .types import LLMTask
from .metrics import base_metric
from .. import Prompt
from ..api_objects.dataset import dataset
from ..api_objects.experiment import experiment_item
from ..api_objects import opik_client
from . import tasks_scorer, scores_logger, report, evaluation_result, utils


def evaluate(
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[Prompt] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset.

    Args:
        dataset: An Opik dataset instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        experiment_name: The name of the experiment associated with evaluation run.
            If None, a generated name will be used.

        project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`

        experiment_config: The dictionary with parameters that describe experiment

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.
            If no value provided, the experiment won't have any scoring metrics.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.
            0 - no outputs, 1 - outputs are enabled (default).

        nb_samples: number of samples to evaluate. If no value is provided, all samples in the dataset will be evaluated.

        task_threads: number of thread workers to run tasks. If set to 1, no additional
            threads are created, all tasks executed in the current thread sequentially.
            are executed sequentially in the current thread.
            Use more than 1 worker if your task object is compatible with sharing across threads.

        prompt: Prompt object to link with experiment.
    """
    client = opik_client.get_client_cached()
    if scoring_metrics is None:
        scoring_metrics = []

    start_time = time.time()

    test_results = tasks_scorer.run(
        client=client,
        dataset_=dataset,
        task=task,
        scoring_metrics=scoring_metrics,
        nb_samples=nb_samples,
        workers=task_threads,
        verbose=verbose,
        project_name=project_name,
    )

    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(dataset.name, total_time, test_results)

    scores_logger.log_scores(
        client=client, test_results=test_results, project_name=project_name
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompt=prompt,
    )

    report.display_experiment_link(dataset.name, experiment.id)

    experiment_items = [
        experiment_item.ExperimentItem(
            dataset_item_id=result.test_case.dataset_item_id,
            trace_id=result.test_case.trace_id,
        )
        for result in test_results
    ]

    experiment.insert(experiment_items=experiment_items)

    client.flush()

    evaluation_result_ = evaluation_result.EvaluationResult(
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
    )
    return evaluation_result_


def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_threads: int = 16,
    verbose: int = 1,
) -> evaluation_result.EvaluationResult:
    """Update existing experiment with new evaluation metrics.

    Args:
        experiment_name: The name of the experiment to update.

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.

        scoring_threads: amount of thread workers to run scoring metrics.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.
    """
    start_time = time.time()

    client = opik_client.get_client_cached()

    experiment = utils.get_experiment_by_name(
        client=client, experiment_name=experiment_name
    )

    test_cases = utils.get_experiment_test_cases(
        client=client, experiment_id=experiment.id, dataset_id=experiment.dataset_id
    )

    test_results = tasks_scorer.score(
        test_cases=test_cases,
        scoring_metrics=scoring_metrics,
        workers=scoring_threads,
        verbose=verbose,
    )

    first_trace_id = test_results[0].test_case.trace_id
    project_name = utils.get_trace_project_name(client=client, trace_id=first_trace_id)

    # Log scores - Needs to be updated to use the project name
    scores_logger.log_scores(
        client=client, test_results=test_results, project_name=project_name
    )
    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(
            experiment.dataset_name, total_time, test_results
        )

    report.display_experiment_link(experiment.dataset_name, experiment.id)

    evaluation_result_ = evaluation_result.EvaluationResult(
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
    )

    return evaluation_result_
