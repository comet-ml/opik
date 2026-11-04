from types import SimpleNamespace
from unittest import mock

import pytest

from opik.api_objects.dataset import dataset_item
from opik.plugins.pytest import experiment_runner, test_run_content, test_runs_storage


def setup_function(function: object) -> None:
    del function
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA.clear()
    test_runs_storage.TEST_RUNS_CONTENTS.clear()


class _FakeDataset:
    def __init__(self, items):
        self.items = items
        self.inserted = None

    def __internal_api__stream_items_as_dataclasses__(self):
        return iter(self.items)

    def __internal_api__insert_items_as_dataclasses__(self, items):
        self.inserted = items


def _stored_item(item_id: str, content: test_run_content.TestRunContent):
    # Stored items have no key for a field the test did not set.
    fields = {"input": content.input}
    if content.expected_output is not None:
        fields["expected_output"] = content.expected_output
    if content.metadata is not None:
        fields["metadata"] = content.metadata
    return dataset_item.DatasetItem(id=item_id, **fields)


@pytest.mark.parametrize(
    "expected_output, metadata",
    [
        (None, None),
        ({"expected_output": "4"}, None),
        (None, {"k": 1}),
        ({"expected_output": "4"}, {"k": 1}),
    ],
)
def test_run__unchanged_test__reuses_existing_dataset_item(expected_output, metadata):
    content = test_run_content.TestRunContent(
        input={"test_name": "test_llm.py::test_a", "input": "What is 2+2?"},
        expected_output=expected_output,
        metadata=metadata,
    )
    test_runs_storage.TEST_RUNS_CONTENTS["test_llm.py::test_a"] = content
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA["test_llm.py::test_a"] = SimpleNamespace(
        id="trace-1", project_name="project"
    )

    dataset = _FakeDataset([_stored_item("item-1", content)])
    experiment = mock.Mock()
    client = mock.Mock()
    client.get_dataset.return_value = dataset
    client.create_experiment.return_value = experiment

    experiment_runner.run(
        client=client, test_items=[SimpleNamespace(nodeid="test_llm.py::test_a")]
    )

    assert dataset.inserted == []
    (references,) = experiment.insert.call_args.kwargs["experiment_items_references"]
    assert references.dataset_item_id == "item-1"
    assert references.trace_id == "trace-1"
