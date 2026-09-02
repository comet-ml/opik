import threading
import time
from typing import Optional
from unittest.mock import Mock, patch

import pytest

from opik.api_objects import constants
from opik.api_objects.dataset import dataset_item
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


def test_insert__deduplication_disabled__duplicates_are_inserted():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    item_dict = {
        "input": {"key": "value"},
        "expected_output": {"key": "output"},
        "metadata": {"key": "meta"},
    }

    dataset.insert([item_dict, item_dict], deduplication=False)

    call_args = mock_rest_client.datasets.create_or_update_dataset_items.call_args
    assert len(call_args[1]["items"]) == 2, (
        "Both identical items must be sent when deduplication is disabled"
    )


def test_insert__deduplication_disabled__backend_items_are_not_downloaded():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )
    # The state `get_dataset`/`get_datasets` leave behind: the backend holds
    # items this object has not hashed yet.
    dataset.__internal_api__hashes_synced__ = False

    dataset.insert(_make_items(3), deduplication=False)

    mock_rest_client.datasets.stream_dataset_items.assert_not_called()


def test_insert__deduplication_disabled__next_deduplicated_insert_syncs_hashes():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    dataset.insert(_make_items(3), deduplication=False)
    assert not dataset.__internal_api__hashes_synced__, (
        "Skipping deduplication leaves the local hash cache stale"
    )

    with patch.object(dataset, "__internal_api__sync_hashes__") as sync_hashes:
        dataset.insert(_make_items(3))

    sync_hashes.assert_called_once()


def test_update__deduplication_disabled__unchanged_item_is_still_sent():
    mock_rest_client = Mock()

    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    item = {
        "input": {"key": "value"},
        "expected_output": {"key": "output"},
        "metadata": {"key": "meta"},
    }
    dataset.insert([item])

    inserted_id = mock_rest_client.datasets.create_or_update_dataset_items.call_args[1][
        "items"
    ][0].id

    dataset.update([{"id": inserted_id, **item}], deduplication=False)

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2
    updated_items = mock_rest_client.datasets.create_or_update_dataset_items.call_args[
        1
    ]["items"]
    assert len(updated_items) == 1, (
        "An update with unchanged content must still be sent when deduplication "
        "is disabled"
    )


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

# Only has to outlast thread-pool startup, so it is generous even on a loaded
# CI runner. It is never reached on a healthy run — it is the deadline by which
# a wrongly-sequential upload gives up and fails the test.
_OVERLAP_TIMEOUT_SECONDS = 10.0

# Sequential expectations cannot use the barrier (it would deadlock), so each
# upload holds this long instead. Long enough that wrongly-parallel uploads
# overlap and get caught; a correct sequential run pays it once per batch.
_SEQUENTIAL_HOLD_SECONDS = 0.05


def _batches_overlapped(
    monkeypatch,
    mock_rest_client: Mock,
    num_threads: Optional[int],
    expect_overlap: bool,
    item_count: int = _GATE_ITEM_COUNT,
) -> bool:
    """Insert through the public API and report whether batches ran concurrently.

    Uploads record how many of them are in flight at once, observed at the REST
    boundary rather than at the gate's internal decision — so this still fails
    if insert() stops honouring the worker count downstream.

    ``num_threads=None`` omits the argument so the call exercises the default.
    ``item_count`` sets how many batches there are, which must not exceed the
    worker count when overlap is expected — the barrier is sized to it.

    ``expect_overlap`` selects how uploads are held, because the two
    expectations fail in opposite directions and need opposite instruments.

    When overlap is expected, every upload waits on a barrier sized to the
    batch count, so the peak is deterministic no matter how the scheduler
    interleaves workers: no sleep to tune, and no way for a slow runner to let
    one upload finish before its sibling starts. A wrongly-sequential run can
    never fill that barrier, so it trips the timeout and fails rather than
    hanging.

    When overlap is *not* expected a barrier would deadlock, but returning
    immediately is no good either: uploads that wrongly run in parallel would
    finish too fast to catch, and the test would pass on a real regression.
    Each upload instead holds briefly, which is enough for concurrent workers
    to pile up and be counted, while a genuinely sequential run only pays that
    hold once per batch.
    """
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)
    batch_count = item_count // _GATE_BATCH_SIZE

    lock = threading.Lock()
    in_flight = 0
    peak_in_flight = 0
    barrier = (
        threading.Barrier(batch_count, timeout=_OVERLAP_TIMEOUT_SECONDS)
        if expect_overlap
        else None
    )

    def tracked_upload(*args, **kwargs):
        nonlocal in_flight, peak_in_flight
        with lock:
            in_flight += 1
            peak_in_flight = max(peak_in_flight, in_flight)
        if barrier is not None:
            # Hold every upload until all of them have arrived, so the observed
            # peak reflects real concurrency instead of a timing guess.
            barrier.wait()
        else:
            # No rendezvous available, so hold long enough that uploads which
            # wrongly overlap are still in flight together and get counted.
            time.sleep(_SEQUENTIAL_HOLD_SECONDS)
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
    items = _make_items(item_count)
    if num_threads is None:
        dataset.insert(items)
    else:
        dataset.insert(items, num_threads=num_threads)

    assert (
        mock_rest_client.datasets.create_or_update_dataset_items.call_count
        == batch_count
    ), "Every batch must be uploaded regardless of the thread count"

    return peak_in_flight > 1


@pytest.mark.parametrize("backend_version", ["2.2.8", "2.2.9", "2.3.0", "3.0.0"])
def test_insert__backend_supports_parallel__batches_uploaded_concurrently(
    monkeypatch, backend_version
):
    assert _batches_overlapped(
        monkeypatch,
        _mock_rest_client(backend_version),
        num_threads=_GATE_BATCH_COUNT,
        expect_overlap=True,
    ), f"Backend {backend_version} supports parallel insert, uploads must overlap"


@pytest.mark.parametrize("backend_version", ["2.2.7", "2.1.0", "1.9.9"])
def test_insert__backend_older_than_minimum__uploads_sequentially(
    monkeypatch, backend_version
):
    assert not _batches_overlapped(
        monkeypatch,
        _mock_rest_client(backend_version),
        num_threads=_GATE_BATCH_COUNT,
        expect_overlap=False,
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
        expect_overlap=True,
    )


def test_insert__unparseable_backend_version__uploads_sequentially(monkeypatch):
    assert not _batches_overlapped(
        monkeypatch,
        _mock_rest_client("dev-local"),
        num_threads=_GATE_BATCH_COUNT,
        expect_overlap=False,
    ), "An undeterminable backend version must fall back to a sequential upload"


def test_insert__version_endpoint_unreachable__uploads_sequentially(monkeypatch):
    mock_rest_client = Mock()
    mock_rest_client.version.side_effect = ConnectionError("backend unreachable")

    assert not _batches_overlapped(
        monkeypatch,
        mock_rest_client,
        num_threads=_GATE_BATCH_COUNT,
        expect_overlap=False,
    ), "A failing version probe must not break insert; it falls back to sequential"


def test_insert__version_probe_recovers__parallel_upload_resumes(monkeypatch):
    """A transient probe failure must not pin the dataset to sequential uploads."""
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)
    mock_rest_client = Mock()
    mock_rest_client.version.side_effect = [
        ConnectionError("backend unreachable"),
        {"version": constants.MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT},
    ]
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    # Spying on the upload layer keeps the assertion on the worker count that
    # actually reached it, so a gate that stops honouring the probe still fails
    # this test.
    workers_per_insert = []
    original_send = Dataset._send_batches

    def spy_send_batches(self, batches, batch_group_id, num_threads):
        workers_per_insert.append(num_threads)
        return original_send(self, batches, batch_group_id, num_threads)

    monkeypatch.setattr(Dataset, "_send_batches", spy_send_batches)

    dataset.insert(_make_items(4), deduplication=False)
    dataset.insert(_make_items(4), deduplication=False)

    assert mock_rest_client.version.call_count == 2, (
        "The failed probe must be retried rather than cached as unsupported"
    )
    assert workers_per_insert[0] == 1, (
        "While the backend is unreachable the upload must stay sequential"
    )
    assert workers_per_insert[1] > 1, (
        "Once the backend answers, the upload must go back to using workers"
    )


def test_insert__unparseable_version__probed_once(monkeypatch):
    """An unparseable version is a conclusive answer, so it must still cache."""
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)
    mock_rest_client = _mock_rest_client("dev-local")
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    for _ in range(3):
        dataset.insert(_make_items(4), deduplication=False)

    assert mock_rest_client.version.call_count == 1, (
        "A version the SDK cannot parse will not change, so it must not be re-probed"
    )


def test_internal_insert__old_backend__worker_count_still_gated(monkeypatch):
    """The gate lives in the funnel, so a direct caller cannot skip it."""
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)
    mock_rest_client = _mock_rest_client("2.2.7")
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    used_workers = []
    monkeypatch.setattr(
        Dataset,
        "_send_batches",
        lambda self, batches, batch_group_id, num_threads: used_workers.append(
            num_threads
        ),
    )

    dataset.__internal_api__insert_items_as_dataclasses__(
        [dataset_item.DatasetItem(**item) for item in _make_items(4)],
        num_threads=4,
    )

    assert used_workers == [1], (
        "A backend that predates parallel insert must force a sequential upload "
        "even when the internal API is called directly"
    )


@pytest.mark.parametrize("bad_value", ["false", 0, 1, None, "", []])
def test_insert__non_bool_deduplication__raises_before_any_request(bad_value):
    mock_rest_client = Mock()
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    with pytest.raises(ValueError, match="deduplication must be a bool"):
        dataset.insert(_make_items(3), deduplication=bad_value)

    mock_rest_client.datasets.create_or_update_dataset_items.assert_not_called()
    mock_rest_client.version.assert_not_called()


def test_insert__sequential__uploads_sequentially_without_probing_version(monkeypatch):
    mock_rest_client = _mock_rest_client()

    assert not _batches_overlapped(
        monkeypatch, mock_rest_client, num_threads=1, expect_overlap=False
    ), "num_threads=1 must upload sequentially"

    # An explicitly sequential upload cannot race, so it must not pay the probe.
    mock_rest_client.version.assert_not_called()


# 4 batches, which is the default worker count — so all of them fit in flight
# together and the barrier can prove the default really uses the pool.
_DEFAULT_THREADS_ITEM_COUNT = _GATE_BATCH_SIZE * 4


def test_insert__num_threads_not_given__uploads_concurrently_by_default(monkeypatch):
    assert _batches_overlapped(
        monkeypatch,
        _mock_rest_client(),
        num_threads=None,
        expect_overlap=True,
        item_count=_DEFAULT_THREADS_ITEM_COUNT,
    ), "insert() must upload in parallel without being asked to"


def test_insert__repeated_inserts__backend_version_probed_once(monkeypatch):
    _small_batches(monkeypatch, size=_GATE_BATCH_SIZE)
    mock_rest_client = _mock_rest_client()
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    for _ in range(3):
        dataset.insert(_make_items(4), deduplication=False)

    assert mock_rest_client.version.call_count == 1, (
        "Parallel upload is the default, so the version gate must be probed once "
        "per dataset rather than once per insert"
    )
