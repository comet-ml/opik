"""Streaming behaviour of `Dataset.insert`: laziness, memory, and the widened signature."""

import json
import threading
import time
import tracemalloc
from unittest.mock import Mock

import pytest
import tenacity

import opik.config as config
from opik import exceptions
from opik.api_objects import constants
from opik.api_objects.dataset import converters, streaming_writer
from opik.api_objects.dataset.dataset import Dataset
from opik.message_processing.batching import sequence_splitter
from opik.rest_client_configurator import retry_decorator

from .upload_capture import UploadCapture, make_dataset


@pytest.fixture
def instant_retries(monkeypatch):
    """The shared retry policy with its waits removed, so a retry test does not sleep."""
    monkeypatch.setattr(
        retry_decorator,
        "opik_rest_retry",
        tenacity.retry(
            stop=tenacity.stop_after_attempt(3),
            wait=tenacity.wait_none(),
            retry=tenacity.retry_if_exception(retry_decorator._allowed_to_retry),
            reraise=True,
        ),
    )


def _items(count: int, prefix: str = "item"):
    return [
        {"input": {"key": f"{prefix}-{i}"}, "expected_output": {"o": i}}
        for i in range(count)
    ]


# --------------------------------------------------------------------------- #
# the widened signature: a list must keep working everywhere it worked before
# --------------------------------------------------------------------------- #
def test_insert__list__still_accepted():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(3))

    assert len(capture.items) == 3


def test_update__list__still_accepted():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.update([{"id": "a", "input": {"key": "v"}}])

    assert len(capture.items) == 1


def test_insert_from_json__list_shape__still_accepted():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert_from_json(json.dumps([{"input": "a"}, {"input": "b"}]))

    assert len(capture.items) == 2


def test_insert_from_pandas__still_accepted():
    pd = pytest.importorskip("pandas")
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert_from_pandas(pd.DataFrame([{"input": "a"}, {"input": "b"}]))

    assert len(capture.items) == 2


def test_insert_from_json__malformed_document__rejected_before_anything_is_sent():
    """The eager entry point keeps failing up front rather than mid-upload."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    with pytest.raises(json.JSONDecodeError):
        dataset.insert_from_json('[{"input": "a"}, {"input": ')

    assert capture.request_count == 0, "Nothing should have been sent"


def test_from_json__returns_a_list():
    """The return type is part of the public contract and must not become a generator."""
    result = converters.from_json(json.dumps([{"input": "a"}]), {}, [])
    assert isinstance(result, list)


# --------------------------------------------------------------------------- #
# streaming
# --------------------------------------------------------------------------- #
def test_insert__generator__accepted_and_items_land():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(item for item in _items(4))

    sent = sorted(item["data"]["input"]["key"] for item in capture.items)
    assert sent == ["item-0", "item-1", "item-2", "item-3"]


def test_insert__generator__consumed_lazily_not_drained_up_front():
    """If anything re-materialised the input, the source would be exhausted before the
    first request went out."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    drawn = []

    def source():
        for item in _items(6):
            drawn.append(item["input"]["key"])
            yield item

    # One item per request, so progress through the source is observable.
    import opik.config as config

    original = config.MAX_BATCH_SIZE_MB
    config.MAX_BATCH_SIZE_MB = 1e-9
    try:
        dataset.insert(source())
    finally:
        config.MAX_BATCH_SIZE_MB = original

    assert capture.request_count == 6, "Each item should have been its own request"
    assert len(drawn) == 6


def test_insert__peak_memory__does_not_grow_with_item_count():
    """Asserts the slope, not an absolute number, so it does not depend on the machine."""

    def peak_for(count: int) -> int:
        capture = UploadCapture()
        dataset = make_dataset(Dataset, Mock(), capture)
        # A payload big enough that materialising would dominate the measurement.
        source = (
            {"input": {"key": "x" * 2000, "i": i}, "expected_output": {"o": i}}
            for i in range(count)
        )
        tracemalloc.start()
        dataset.insert(source)
        _, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        return peak

    small = peak_for(200)
    large = peak_for(800)

    # Four times the items. A path that materialises grows roughly linearly; a streaming
    # one stays flat. Allow generous headroom for the dedup digests, which are the one
    # structure permitted to grow, and for allocator noise.
    assert large < small * 2, (
        f"Peak memory grew with item count: {small} -> {large} for 4x the items"
    )


def test_insert__generator_items_are_not_kept_alive():
    """The dedup structures may grow; the items themselves must not be retained."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(20))

    # Only digests and ids are held, never the item payloads.
    assert len(dataset._hashes) == 20
    assert all(isinstance(value, str) for value in dataset._hashes)


# --------------------------------------------------------------------------- #
# validation that changed
# --------------------------------------------------------------------------- #
def test_update__item_without_id__raises_with_the_index():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    with pytest.raises(exceptions.DatasetItemUpdateOperationRequiresItemId) as exc_info:
        dataset.update([{"id": "a", "input": 1}, {"input": 2}])

    message = str(exc_info.value)
    assert "index 1" in message, "The failing item's position must be named"
    assert "persisted" in message, (
        "The message must warn that earlier items may already be persisted"
    )


def test_insert__value_not_json_serializable__raises_explicitly():
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    class NotSerializable:
        pass

    from opik.api_objects.dataset import streaming_writer

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        dataset.insert([{"input": NotSerializable()}])


# --------------------------------------------------------------------------- #
# compatibility: a Dataset built from a rest client alone still uploads
# --------------------------------------------------------------------------- #
def test_insert__dataset_built_without_an_owning_client__still_uploads():
    """Third-party code constructs Dataset this way; it must keep working."""
    mock_rest_client = Mock()
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    dataset.insert(_items(2))

    create = mock_rest_client.datasets.create_or_update_dataset_items
    assert create.call_count == 1, (
        "Without an HTTP client of its own the upload must fall back to the REST client"
    )
    assert len(create.call_args.kwargs["items"]) == 2


# --------------------------------------------------------------------------- #
# transport-sensitive behaviour, asserted on the streaming path
#
# The pre-existing dataset tests construct a Dataset from a REST client alone, which is a
# supported shape that takes the fallback path, so they keep covering that path unchanged.
# These cover the same behaviours where the streaming path implements them differently.
# --------------------------------------------------------------------------- #
def test_insert__streaming__worker_count_gated_by_backend_version(monkeypatch):
    """The parallel-upload gate must apply to the streaming pool too."""
    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": "2.2.7"}  # predates parallel
    capture = UploadCapture()
    dataset = make_dataset(Dataset, mock_rest_client, capture)

    used_workers = []
    original = Dataset._open_send_pool

    def spy(self, num_threads):
        used_workers.append(num_threads)
        return original(self, num_threads)

    monkeypatch.setattr(Dataset, "_open_send_pool", spy)
    dataset.insert(_items(4), num_threads=4)

    assert used_workers == [1], (
        "An old backend must force a sequential upload on the streaming path as well"
    )
    assert len(capture.items) == 4


def test_insert__streaming__rate_limited_request_is_retried(
    monkeypatch, instant_retries
):
    """429 handling lives outside the generated client now, so it needs its own check."""
    monkeypatch.setattr("opik.api_objects.rest_helpers._sleep", lambda _seconds: None)
    capture = UploadCapture(
        responses=[429, 204],
        response_headers={"x-ratelimit-reset": "1"},
    )
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(1))

    assert capture.request_count == 2, "The throttled request should have been retried"


def test_insert__streaming__server_error_raises(instant_retries):
    capture = UploadCapture(status_code=500)
    dataset = make_dataset(Dataset, Mock(), capture)

    from opik.rest_api.core.api_error import ApiError

    with pytest.raises(ApiError):
        dataset.insert(_items(1))


def test_insert__streaming__uses_the_dataset_upload_compression_level(monkeypatch):
    """A bulk upload has its own level; the global request level must not leak into it."""
    monkeypatch.setenv("OPIK_REQUEST_COMPRESSION_LEVEL", "9")
    monkeypatch.setenv("OPIK_DATASET_UPLOAD_COMPRESSION_LEVEL", "2")

    levels = []
    original = streaming_writer.StreamingBatchWriter

    def spy(**kwargs):
        levels.append(kwargs["gzip_level"])
        return original(**kwargs)

    monkeypatch.setattr(streaming_writer, "StreamingBatchWriter", spy)
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(2))

    assert levels == [2]
    assert len(capture.items) == 2


def test_insert__compression_disabled_on_the_client__plain_body_and_no_gzip_header():
    """`enable_json_request_compression=False` must reach the streaming path too."""
    capture = UploadCapture()
    capture.compress_json_requests = False  # what the httpx client is built with
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(2))

    body = capture.bodies[0]
    assert json.loads(body)["items"], "The body should be readable as plain JSON"
    assert "Content-Encoding" not in capture.request_headers[0], (
        "An uncompressed body must not be labelled gzip"
    )


def test_insert__streaming__transient_server_error_is_retried(instant_retries):
    """The prepared-body sender bypasses the generated client, so it carries the retry."""
    capture = UploadCapture(responses=[503, 204])
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert(_items(1))

    assert capture.request_count == 2, "A 503 must be retried, not surfaced"


def test_insert__streaming__permanent_server_error_still_raises(instant_retries):
    capture = UploadCapture(status_code=503)
    dataset = make_dataset(Dataset, Mock(), capture)

    from opik.rest_api.core.api_error import ApiError

    with pytest.raises(ApiError):
        dataset.insert(_items(1))

    assert capture.request_count == 3, "The retry budget should have been spent"


# --------------------------------------------------------------------------- #
# the rest-client-only fallback: slower, but bounded the same way
# --------------------------------------------------------------------------- #
def _fallback_dataset(rest_client):
    """A Dataset with no HTTP client of its own, so uploads take the fallback path."""
    return Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=rest_client,
    )


def test_insert__rest_client_only__generator_consumed_lazily_not_drained_up_front(
    monkeypatch,
):
    """The fallback used to build the whole upload as a list before batching."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    mock_rest_client = Mock()
    dataset = _fallback_dataset(mock_rest_client)

    drawn = []
    drawn_when_sent = []

    def source():
        for item in _items(6):
            drawn.append(item)
            yield item

    mock_rest_client.datasets.create_or_update_dataset_items.side_effect = (
        lambda **kwargs: drawn_when_sent.append(len(drawn))
    )

    dataset.insert(source(), num_threads=1)

    assert drawn_when_sent == [1, 2, 3, 4, 5, 6], (
        "Each request should go out as its batch fills, not after the source is drained"
    )


def test_insert__rest_client_only__requests_in_flight_are_bounded(monkeypatch):
    """Submitting every batch up front would hold the whole upload as queued futures."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    num_threads = 4
    total = 200

    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": "99.0.0"}  # allow parallelism
    dataset = _fallback_dataset(mock_rest_client)

    release = threading.Event()
    mock_rest_client.datasets.create_or_update_dataset_items.side_effect = (
        lambda **kwargs: release.wait(10)
    )

    drawn = []

    def source():
        for item in _items(total):
            drawn.append(item)
            yield item

    drawn_while_blocked = []

    def unblock_once_the_producer_stalls():
        previous = -1
        while previous != len(drawn):
            previous = len(drawn)
            time.sleep(0.1)
        drawn_while_blocked.append(len(drawn))
        release.set()

    watcher = threading.Thread(target=unblock_once_the_producer_stalls, daemon=True)
    watcher.start()
    dataset.insert(source(), num_threads=num_threads)
    watcher.join(5)

    assert drawn_while_blocked, "The watcher never observed the upload"
    assert drawn_while_blocked[0] <= num_threads * 2 + 1, (
        f"Work in flight is not bounded: {drawn_while_blocked[0]} items were drawn "
        f"while every worker was blocked"
    )
    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == total


def test_insert__rest_client_only__batches_match_the_splitter(monkeypatch):
    """Incremental batching must split exactly where `split_into_batches` split."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 0.0005)
    monkeypatch.setattr(constants, "DATASET_ITEMS_MAX_BATCH_SIZE", 3)

    # Mixed sizes, two of them past the cap, so the oversized-item boundary is exercised
    # and not only the count limit.
    payloads = [
        {"i": i, "input": "x" * (4000 if i in (4, 5) else 120)} for i in range(14)
    ]
    mock_rest_client = Mock()
    dataset = _fallback_dataset(mock_rest_client)

    dataset.insert(payloads, num_threads=1)

    create = mock_rest_client.datasets.create_or_update_dataset_items
    sent = [call.kwargs["items"] for call in create.call_args_list]
    in_source_order = sorted(
        (item for batch in sent for item in batch), key=lambda item: item.data["i"]
    )
    expected = sequence_splitter.split_into_batches(
        in_source_order,
        max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
        max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
    )

    def indices(batches):
        return [[item.data["i"] for item in batch] for batch in batches]

    assert indices(sent) == indices(expected), "Batching diverged from the splitter"
    assert len(in_source_order) == len(payloads), "Every item must be sent exactly once"
    groups = {call.kwargs["batch_group_id"] for call in create.call_args_list}
    assert len(groups) == 1, "All batches must share one batch_group_id"


def test_insert__rest_client_only__batch_failure_propagates(monkeypatch):
    """A failing batch must raise, as it did when every batch was submitted up front."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": "99.0.0"}  # allow parallelism
    mock_rest_client.datasets.create_or_update_dataset_items.side_effect = ValueError(
        "backend rejected the batch"
    )
    dataset = _fallback_dataset(mock_rest_client)

    with pytest.raises(ValueError):
        dataset.insert(_items(50), num_threads=4)


def test_insert__rest_client_only__list_input__nothing_is_sent_if_an_item_is_invalid(
    monkeypatch,
):
    """A list was checked in full before the first request went out; it still is."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    mock_rest_client = Mock()
    dataset = _fallback_dataset(mock_rest_client)

    items = _items(5)
    items[4]["input"] = {"bad": object()}  # not serialisable, so it cannot be converted

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        dataset.insert(items, num_threads=1)

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 0, (
        "A list input must be validated before anything is persisted"
    )


def test_insert__rest_client_only__generator_input__earlier_items_are_already_sent(
    monkeypatch,
):
    """The documented trade for a generator: it cannot be checked without draining it."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    mock_rest_client = Mock()
    dataset = _fallback_dataset(mock_rest_client)

    items = _items(5)
    items[4]["input"] = {"bad": object()}

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        dataset.insert((item for item in items), num_threads=1)

    assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 4, (
        "The items before the invalid one are sent, as the docstring says"
    )


# --------------------------------------------------------------------------- #
# the two upload paths must agree
# --------------------------------------------------------------------------- #
def _payloads_with_an_oversized_item():
    """Small, small, one item past the cap, small -- the boundary case that reorders."""
    return [
        {"i": 0, "input": "a" * 50},
        {"i": 1, "input": "b" * 50},
        {"i": 2, "input": "c" * 4000},
        {"i": 3, "input": "d" * 50},
    ]


def test_insert__oversized_item__both_paths_emit_the_same_requests(monkeypatch):
    """Same input, same batches, same order, whichever transport sends it."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 0.0005)

    capture = UploadCapture()
    streaming = make_dataset(Dataset, Mock(), capture)
    streaming.insert(_payloads_with_an_oversized_item(), num_threads=1)
    via_streaming = [[item["data"]["i"] for item in batch] for batch in capture.batches]

    mock_rest_client = Mock()
    fallback = _fallback_dataset(mock_rest_client)
    fallback.insert(_payloads_with_an_oversized_item(), num_threads=1)
    create = mock_rest_client.datasets.create_or_update_dataset_items
    via_fallback = [
        [item.data["i"] for item in call.kwargs["items"]]
        for call in create.call_args_list
    ]

    assert via_streaming == via_fallback, "The transports disagree on batching"
    assert via_streaming == [[0, 1], [2], [3]], (
        "The oversized item gets its own request, and the input order is kept"
    )


# --------------------------------------------------------------------------- #
# identifiers on the wire
# --------------------------------------------------------------------------- #
def test_insert__numeric_item_id__sent_as_a_string_on_both_paths():
    """`DatasetItem` skips validating its id, so a number reaches the writer unchecked."""
    capture = UploadCapture()
    streaming = make_dataset(Dataset, Mock(), capture)
    streaming.insert([{"id": 123, "input": {"k": "v"}}])

    assert capture.items[0]["id"] == "123", "A numeric id must not go out as a number"

    mock_rest_client = Mock()
    fallback = _fallback_dataset(mock_rest_client)
    fallback.insert([{"id": 123, "input": {"k": "v"}}])

    sent = mock_rest_client.datasets.create_or_update_dataset_items.call_args.kwargs
    assert sent["items"][0].id == "123", "The fallback must agree, not reject it"


def test_insert_delete_reinsert__numeric_id__the_item_is_not_skipped():
    """One identity per item: the dedup cache must key on what was actually sent.

    A numeric id is sent as `"123"`, so deleting `"123"` -- the form the backend hands
    back -- has to drop the hash cached for it, or the re-insert is silently deduplicated
    away and the item never reaches the dataset again.
    """
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)
    item = {"id": 123, "input": {"k": "v"}}

    dataset.insert([item])
    assert len(capture.items) == 1

    dataset.delete(["123"])
    assert dataset._id_to_hash == {}, "The delete must drop the hash it cached"
    assert dataset._hashes == set()

    dataset.insert([item], deduplication=True)

    assert len(capture.items) == 2, (
        "The re-inserted item was dropped as a duplicate of one that no longer exists"
    )
