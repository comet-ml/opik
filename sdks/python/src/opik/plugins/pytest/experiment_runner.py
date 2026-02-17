import dataclasses
import json
from typing import Callable, List, Optional

from pytest import Item

from opik.api_objects import opik_client
from opik.api_objects.experiment import experiment_item
from opik.api_objects.dataset import dataset_item
import opik.id_helpers as id_helpers

import opik.datetime_helpers as datetime_helpers
import opik.dict_utils as dict_utils
from . import test_runs_storage, test_run_content


def _serialize_content(value: object) -> str:
    return json.dumps(value, sort_keys=True, default=str)


def get_dataset_item_id_finder(
    existing_dataset_items: List[dataset_item.DatasetItem],
) -> Callable[[test_run_content.TestRunContent], Optional[str]]:
    """
    Returns a callable that takes TestRunContent as input and checks
    if there is already a dataset item with such a content (input, expected_output and metadata).
    """
    dataset_item_content_to_ids = {
        _serialize_content(dataset_item.get_content()): dataset_item.id
        for dataset_item in existing_dataset_items
    }

    def callback(content: test_run_content.TestRunContent) -> Optional[str]:
        normalized_content = dict_utils.remove_none_from_dict(
            dataclasses.asdict(content)
        )
        content_str = _serialize_content(normalized_content)
        result = dataset_item_content_to_ids.get(content_str, None)
        return result

    return callback


def run(
    client: opik_client.Opik,
    test_items: List[Item],
    dataset_name: str = "tests",
    experiment_name_prefix: str = "Test-Suite",
) -> None:
    """Create/update pytest experiment artifacts for tracked llm tests.

    Uses ``client.get_or_create_dataset`` and creates an experiment scoped to this run,
    deduplicates dataset items by serialized content, inserts missing dataset/experiment
    items, and flushes. Exceptions from client operations are propagated.
    """
    timestamp = datetime_helpers.datetime_to_iso8601(datetime_helpers.local_timestamp())
    experiment_name = f"{experiment_name_prefix}-{timestamp}"

    dataset = client.get_or_create_dataset(dataset_name)

    dataset_items = list(dataset.__internal_api__stream_items_as_dataclasses__())
    dataset_item_id_finder = get_dataset_item_id_finder(
        existing_dataset_items=dataset_items
    )

    experiment = client.create_experiment(
        name=experiment_name, dataset_name=dataset_name
    )

    experiment_items: List[experiment_item.ExperimentItemReferences] = []
    dataset_items_to_create: List[dataset_item.DatasetItem] = []

    for test_item in test_items:
        test_run_content = test_runs_storage.TEST_RUNS_CONTENTS[test_item.nodeid]
        test_run_trace_data = test_runs_storage.TEST_RUNS_TO_TRACE_DATA[
            test_item.nodeid
        ]
        test_run_trace_id = test_run_trace_data.id
        test_run_project_name = test_run_trace_data.project_name

        dataset_item_id = dataset_item_id_finder(test_run_content)

        if dataset_item_id is None:
            dataset_item_id = id_helpers.generate_id()
            filtered_test_run_content_dict = dict_utils.remove_none_from_dict(
                dataclasses.asdict(test_run_content)
            )
            dataset_item_ = dataset_item.DatasetItem(
                id=dataset_item_id,
                **filtered_test_run_content_dict,
            )
            dataset_items_to_create.append(dataset_item_)
            # Keep the in-memory index current to avoid duplicate inserts in the same run.
            dataset_item_id_finder = get_dataset_item_id_finder(
                existing_dataset_items=dataset_items + dataset_items_to_create
            )

        experiment_items.append(
            experiment_item.ExperimentItemReferences(
                dataset_item_id=dataset_item_id,
                trace_id=test_run_trace_id,
                project_name=test_run_project_name,
            )
        )

    dataset.__internal_api__insert_items_as_dataclasses__(items=dataset_items_to_create)
    experiment.insert(experiment_items_references=experiment_items)
    client.flush()
