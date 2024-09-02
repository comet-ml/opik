import tqdm
from concurrent import futures

from typing import List
from .types import LLMTask
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects import opik_client
from opik import context_storage

from . import task_output, test_case


def _process_item(
    client: opik_client.Opik, item: dataset_item.DatasetItem, task: LLMTask
) -> test_case.TestCase:
    assert item.id is not None

    try:
        trace = client.trace(input=item.input, name="evaluation_task")
        context_storage.set_trace(trace)
        task_output_ = task(item)
        trace.end(output=task_output_)

        test_case_ = test_case.TestCase(
            trace_id=trace.id,
            dataset_item_id=item.id,
            task_output=task_output.TaskOutput(**task_output_),
        )
        return test_case_

    finally:
        context_storage.pop_trace()


def run(
    client: opik_client.Opik,
    dataset_: dataset.Dataset,
    task: LLMTask,
    workers: int,
    verbose: int,
) -> List[test_case.TestCase]:
    dataset_items = dataset_.get_all_items()
    test_cases: List[test_case.TestCase]

    if workers == 1:
        test_cases = [
            _process_item(client, item, task)
            for item in tqdm.tqdm(
                dataset_items,
                disable=(verbose < 1),
                desc="Running tasks",
                total=len(dataset_items),
            )
        ]
        return test_cases

    with futures.ThreadPoolExecutor(max_workers=workers) as pool:
        test_case_futures = [
            pool.submit(_process_item, client, item, task) for item in dataset_items
        ]

        test_cases = [
            test_case_future.result()
            for test_case_future in tqdm.tqdm(
                futures.as_completed(
                    test_case_futures,
                ),
                disable=(verbose < 1),
                desc="Running tasks",
                total=len(test_case_futures),
            )
        ]

    return test_cases
