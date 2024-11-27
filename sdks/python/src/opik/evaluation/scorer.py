import tqdm
import logging
from concurrent import futures

from typing import List, Optional, Dict, Any, Union, Callable
from .types import LLMTask
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment, experiment_item
from opik.api_objects import opik_client, trace
from opik import context_storage, opik_context, exceptions

from . import test_case, test_result
from .metrics import arguments_helpers, score_result, base_metric

LOGGER = logging.getLogger(__name__)


def _score_test_case(
    test_case_: test_case.TestCase,
    scoring_metrics: List[base_metric.BaseMetric],
) -> test_result.TestResult:
    score_results = []

    for metric in scoring_metrics:
        try:
            score_kwargs = test_case_.scoring_inputs
            arguments_helpers.raise_if_score_arguments_are_missing(
                score_function=metric.score,
                score_name=metric.name,
                kwargs=score_kwargs,
            )
            LOGGER.debug("Metric %s score started", metric.name)
            result = metric.score(**score_kwargs)
            LOGGER.debug("Metric %s score ended", metric.name)

            if isinstance(result, list):
                score_results += result
            else:
                score_results.append(result)
        except exceptions.ScoreMethodMissingArguments:
            raise
        except Exception as e:
            # This can be problematic if the metric returns a list of strings as we will not know the name of the metrics that have failed
            LOGGER.error(
                "Failed to compute metric %s. Score result will be marked as failed.",
                metric.name,
                exc_info=True,
            )

            score_results.append(
                score_result.ScoreResult(
                    name=metric.name, value=0.0, reason=str(e), scoring_failed=True
                )
            )

    test_result_ = test_result.TestResult(
        test_case=test_case_, score_results=score_results
    )

    return test_result_


def _process_item(
    client: opik_client.Opik,
    experiment_: experiment.Experiment,
    item: dataset_item.DatasetItem,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    project_name: Optional[str],
    scoring_key_mapping: Optional[
        Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]
    ],
) -> test_result.TestResult:
    try:
        trace_data = trace.TraceData(
            input=item.get_content(),
            name="evaluation_task",
            created_by="evaluation",
            project_name=project_name,
        )
        context_storage.set_trace_data(trace_data)
        item_content = item.get_content()
        LOGGER.debug("Task started, input: %s", item_content)
        task_output_ = task(item_content)
        LOGGER.debug("Task finished, output: %s", task_output_)

        opik_context.update_current_trace(output=task_output_)

        scoring_inputs = arguments_helpers.create_scoring_inputs(
            dataset_item=item_content,
            task_output=task_output_,
            scoring_key_mapping=scoring_key_mapping,
        )

        test_case_ = test_case.TestCase(
            trace_id=trace_data.id,
            dataset_item_id=item.id,
            scoring_inputs=scoring_inputs,
            task_output=task_output_,
        )

        test_result_ = _score_test_case(
            test_case_=test_case_, scoring_metrics=scoring_metrics
        )

        return test_result_

    finally:
        trace_data = context_storage.pop_trace_data()  # type: ignore
        assert trace_data is not None
        trace_data.init_end_time()
        client.trace(**trace_data.__dict__)
        experiment_item_ = experiment_item.ExperimentItem(
            dataset_item_id=item.id,
            trace_id=trace_data.id,
        )

        experiment_.insert(experiment_items=[experiment_item_])


def score_tasks(
    client: opik_client.Opik,
    experiment_: experiment.Experiment,
    dataset_: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    workers: int,
    nb_samples: Optional[int],
    verbose: int,
    project_name: Optional[str],
    scoring_key_mapping: Optional[
        Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]
    ],
) -> List[test_result.TestResult]:
    dataset_items = dataset_.__internal_api__get_items_as_dataclasses__(
        nb_samples=nb_samples
    )
    test_results: List[test_result.TestResult]

    if workers == 1:
        test_results = [
            _process_item(
                client=client,
                experiment_=experiment_,
                item=item,
                task=task,
                scoring_metrics=scoring_metrics,
                project_name=project_name,
                scoring_key_mapping=scoring_key_mapping,
            )
            for item in tqdm.tqdm(
                dataset_items,
                disable=(verbose < 1),
                desc="Evaluation",
                total=len(dataset_items),
            )
        ]
        return test_results

    with futures.ThreadPoolExecutor(max_workers=workers) as pool:
        test_result_futures = [
            pool.submit(
                _process_item,
                client=client,
                experiment_=experiment_,
                item=item,
                task=task,
                scoring_metrics=scoring_metrics,
                project_name=project_name,
                scoring_key_mapping=scoring_key_mapping,
            )
            for item in dataset_items
        ]

        test_results = [
            test_result_future.result()
            for test_result_future in tqdm.tqdm(
                futures.as_completed(
                    test_result_futures,
                ),
                disable=(verbose < 1),
                desc="Evaluation",
                total=len(test_result_futures),
            )
        ]

    return test_results


def score_test_cases(
    test_cases: List[test_case.TestCase],
    scoring_metrics: List[base_metric.BaseMetric],
    workers: int,
    verbose: int,
) -> List[test_result.TestResult]:
    if workers == 1:
        test_results = [
            _score_test_case(test_case_=test_case_, scoring_metrics=scoring_metrics)
            for test_case_ in tqdm.tqdm(
                test_cases,
                disable=(verbose < 1),
                desc="Scoring",
                total=len(test_cases),
            )
        ]
    else:
        with futures.ThreadPoolExecutor(max_workers=workers) as pool:
            test_result_futures = [
                pool.submit(_score_test_case, test_case_, scoring_metrics)
                for test_case_ in test_cases
            ]

            test_results = [
                test_result_future.result()
                for test_result_future in tqdm.tqdm(
                    futures.as_completed(
                        test_result_futures,
                    ),
                    disable=(verbose < 1),
                    desc="Evaluation",
                    total=len(test_result_futures),
                )
            ]

    return test_results
