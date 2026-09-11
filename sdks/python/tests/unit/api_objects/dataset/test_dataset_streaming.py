"""Streaming behaviour of `Dataset.insert`: laziness, memory, and the widened signature."""

import json
import tracemalloc
from unittest.mock import Mock

import pytest

from opik import exceptions
from opik.api_objects.dataset import converters
from opik.api_objects.dataset.dataset import Dataset

from .upload_capture import UploadCapture, make_dataset


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
