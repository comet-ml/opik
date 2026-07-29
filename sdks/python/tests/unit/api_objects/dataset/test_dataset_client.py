from unittest.mock import Mock

import pytest

from opik.api_objects import constants
from opik.api_objects.dataset.dataset import Dataset


def _make_items(count: int) -> list:
    return [
        {"input": {"i": i}, "expected_output": {"o": i}, "metadata": {"m": i}}
        for i in range(count)
    ]


def test_insert_deduplication__two_dicts_passed_with_the_same_content__only_one_is_inserted():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    item_dict = {
        "input": {"key": "value", "key2": "value2"},
        "expected_output": {"key": "value", "key2": "value2"},
        "metadata": {"key": "value", "key2": "value2"},
    }

    # Insert the identical items
    dataset.insert([item_dict, item_dict])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    assert len(inserted_items) == 1, "Only one item should be inserted"


def test_insert_deduplication__two_dicts_passed_with_the_different_content__both_are_inserted():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    item_dict1 = {
        "input": {"key": "value1"},
        "expected_output": {"key": "output1"},
        "metadata": {"key": "meta1"},
    }
    item_dict2 = {
        "input": {"key": "value2"},
        "expected_output": {"key": "output2"},
        "metadata": {"key": "meta2"},
    }

    # Insert the different items
    dataset.insert([item_dict1, item_dict2])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_items = call_args[1]["items"]

    assert len(inserted_items) == 2, "Two items should be inserted"


def test_insert_deduplication__three_dicts_passed__one_unique__two_duplicates__two_different_items_are_inserted():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    item_dict1 = {
        "input": {"key": "value1"},
        "expected_output": {"key": "output1"},
        "metadata": {"key": "meta1"},
    }
    item_dict2 = {
        "input": {"key": "value2"},
        "expected_output": {"key": "output2"},
        "metadata": {"key": "meta2"},
    }

    # Insert 3 items: one unique and two duplicates
    dataset.insert([item_dict1, item_dict2, item_dict1])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called only once"
    )

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    inserted_rest_items = call_args[1]["items"]

    assert len(inserted_rest_items) == 2, "Two items should be inserted"


def test_update__happyflow():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    initial_item = {
        "input": {"key": "initial_value"},
        "expected_output": {"key": "initial_output"},
        "metadata": {"key": "initial_metadata"},
    }

    dataset.insert([initial_item])

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1, (
        "create_or_update_dataset_items should be called once for insertion"
    )

    insert_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    inserted_items = insert_call_args[1]["items"]

    assert len(inserted_items) == 1, "One item should be inserted"

    # Create an updated version of the item
    updated_item = {
        "id": inserted_items[0].id,
        "input": {"key": "updated_value"},
        "expected_output": {"key": "updated_output"},
        "metadata": {"key": "updated_metadata"},
    }

    # Update the item
    dataset.update([updated_item])

    # Check that create_or_update_dataset_items was called twice in total (once for insertion, once for update)
    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2, (
        "create_or_update_dataset_items should be called twice in total"
    )

    # Get the arguments passed to create_or_update_dataset_items for update
    update_call_args = (
        mock_rest_client.datasets.create_or_update_dataset_items.call_args
    )
    updated_rest_items = update_call_args[1]["items"]

    # Check that one item was updated
    assert len(updated_rest_items) == 1, "One item should be updated"

    # Verify the content of the updated item
    assert updated_rest_items[0].data["input"] == {"key": "updated_value"}, (
        "Input should be updated"
    )
    assert updated_rest_items[0].data["expected_output"] == {"key": "updated_output"}, (
        "Expected output should be updated"
    )
    assert updated_rest_items[0].data["metadata"] == {"key": "updated_metadata"}, (
        "Metadata should be updated"
    )


def _small_batches(monkeypatch, size: int = 2) -> None:
    """Shrink the batch size so a handful of items produce multiple batches."""
    monkeypatch.setattr(constants, "DATASET_ITEMS_MAX_BATCH_SIZE", size)


def test_insert__parallel__all_batches_sent_under_one_batch_group_id(monkeypatch):
    _small_batches(monkeypatch, size=2)
    mock_rest_client = Mock()
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    dataset.insert(_make_items(10), num_threads=4)

    create_or_update = mock_rest_client.datasets.create_or_update_dataset_items
    assert create_or_update.call_count == 5, "10 items / batch size 2 => 5 batches"

    batch_group_ids = {
        call.kwargs["batch_group_id"] for call in create_or_update.call_args_list
    }
    assert len(batch_group_ids) == 1, (
        "All parallel batches must share one batch_group_id (single version)"
    )

    sent_inputs = sorted(
        item.data["input"]["i"]
        for call in create_or_update.call_args_list
        for item in call.kwargs["items"]
    )
    assert sent_inputs == list(range(10)), (
        "Every item must be sent exactly once regardless of interleaving"
    )


def test_insert__parallel_and_sequential_send_identical_items(monkeypatch):
    _small_batches(monkeypatch, size=2)
    items = _make_items(10)

    def sent_inputs(num_threads: int) -> list:
        mock_rest_client = Mock()
        dataset = Dataset(
            name="test_dataset",
            description="Test description",
            project_name="Test project",
            rest_client=mock_rest_client,
        )
        dataset.insert(items, num_threads=num_threads)
        create_or_update = mock_rest_client.datasets.create_or_update_dataset_items
        return sorted(
            item.data["input"]["i"]
            for call in create_or_update.call_args_list
            for item in call.kwargs["items"]
        )

    assert sent_inputs(num_threads=1) == sent_inputs(num_threads=4), (
        "Parallel and sequential inserts must send the same set of items"
    )


def test_insert__parallel__batch_failure_raises(monkeypatch):
    _small_batches(monkeypatch, size=2)
    mock_rest_client = Mock()

    # Any batch failing must surface to the caller. Fail unconditionally so the
    # assertion is deterministic regardless of worker scheduling.
    def failing_create_or_update(*args, **kwargs):
        raise ValueError("backend rejected batch")

    mock_rest_client.datasets.create_or_update_dataset_items.side_effect = (
        failing_create_or_update
    )

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    with pytest.raises(ValueError, match="backend rejected batch"):
        dataset.insert(_make_items(10), num_threads=4)


@pytest.mark.parametrize("bad_value", [0, -1, 1.5, "2", True])
def test_insert__invalid_num_threads__raises_before_upload(bad_value):
    mock_rest_client = Mock()
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    with pytest.raises(ValueError, match="num_threads must be a positive integer"):
        dataset.insert(_make_items(3), num_threads=bad_value)

    mock_rest_client.datasets.create_or_update_dataset_items.assert_not_called()
