import threading
import time
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


def _mock_rest_client(
    backend_version: str = constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT,
) -> Mock:
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


_GATE_BATCH_SIZE = 2
_GATE_ITEM_COUNT = 10
_GATE_BATCH_COUNT = _GATE_ITEM_COUNT // _GATE_BATCH_SIZE


def _batches_overlapped(monkeypatch, mock_rest_client: Mock, num_threads: int) -> bool:
    """Insert through the public API and report whether batches ran concurrently.

    Each upload records how many uploads are in flight alongside it and holds
    briefly so overlap is observable; seeing more than one at once proves the
    thread pool ran. This observes the REST boundary rather than the gate's
    internal decision, so it still fails if insert() stops honouring the worker
    count downstream. A sequential upload never exceeds one in flight and
    needs no timeout to prove it.
    """
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)

    lock = threading.Lock()
    in_flight = 0
    peak_in_flight = 0

    def tracked_upload(*args, **kwargs):
        nonlocal in_flight, peak_in_flight
        with lock:
            in_flight += 1
            peak_in_flight = max(peak_in_flight, in_flight)
        # Hold long enough for sibling workers to enter, without a hard
        # synchronisation point that a sequential run would have to wait out.
        time.sleep(0.05)
        with lock:
            in_flight -= 1

    mock_rest_client.datasets.create_or_update_dataset_items.side_effect = (
        tracked_upload
    )

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )
    dataset.insert(_make_items(_GATE_ITEM_COUNT), num_threads=num_threads)

    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count
        == _GATE_BATCH_COUNT
    ), "Every batch must be uploaded regardless of the thread count"

    return peak_in_flight > 1


@pytest.mark.parametrize("backend_version", ["2.2.8", "2.2.9", "2.3.0", "3.0.0"])
def test_insert__backend_supports_parallel__batches_uploaded_concurrently(
    monkeypatch, backend_version
):
    assert _batches_overlapped(
        monkeypatch, _mock_rest_client(backend_version), num_threads=_GATE_BATCH_COUNT
    ), f"Backend {backend_version} supports parallel insert, uploads must overlap"


@pytest.mark.parametrize("backend_version", ["2.2.7", "2.1.0", "1.9.9"])
def test_insert__backend_older_than_minimum__uploads_sequentially(
    monkeypatch, backend_version
):
    assert not _batches_overlapped(
        monkeypatch, _mock_rest_client(backend_version), num_threads=_GATE_BATCH_COUNT
    ), (
        f"Backend {backend_version} predates parallel insert support, uploads must "
        "not overlap"
    )


def test_insert__self_hosted_build_version__uploads_concurrently(monkeypatch):
    # Self-hosted / PR-environment builds report a suffixed version; only
    # major.minor.patch is compared, so this must still count as supported.
    assert _batches_overlapped(
        monkeypatch,
        _mock_rest_client("2.2.12-7671-merge-2777"),
        num_threads=_GATE_BATCH_COUNT,
    )


def test_insert__unparseable_backend_version__uploads_sequentially(monkeypatch):
    assert not _batches_overlapped(
        monkeypatch, _mock_rest_client("dev-local"), num_threads=_GATE_BATCH_COUNT
    ), "An undeterminable backend version must fall back to a sequential upload"


def test_insert__version_endpoint_unreachable__uploads_sequentially(monkeypatch):
    mock_rest_client = Mock()
    mock_rest_client.version.side_effect = ConnectionError("backend unreachable")

    assert not _batches_overlapped(
        monkeypatch, mock_rest_client, num_threads=_GATE_BATCH_COUNT
    ), "A failing version probe must not break insert; it falls back to sequential"


def test_insert__sequential__version_endpoint_not_probed(monkeypatch):
    # The default path must not pay an extra request.
    mock_rest_client = _mock_rest_client()

    _batches_overlapped(monkeypatch, mock_rest_client, num_threads=1)

    mock_rest_client.version.assert_not_called()
