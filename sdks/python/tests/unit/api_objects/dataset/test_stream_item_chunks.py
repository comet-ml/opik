"""Unit tests for Dataset.stream_items() and its parallel paginated reader."""

import json
import threading
from typing import Any, Dict, List, Optional
from unittest.mock import Mock, patch

import pytest

from opik.api_objects import constants
from opik.api_objects.dataset import rest_operations
from opik.api_objects.dataset.dataset import Dataset
from opik.rest_api.core.api_error import ApiError

DATASET_ID = "dataset-id-1"


class FakeItemsEndpoint:
    """Serves ``GET /v1/private/datasets/{id}/items`` from an in-memory list.

    Records every call, and tracks how many were in flight at once so the tests
    can tell a parallel read from a sequential one.
    """

    def __init__(
        self,
        items: List[Dict[str, Any]],
        status_code: int = 200,
        delay_seconds: float = 0.0,
    ) -> None:
        self._items = items
        self._status_code = status_code
        self._delay_seconds = delay_seconds
        self._lock = threading.Lock()
        self._in_flight = 0

        self.calls: List[Dict[str, Any]] = []
        self.max_in_flight = 0

    def __call__(self, path: str, *, method: str, params: Dict[str, Any]) -> Mock:
        with self._lock:
            self._in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self._in_flight)
            self.calls.append({"path": path, "method": method, "params": params})

        try:
            if self._delay_seconds:
                # Long enough that a sequential reader could not overlap two
                # calls, so max_in_flight > 1 really does mean parallelism.
                threading.Event().wait(self._delay_seconds)

            page = params["page"]
            size = params["size"]
            content = self._items[(page - 1) * size : page * size]

            response = Mock()
            response.status_code = self._status_code
            response.headers = {}
            response.text = "error body"
            response.json.return_value = {
                "content": content,
                "page": page,
                "size": size,
                "total": len(self._items),
            }
            return response
        finally:
            with self._lock:
                self._in_flight -= 1

    @property
    def requested_pages(self) -> List[int]:
        return [call["params"]["page"] for call in self.calls]


def _rest_items(count: int) -> List[Dict[str, Any]]:
    return [
        {"id": f"item-{i}", "source": "sdk", "data": {"question": f"q-{i}"}}
        for i in range(count)
    ]


def _build_dataset(endpoint: Optional[FakeItemsEndpoint]) -> Dataset:
    mock_rest_client = Mock()
    mock_rest_client.datasets.get_dataset_by_identifier.return_value.id = DATASET_ID
    if endpoint is not None:
        mock_rest_client._client_wrapper.httpx_client.request.side_effect = endpoint

    return Dataset(
        name="test-dataset",
        description=None,
        project_name=None,
        rest_client=mock_rest_client,
    )


def test_stream_items__single_page__yields_one_chunk():
    endpoint = FakeItemsEndpoint(_rest_items(3))
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10))

    assert chunks == [
        [
            {"question": "q-0", "id": "item-0"},
            {"question": "q-1", "id": "item-1"},
            {"question": "q-2", "id": "item-2"},
        ]
    ]
    assert endpoint.requested_pages == [1]
    assert endpoint.calls[0]["path"] == f"v1/private/datasets/{DATASET_ID}/items"
    assert endpoint.calls[0]["method"] == "GET"


def test_stream_items__empty_dataset__yields_nothing():
    endpoint = FakeItemsEndpoint([])
    dataset = _build_dataset(endpoint)

    assert list(dataset.stream_items()) == []
    assert endpoint.requested_pages == [1]


def test_stream_items__multiple_pages__yields_chunks_in_dataset_order():
    endpoint = FakeItemsEndpoint(_rest_items(25))
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10, num_threads=4))

    assert [len(chunk) for chunk in chunks] == [10, 10, 5]
    assert [item["id"] for chunk in chunks for item in chunk] == [
        f"item-{i}" for i in range(25)
    ]
    assert sorted(endpoint.requested_pages) == [1, 2, 3]


def test_stream_items__num_threads_above_one__fetches_pages_concurrently():
    endpoint = FakeItemsEndpoint(_rest_items(40), delay_seconds=0.2)
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10, num_threads=4))

    assert len(chunks) == 4
    assert endpoint.max_in_flight > 1


def test_stream_items__single_thread__fetches_pages_one_at_a_time():
    endpoint = FakeItemsEndpoint(_rest_items(40), delay_seconds=0.05)
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10, num_threads=1))

    assert len(chunks) == 4
    assert endpoint.max_in_flight == 1


def test_stream_items__nb_samples_below_total__trims_and_skips_unneeded_pages():
    endpoint = FakeItemsEndpoint(_rest_items(100))
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10, num_threads=4, nb_samples=25))

    assert [len(chunk) for chunk in chunks] == [10, 10, 5]
    assert sorted(endpoint.requested_pages) == [1, 2, 3]


def test_stream_items__nb_samples_above_total__reads_everything():
    endpoint = FakeItemsEndpoint(_rest_items(12))
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items(chunk_size=10, nb_samples=1000))

    assert [len(chunk) for chunk in chunks] == [10, 2]


def test_stream_items__lazy__no_request_until_iterated():
    endpoint = FakeItemsEndpoint(_rest_items(10))
    dataset = _build_dataset(endpoint)

    stream = dataset.stream_items()

    assert endpoint.calls == []
    next(iter(stream))
    assert endpoint.requested_pages == [1]


def test_stream_items__data_with_id_key__real_item_id_wins():
    endpoint = FakeItemsEndpoint(
        [{"id": "real-id", "source": "sdk", "data": {"id": "COLLISION", "q": "?"}}]
    )
    dataset = _build_dataset(endpoint)

    chunks = list(dataset.stream_items())

    assert chunks == [[{"id": "real-id", "q": "?"}]]


def test_stream_items__data_shadowing_item_fields__dropped_and_warned_once():
    """Same contract as the typed read path: shadowing keys cannot survive."""
    endpoint = FakeItemsEndpoint(
        [
            {
                "id": f"real-{i}",
                "source": "sdk",
                "data": {"source": "SHADOW", "description": "SHADOW", "q": f"?{i}"},
            }
            for i in range(3)
        ]
    )
    dataset = _build_dataset(endpoint)

    with patch.object(rest_operations.LOGGER, "warning") as mock_warn:
        chunks = list(dataset.stream_items())

    assert chunks == [
        [
            {"id": "real-0", "q": "?0"},
            {"id": "real-1", "q": "?1"},
            {"id": "real-2", "q": "?2"},
        ]
    ]
    mock_warn.assert_called_once_with(
        rest_operations.SHADOWED_KEYS_WARNING, ["description", "source"]
    )


def test_stream_items__filter_string__sent_as_serialized_filter_expressions():
    endpoint = FakeItemsEndpoint(_rest_items(1))
    dataset = _build_dataset(endpoint)

    list(dataset.stream_items(filter_string='data.category = "test"'))

    filters = endpoint.calls[0]["params"]["filters"]
    assert json.loads(filters) == [
        {
            "field": "data",
            "key": "category",
            "operator": "=",
            "type": "map",
            "value": "test",
        }
    ]


def test_stream_items__no_filter_string__filters_left_unset():
    endpoint = FakeItemsEndpoint(_rest_items(1))
    dataset = _build_dataset(endpoint)

    list(dataset.stream_items())

    assert endpoint.calls[0]["params"]["filters"] is None
    assert endpoint.calls[0]["params"]["version"] is None


def test_stream_items__chunk_size__sent_as_page_size():
    endpoint = FakeItemsEndpoint(_rest_items(5))
    dataset = _build_dataset(endpoint)

    list(dataset.stream_items(chunk_size=3))

    assert [call["params"]["size"] for call in endpoint.calls] == [3, 3]


def test_stream_items__non_retryable_error_response__raises_api_error():
    endpoint = FakeItemsEndpoint(_rest_items(5), status_code=404)
    dataset = _build_dataset(endpoint)

    with pytest.raises(ApiError) as exc_info:
        list(dataset.stream_items())

    assert exc_info.value.status_code == 404


@pytest.mark.parametrize(
    "kwargs",
    [
        {"chunk_size": 0},
        {"chunk_size": -1},
        {"chunk_size": True},
        {"chunk_size": "10"},
        {"num_threads": 0},
        {"num_threads": -1},
        {"num_threads": True},
        {"num_threads": "4"},
        {"nb_samples": 0},
        {"nb_samples": -1},
        {"nb_samples": True},
        {"nb_samples": "10"},
    ],
)
def test_stream_items__invalid_arguments__raises_value_error(kwargs):
    dataset = _build_dataset(endpoint=None)

    with pytest.raises(ValueError):
        dataset.stream_items(**kwargs)


def test_stream_items__invalid_arguments__raises_before_any_request():
    endpoint = FakeItemsEndpoint(_rest_items(5))
    dataset = _build_dataset(endpoint)

    with pytest.raises(ValueError):
        dataset.stream_items(num_threads=0)

    assert endpoint.calls == []


def test_get_items__reads_through_the_parallel_paginated_endpoint():
    """get_items() is a thin wrapper over the chunked reader, not the
    cursor-chained typed stream it used to call."""
    total = constants.DATASET_STREAM_BATCH_SIZE * 2 + 1
    endpoint = FakeItemsEndpoint(_rest_items(total))
    dataset = _build_dataset(endpoint)

    items = dataset.get_items()

    assert len(items) == total
    assert [item["id"] for item in items] == [f"item-{i}" for i in range(total)]
    # Just over two default-sized chunks, so three pages of the endpoint.
    assert sorted(endpoint.requested_pages) == [1, 2, 3]
    assert [call["params"]["size"] for call in endpoint.calls] == [
        constants.DATASET_STREAM_BATCH_SIZE
    ] * 3
    assert all(
        call["path"] == f"v1/private/datasets/{DATASET_ID}/items"
        for call in endpoint.calls
    )


def test_get_items__same_items_as_stream_items():
    endpoint = FakeItemsEndpoint(_rest_items(30))
    dataset = _build_dataset(endpoint)

    from_get_items = dataset.get_items()
    streamed = [item for chunk in dataset.stream_items(chunk_size=7) for item in chunk]

    assert from_get_items == streamed


def test_get_items__data_shadowing_item_fields__keys_dropped_as_before():
    endpoint = FakeItemsEndpoint(
        [
            {
                "id": "real-id",
                "source": "sdk",
                "data": {"id": "SHADOW", "trace_id": "SHADOW", "q": "?"},
            }
        ]
    )
    dataset = _build_dataset(endpoint)

    assert dataset.get_items() == [{"id": "real-id", "q": "?"}]


def test_get_items__nb_samples__limits_items_and_pages():
    chunk = constants.DATASET_STREAM_BATCH_SIZE
    endpoint = FakeItemsEndpoint(_rest_items(chunk * 5))
    dataset = _build_dataset(endpoint)

    items = dataset.get_items(nb_samples=chunk + 200)

    assert len(items) == chunk + 200
    # Only the two pages holding wanted items are fetched, not all five.
    assert sorted(endpoint.requested_pages) == [1, 2]


def test_get_items__filter_string__forwarded_to_the_endpoint():
    endpoint = FakeItemsEndpoint(_rest_items(1))
    dataset = _build_dataset(endpoint)

    dataset.get_items(filter_string='data.category = "test"')

    assert json.loads(endpoint.calls[0]["params"]["filters"]) == [
        {
            "field": "data",
            "key": "category",
            "operator": "=",
            "type": "map",
            "value": "test",
        }
    ]


def test_get_items__empty_dataset__returns_empty_list():
    endpoint = FakeItemsEndpoint([])
    dataset = _build_dataset(endpoint)

    assert dataset.get_items() == []


def test_get_items__num_threads__forwarded_to_the_reader():
    chunk = constants.DATASET_STREAM_BATCH_SIZE
    endpoint = FakeItemsEndpoint(_rest_items(chunk * 4), delay_seconds=0.05)
    dataset = _build_dataset(endpoint)

    items = dataset.get_items(num_threads=4)

    assert len(items) == chunk * 4
    assert endpoint.max_in_flight > 1


def test_get_items__num_threads_one__fetches_pages_sequentially():
    chunk = constants.DATASET_STREAM_BATCH_SIZE
    endpoint = FakeItemsEndpoint(_rest_items(chunk * 3), delay_seconds=0.05)
    dataset = _build_dataset(endpoint)

    items = dataset.get_items(num_threads=1)

    assert len(items) == chunk * 3
    assert endpoint.max_in_flight == 1


def test_get_items__thread_count_does_not_change_the_result():
    endpoint = FakeItemsEndpoint(_rest_items(constants.DATASET_STREAM_BATCH_SIZE * 3))
    dataset = _build_dataset(endpoint)

    assert dataset.get_items(num_threads=1) == dataset.get_items(num_threads=8)


@pytest.mark.parametrize("num_threads", [0, -1, True, "4"])
def test_get_items__invalid_num_threads__raises_value_error(num_threads):
    dataset = _build_dataset(endpoint=None)

    with pytest.raises(ValueError):
        dataset.get_items(num_threads=num_threads)


def test_get_items__positional_args_unchanged__nb_samples_still_first():
    """num_threads was appended, so existing positional callers are unaffected."""
    endpoint = FakeItemsEndpoint(_rest_items(50))
    dataset = _build_dataset(endpoint)

    assert len(dataset.get_items(10)) == 10
