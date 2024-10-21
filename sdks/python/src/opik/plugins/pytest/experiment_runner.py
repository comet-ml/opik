from typing import List, Callable, cast
from pytest import Item

import json

from opik.api_objects import opik_client
from opik.api_objects.experiment import experiment_item
from opik.api_objects.dataset import dataset_item
from opik.api_objects import helpers

from opik import datetime_helpers, dict_utils
from . import test_runs_storage, test_run_content


def get_dataset_item_id_finder(
    existing_dataset_items: List[dataset_item.DatasetItem],
) -> Callable[[test_run_content.TestRunContent], str]:
    """
    Returns a callable that takes TestRunContent as input and checks
    if there is already a dataset item with such a content (input, expected_output and metadata).
    """
    dataset_item_content_to_ids = {
        json.dumps(dataset_item.get_content(), sort_keys=True): dataset_item.id
        for dataset_item in existing_dataset_items
    }

    def callback(content: test_run_content.TestRunContent) -> str:
        content_str = json.dumps(content.__dict__, sort_keys=True)
        result = dataset_item_content_to_ids.get(content_str, None)
        return cast(str, result)

    return callback


def run(client: opik_client.Opik, test_items: List[Item]) -> None:
    experiment_name = f"Test-Suite-{datetime_helpers.local_timestamp()}"

    try:
        dataset = client.get_dataset("tests")
    except Exception:
        dataset = client.create_dataset("tests")

    dataset_items = dataset.__internal_api__get_items_as_dataclasses__()
    dataset_item_id_finder = get_dataset_item_id_finder(
        existing_dataset_items=dataset_items
    )

    experiment = client.create_experiment(name=experiment_name, dataset_name="tests")

    experiment_items: List[experiment_item.ExperimentItem] = []
    dataset_items_to_create: List[dataset_item.DatasetItem] = []

    for test_item in test_items:
        test_run_content = test_runs_storage.TEST_RUNS_CONTENTS[test_item.nodeid]
        test_run_trace_id = test_runs_storage.TEST_RUNS_TO_TRACE_DATA[
            test_item.nodeid
        ].id

        dataset_item_id = dataset_item_id_finder(test_run_content)

        if dataset_item_id is None:
            dataset_item_id = helpers.generate_id()
            filtered_test_run_content_dict = dict_utils.remove_none_from_dict(
                test_run_content.__dict__
            )
            dataset_item_ = dataset_item.DatasetItem(
                id=dataset_item_id,
                **filtered_test_run_content_dict,
            )
            dataset_items_to_create.append(dataset_item_)

        experiment_items.append(
            experiment_item.ExperimentItem(
                dataset_item_id=dataset_item_id,
                trace_id=test_run_trace_id,
            )
        )

    dataset.__internal_api__insert_items_as_dataclasses__(items=dataset_items_to_create)
    experiment.insert(experiment_items=experiment_items)
    client.flush()
