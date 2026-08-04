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


def _mock_rest_client(backend_version: str = "2.2.8") -> Mock:
    """A rest client whose reported backend version supports parallel insert.

    A bare Mock() would return a Mock from version(), which the parallel gate
    treats as undeterminable and downgrades to a sequential upload — so tests
    that mean to exercise the thread pool must pin a supported version.
    """
    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": backend_version}
    return mock_rest_client


def test_insert__parallel__all_batches_sent_under_one_batch_group_id(monkeypatch):
    _small_batches(monkeypatch, size=2)
    mock_rest_client = _mock_rest_client()
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
        mock_rest_client = _mock_rest_client()
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
    mock_rest_client = _mock_rest_client()

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


def _insert_and_capture_num_threads(
    monkeypatch, mock_rest_client: Mock, requested_num_threads: int
) -> int:
    """Run an insert and report the num_threads that reached the send stage."""
    _small_batches(monkeypatch, size=2)
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    captured = {}
    original_send_batches = dataset._send_batches

    def spy(batches, batch_group_id, num_threads):
        captured["num_threads"] = num_threads
        return original_send_batches(batches, batch_group_id, num_threads)

    monkeypatch.setattr(dataset, "_send_batches", spy)
    dataset.insert(_make_items(10), num_threads=requested_num_threads)
    return captured["num_threads"]


@pytest.mark.parametrize("backend_version", ["2.2.8", "2.2.9", "2.3.0", "3.0.0"])
def test_insert__backend_supports_parallel__requested_threads_used(
    monkeypatch, backend_version
):
    used = _insert_and_capture_num_threads(
        monkeypatch, _mock_rest_client(backend_version), requested_num_threads=4
    )

    assert used == 4, (
        f"Backend {backend_version} supports parallel insert, threads must not be capped"
    )


@pytest.mark.parametrize("backend_version", ["2.2.7", "2.1.0", "1.9.9"])
def test_insert__backend_older_than_minimum__downgrades_to_sequential(
    monkeypatch, backend_version
):
    used = _insert_and_capture_num_threads(
        monkeypatch, _mock_rest_client(backend_version), requested_num_threads=4
    )

    assert used == 1, (
        f"Backend {backend_version} predates parallel insert support, must fall back "
        "to a sequential upload"
    )


def test_insert__self_hosted_build_version__parsed_and_supported(monkeypatch):
    # Self-hosted / PR-environment builds report a suffixed version; only
    # major.minor.patch is compared, so this must still count as supported.
    used = _insert_and_capture_num_threads(
        monkeypatch,
        _mock_rest_client("2.2.12-7671-merge-2777"),
        requested_num_threads=4,
    )

    assert used == 4


def test_insert__unparseable_backend_version__downgrades_to_sequential(monkeypatch):
    used = _insert_and_capture_num_threads(
        monkeypatch, _mock_rest_client("dev-local"), requested_num_threads=4
    )

    assert used == 1, (
        "An undeterminable backend version must fall back to a sequential upload"
    )


def test_insert__version_endpoint_unreachable__downgrades_to_sequential(monkeypatch):
    mock_rest_client = Mock()
    mock_rest_client.version.side_effect = ConnectionError("backend unreachable")

    used = _insert_and_capture_num_threads(
        monkeypatch, mock_rest_client, requested_num_threads=4
    )

    assert used == 1, (
        "A failing version probe must not break insert; it falls back to sequential"
    )


def test_insert__sequential__version_endpoint_not_probed(monkeypatch):
    # The default path must not pay an extra request.
    mock_rest_client = _mock_rest_client()

    _insert_and_capture_num_threads(
        monkeypatch, mock_rest_client, requested_num_threads=1
    )

    mock_rest_client.version.assert_not_called()
