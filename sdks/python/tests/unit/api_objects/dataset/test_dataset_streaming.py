"""Streaming behaviour of `Dataset.insert`: laziness, memory, and the widened signature."""

import base64
import datetime
import json
import tracemalloc
import uuid
from unittest.mock import Mock

import pytest
import tenacity

import opik.config as config
from opik import exceptions
from opik.api_objects.dataset import converters, streaming_writer
from opik.api_objects.dataset.dataset import Dataset
from opik.rest_api.core.jsonable_encoder import jsonable_encoder
from opik.rest_client_configurator import retry_decorator

from .upload_capture import UploadCapture, make_dataset, rest_client_with_transport


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

    dataset.update(
        [{"id": "0192f1a0-0000-7000-8000-00000000000a", "input": {"key": "v"}}]
    )

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
        dataset.update(
            [{"id": "0192f1a0-0000-7000-8000-00000000000a", "input": 1}, {"input": 2}]
        )

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
    """Third-party code constructs Dataset this way; it must keep working.

    There is no second upload path any more: the transport comes from the REST client's
    own wrapper, which is the same `OpikHttpxClient` an owning client would have handed
    over, so this construction streams like any other.
    """
    capture = UploadCapture()
    dataset = _rest_client_only_dataset(capture)

    dataset.insert(_items(2))

    assert capture.request_count == 1
    assert len(capture.items) == 2


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


# --------------------------------------------------------------------------- #
# a Dataset built from a REST client alone: the same path, resolved differently
# --------------------------------------------------------------------------- #
def _rest_client_only_dataset(capture, **rest_client_attrs):
    """A Dataset built from nothing but a REST client, as third-party code builds one.

    It resolves its transport from the REST client's own wrapper, so it uploads through
    the same path as every other Dataset.
    """
    return Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=rest_client_with_transport(capture, **rest_client_attrs),
    )


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
# a Dataset built from a REST client alone: the same path, resolved differently
# --------------------------------------------------------------------------- #
def _rest_client_only_dataset(capture, **rest_client_attrs):
    """A Dataset built from nothing but a REST client, as third-party code builds one.

    It resolves its transport from the REST client's own wrapper, so it uploads through
    the same path as every other Dataset.
    """
    return Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=rest_client_with_transport(capture, **rest_client_attrs),
    )


def test_insert__parallel_upload__a_failing_request_raises_to_the_caller(
    monkeypatch, instant_retries
):
    """A failure on a worker thread must reach the caller, not be lost in the pool."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    capture = UploadCapture(status_code=500)
    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": "99.0.0"}  # allow parallelism
    dataset = make_dataset(Dataset, mock_rest_client, capture)

    from opik.rest_api.core.api_error import ApiError

    with pytest.raises(ApiError):
        dataset.insert(_items(50), num_threads=4)


@pytest.mark.parametrize("materialised", [True, False], ids=["list", "generator"])
def test_insert__invalid_item__items_sent_before_it_stay_persisted(
    monkeypatch, materialised
):
    """What a single-pass upload can promise on failure, for both input shapes.

    The upload used to have a second path that built every item before sending, so from a
    list an invalid item raised with nothing persisted. That path is gone: an item that
    cannot be serialised is now found when it is reached, whatever the input was, and the
    requests already sent stay sent. `insert`'s docstring says exactly this.
    """
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    items = _items(5)
    items[4]["input"] = {"bad": object()}  # not serialisable, so it cannot be sent
    source = items if materialised else (item for item in items)

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        dataset.insert(source, num_threads=1)

    assert capture.request_count == 4, (
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


def test_insert__oversized_item__gets_its_own_request_in_input_order(monkeypatch):
    """An item past the cap is sent alone, and the input's order survives batching."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 0.0005)

    capture = UploadCapture()
    streaming = make_dataset(Dataset, Mock(), capture)
    streaming.insert(_payloads_with_an_oversized_item(), num_threads=1)
    via_streaming = [[item["data"]["i"] for item in batch] for batch in capture.batches]

    assert via_streaming == [[0, 1], [2], [3]], (
        "The oversized item gets its own request, and the input order is kept"
    )


# --------------------------------------------------------------------------- #
# identifiers on the wire
# --------------------------------------------------------------------------- #
def test_insert__numeric_item_id__refused_before_the_request():
    """`id` is a UUID column server-side, so a number is not an identifier it can store.

    `DatasetItem` skips validating it and a prepared body never passes through the
    generated model, so without this check the backend refuses the value instead -- with
    an error naming neither the item nor the field.
    """
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    with pytest.raises(ValueError) as exc_info:
        dataset.insert([{"input": {"k": "v"}}, {"id": 123, "input": {"k": "v"}}])

    message = str(exc_info.value)
    assert "index 1" in message, "The failing item's position must be named"
    assert "id" in message and "123" in message
    assert capture.request_count == 0, "Nothing should have been sent"


def test_insert__uuid_object_as_id__sent_in_its_string_form():
    """A `uuid.UUID` is an identifier the backend can store; it just is not a string yet."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)
    item_id = uuid.UUID("0192f1a0-0000-7000-8000-00000000000b")

    dataset.insert([{"id": item_id, "input": {"k": "v"}}])

    assert capture.items[0]["id"] == str(item_id)


def test_insert_delete_reinsert__uuid_object_id__the_item_is_not_skipped():
    """One identity per item: the dedup cache must key on what was actually sent.

    A `uuid.UUID` id is sent in its string form, so deleting by that string -- the form
    the backend hands back -- has to drop the hash cached for it, or the re-insert is
    silently deduplicated away and the item never reaches the dataset again.
    """
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)
    item = {
        "id": uuid.UUID("0192f1a0-0000-7000-8000-00000000000b"),
        "input": {"k": "v"},
    }

    dataset.insert([item])
    assert len(capture.items) == 1

    dataset.delete(["0192f1a0-0000-7000-8000-00000000000b"])
    dataset.insert([item], deduplication=True)

    assert len(capture.items) == 2, (
        "The re-inserted item was dropped as a duplicate of one that no longer exists"
    )


@pytest.mark.parametrize("bad_id", [None, ""], ids=["none", "empty-string"])
def test_delete__id_that_identifies_nothing__raises_before_anything_is_sent(bad_id):
    """Both reach `delete_dataset_items` as a request to delete nothing in particular."""
    capture = UploadCapture()
    dataset = _rest_client_only_dataset(capture)
    mock_rest_client = dataset._rest_client

    with pytest.raises(ValueError) as exc_info:
        dataset.delete(["real-id", bad_id])

    assert "index 1" in str(exc_info.value), "The failing id's position must be named"
    assert mock_rest_client.datasets.delete_dataset_items.call_count == 0, (
        "The valid id before it must not have been deleted"
    )


@pytest.mark.parametrize("deduplication", [True, False])
def test_insert__flexible_value__is_encoded_the_way_the_generated_client_encoded_it(
    deduplication,
):
    """Hashing runs before the writer does, so dedup must accept what the upload accepts."""
    item = {
        "input": {"when": datetime.datetime(2024, 1, 2, tzinfo=datetime.timezone.utc)},
        "expected_output": {"raw": b"bytes"},
    }

    capture = UploadCapture()
    streaming = make_dataset(Dataset, Mock(), capture)
    streaming.insert([item], deduplication=deduplication)
    sent_streaming = capture.items[0]["data"]

    # The generated client would have encoded the same value with `jsonable_encoder` on
    # the way out; the writer has to agree with it.
    expected = jsonable_encoder(
        {"when": datetime.datetime(2024, 1, 2, tzinfo=datetime.timezone.utc)}
    )
    assert sent_streaming["input"] == expected

    assert sent_streaming["input"]["when"] == "2024-01-02T00:00:00Z"
    assert (
        sent_streaming["expected_output"]["raw"] == base64.b64encode(b"bytes").decode()
    )


def test_insert__list_containing_something_that_is_not_an_item__raises_before_sending():
    """A shape check costs an isinstance per item and keeps the old failure point."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    with pytest.raises(ValueError) as exc_info:
        dataset.insert([{"input": "fine"}, 42])

    assert "index 1" in str(exc_info.value)
    assert capture.request_count == 0, "The valid item must not have been sent"


@pytest.mark.parametrize("use_orjson", [True, False])
def test_insert__integer_beyond_64_bits__sent_whichever_serialiser_is_in_use(
    monkeypatch, use_orjson
):
    """orjson refuses these at any option; the standard library writes them.

    Which serialiser happens to be installed must not decide whether an item can be
    uploaded -- and `content_hash`, which runs first, has always accepted them.
    """
    if use_orjson:
        pytest.importorskip("orjson")
    monkeypatch.setenv("OPIK_ENABLE_ORJSON_SERIALIZATION", str(use_orjson).lower())
    huge = 2**70
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    dataset.insert([{"input": {"n": huge}}])

    assert capture.items[0]["data"]["input"]["n"] == huge


def test_insert__producer_error_with_a_worker_error_pending__producer_error_wins(
    monkeypatch,
):
    """Closing the pool must not replace the exception that explains the failure."""
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 1e-9)  # one item per request
    mock_rest_client = Mock()
    mock_rest_client.version.return_value = {"version": "99.0.0"}  # allow parallelism

    def failing_upload() -> None:
        raise ValueError("the worker's own failure")

    capture = UploadCapture(on_request=failing_upload)
    dataset = make_dataset(Dataset, mock_rest_client, capture)

    items = _items(5)
    items[4]["input"] = {"bad": object()}  # the producer fails on this one

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        dataset.insert(items, num_threads=4)


def test_insert__standalone_rest_client__sends_its_auth_and_workspace_headers():
    """A REST client built directly carries its credentials on the wrapper, not the client.

    `Opik` puts them on the httpx client, so an SDK-built client uploads authenticated
    whatever the sender does. A `OpikApi(api_key=..., workspace_name=...)` built by hand
    does not: the generated client applies them per request. Sending a prepared body goes
    straight to the httpx client, so without them the upload is anonymous.
    """
    from opik.rest_api.client import OpikApi

    capture = UploadCapture()
    rest_client = OpikApi(
        base_url=capture.base_url, api_key="SECRET-KEY", workspace_name="my-workspace"
    )
    # The transport this Dataset resolves, with the wrapper's credentials left where the
    # generated client keeps them.
    rest_client._client_wrapper.httpx_client.httpx_client = capture
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=rest_client,
    )

    # num_threads=1 so the parallel gate does not probe the backend version through the
    # same capture first; the upload is then the only request.
    dataset.insert(_items(1), num_threads=1)

    assert capture.request_count == 1
    sent = capture.request_headers[0]
    assert sent.get("Authorization") == "SECRET-KEY", (
        "The upload went out unauthenticated"
    )
    assert sent.get("Comet-Workspace") == "my-workspace", "The upload had no workspace"
    assert sent["Content-Type"] == "application/json;charset=utf-8", (
        "Identity headers must not displace the framing of the request"
    )


def test_insert__standalone_rest_client__honours_the_configured_compression(
    monkeypatch,
):
    """A plain transport carries no setting of its own, so the config has to supply it.

    A REST client built directly sends through a plain `httpx.Client`, which has no
    `compress_json_requests` attribute. The level and the serialiser are already read from
    the configuration on that path; the enable flag has to come from the same place, or a
    caller who turned compression off would still get gzipped bodies.
    """
    from opik.rest_api.client import OpikApi

    monkeypatch.setenv("OPIK_ENABLE_JSON_REQUEST_COMPRESSION", "false")
    capture = UploadCapture()
    rest_client = OpikApi(base_url=capture.base_url, api_key="k", workspace_name="w")
    rest_client._client_wrapper.httpx_client.httpx_client = capture
    dataset = Dataset(
        name="test_dataset",
        description="Test description",
        project_name="Test project",
        rest_client=rest_client,
    )

    dataset.insert(_items(1), num_threads=1)

    body = capture.bodies[0]
    assert json.loads(body)["items"], "The body should be readable as plain JSON"
    assert "Content-Encoding" not in capture.request_headers[0], (
        "An uncompressed body must not be labelled gzip"
    )
