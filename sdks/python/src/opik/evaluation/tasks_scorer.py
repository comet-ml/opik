import tqdm
import logging
from concurrent import futures

from typing import List
from .types import LLMTask
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects import opik_client, trace
from opik import context_storage, opik_context, exceptions

from . import task_output, test_case, test_result
from .metrics import arguments_helpers, score_result, base_metric

LOGGER = logging.getLogger(__name__)


def _score_test_case(
    test_case_: test_case.TestCase, scoring_metrics: List[base_metric.BaseMetric]
) -> test_result.TestResult:
    score_results = []
    for metric in scoring_metrics:
        try:
            score_kwargs = test_case_.task_output.model_dump(exclude_none=False)
            arguments_helpers.raise_if_score_arguments_are_missing(
                score_function=metric.score,
                score_name=metric.name,
                kwargs=score_kwargs,
            )
            result = metric.score(**score_kwargs)
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
    item: dataset_item.DatasetItem,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
) -> test_result.TestResult:
    assert item.id is not None

    try:
        trace_data = trace.TraceData(
            input=item.input,
            name="evaluation_task",
        )
        context_storage.set_trace_data(trace_data)
        task_output_ = task(item)
        opik_context.update_current_trace(output=task_output_)

        test_case_ = test_case.TestCase(
            trace_id=trace_data.id,
            dataset_item_id=item.id,
            task_output=task_output.TaskOutput(**task_output_),
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


def run(
    client: opik_client.Opik,
    dataset_: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    workers: int,
    verbose: int,
) -> List[test_result.TestResult]:
    dataset_items = dataset_.get_all_items()
    test_cases: List[test_result.TestResult]

    if workers == 1:
        test_cases = [
            _process_item(
                client=client, item=item, task=task, scoring_metrics=scoring_metrics
            )
            for item in tqdm.tqdm(
                dataset_items,
                disable=(verbose < 1),
                desc="Evaluation",
                total=len(dataset_items),
            )
        ]
        return test_cases

    with futures.ThreadPoolExecutor(max_workers=workers) as pool:
        test_case_futures = [
            pool.submit(_process_item, client, item, task, scoring_metrics)
            for item in dataset_items
        ]

        test_cases = [
            test_case_future.result()
            for test_case_future in tqdm.tqdm(
                futures.as_completed(
                    test_case_futures,
                ),
                disable=(verbose < 1),
                desc="Evaluation",
                total=len(test_case_futures),
            )
        ]

    return test_cases
