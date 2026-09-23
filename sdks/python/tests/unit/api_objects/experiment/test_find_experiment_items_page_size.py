"""Tests for the page size and concurrency of the experiment Compare-view read."""

import json
import math
import threading
import types
from typing import Any, Dict, List
from unittest.mock import Mock

import pytest

from opik import exceptions
from opik.api_objects import constants
from opik.rest_api.core.api_error import ApiError
from opik.api_objects.experiment import (
    experiment as experiment_module,
    experiments_client as experiments_client_module,
    rest_operations,
)


class _RecordingHttpxClient:
    """Serves ``total`` dataset items, one experiment item each, as JSON pages.

    The read parses the endpoint's JSON itself rather than going through the
    generated REST models, so the seam under test is the HTTP call.
    """

    def __init__(self, total: int) -> None:
        self._total = total
        self.reported_total = total
        self._lock = threading.Lock()
        self.requested_sizes: List[int] = []
        self.requested_pages: List[int] = []

    def request(self, path: str, *, method: str, params: Dict[str, Any]) -> Any:
        page, size = params["page"], params["size"]
        with self._lock:
            self.requested_sizes.append(size)
            self.requested_pages.append(page)
        start = (page - 1) * size
        content = [
            _dataset_item(index)
            for index in range(start, min(start + size, self._total))
        ]
        body = json.dumps({"content": content, "total": self.reported_total})
        return types.SimpleNamespace(
            status_code=200, content=body.encode("utf-8"), text=body, headers={}
        )


class _FailingHttpxClient:
    """Answers every page with one non-2xx response."""

    def __init__(self, status_code: int, body: str) -> None:
        self._status_code = status_code
        self._body = body
        self.calls = 0

    def request(self, path: str, *, method: str, params: Dict[str, Any]) -> Any:
        self.calls += 1
        return types.SimpleNamespace(
            status_code=self._status_code,
            content=self._body.encode("utf-8"),
            text=self._body,
            headers={"content-type": "application/json", "x-request-id": "req-1"},
        )


def _dataset_item(index: int) -> Dict[str, Any]:
    compare = {
        "id": f"experiment-item-{index}",
        "trace_id": f"trace-{index}",
        "dataset_item_id": f"dataset-item-{index}",
        "input": None,
        "output": None,
        "feedback_scores": None,
        "assertion_results": None,
    }
    return {
        "id": f"dataset-item-{index}",
        "data": {"index": index},
        "experiment_items": [compare],
    }


def _read(total: int, **kwargs: Any) -> Dict[str, Any]:
    datasets_client = _RecordingHttpxClient(total)
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=datasets_client)
    )

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        **kwargs,
    )
    return {
        "items": items,
        "requested_sizes": datasets_client.requested_sizes,
        "requested_pages": datasets_client.requested_pages,
    }


def test_find_experiment_items_for_dataset__default_page_size_is_2000():
    # Spelled out rather than read from the constant: deriving both sides from
    # EXPERIMENT_ITEMS_READ_PAGE_SIZE would keep the assertion self-consistent
    # for any value it was changed to.
    expected_page_size = 2000
    assert constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE == expected_page_size

    total = 2500
    result = _read(total=total, max_results=total)

    pages = math.ceil(total / expected_page_size)
    assert result["requested_sizes"] == [expected_page_size] * pages
    assert len(result["items"]) == total


def test_find_experiment_items_for_dataset__page_size_override_is_used():
    result = _read(total=250, max_results=250, page_size=100)

    assert result["requested_sizes"] == [100, 100, 100]
    assert len(result["items"]) == 250


def test_find_experiment_items_for_dataset__max_results_still_truncates():
    result = _read(total=2500, max_results=1500)

    ids = [item.id for item in result["items"]]
    assert ids == [f"experiment-item-{index}" for index in range(1500)]


def test_find_experiment_items_for_dataset__pages_are_assembled_in_page_order():
    result = _read(total=1000, max_results=1000, page_size=100, num_threads=8)

    ids = [item.id for item in result["items"]]
    assert ids == [f"experiment-item-{index}" for index in range(1000)]
    assert sorted(result["requested_pages"]) == list(range(1, 11))


def test_find_experiment_items_for_dataset__single_thread_reads_pages_in_order():
    result = _read(total=1000, max_results=1000, page_size=100, num_threads=1)

    assert result["requested_pages"] == list(range(1, 11))
    assert len(result["items"]) == 1000


def test_find_experiment_items_for_dataset__does_not_fetch_pages_it_does_not_need():
    result = _read(total=10000, max_results=250, page_size=100, num_threads=8)

    assert sorted(result["requested_pages"]) == [1, 2, 3]
    assert len(result["items"]) == 250


def test_find_experiment_items_for_dataset__stops_at_the_last_page():
    result = _read(total=150, max_results=10000, page_size=100, num_threads=8)

    assert sorted(result["requested_pages"]) == [1, 2]
    assert len(result["items"]) == 150


class _BarrierHttpxClient(_RecordingHttpxClient):
    """Blocks every page until ``parties`` of them are in flight at once."""

    def __init__(self, total: int, parties: int) -> None:
        super().__init__(total)
        self._barrier = threading.Barrier(parties, timeout=10)
        self.barrier_broke = False

    def request(self, path: str, *, method: str, params: Dict[str, Any]) -> Any:
        response = super().request(path, method=method, params=params)
        if params["page"] > 1:
            try:
                self._barrier.wait()
            except threading.BrokenBarrierError:
                self.barrier_broke = True
        return response


def test_find_experiment_items_for_dataset__pages_after_the_first_overlap():
    # A sequential implementation cannot reach the barrier's party count, so it
    # times out and this fails rather than silently passing.
    datasets_client = _BarrierHttpxClient(total=500, parties=4)
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=datasets_client)
    )

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        max_results=500,
        page_size=100,
        num_threads=4,
    )

    assert not datasets_client.barrier_broke
    assert [item.id for item in items] == [
        f"experiment-item-{index}" for index in range(500)
    ]


@pytest.mark.parametrize("total", [None, "many", -1])
def test_find_experiment_items_for_dataset__unusable_total_falls_back_to_walking(total):
    datasets_client = _RecordingHttpxClient(250)
    datasets_client.reported_total = total
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=datasets_client)
    )

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        max_results=10000,
        page_size=100,
        num_threads=1,
    )

    assert [item.id for item in items] == [
        f"experiment-item-{index}" for index in range(250)
    ]
    # Page 4 is empty and ends the walk when no usable page count is available.
    assert datasets_client.requested_pages == [1, 2, 3, 4]


def _experiment(experiments_client: Any) -> experiment_module.Experiment:
    return experiment_module.Experiment(
        id="some-experiment-id",
        name="some-experiment",
        dataset_name="some-dataset",
        rest_client=Mock(),
        streamer=Mock(),
        experiments_client=experiments_client,
    )


@pytest.mark.parametrize(
    "page_size",
    [0, -1, 1.5, True, None],
)
def test_get_items__rejects_a_page_size_that_is_not_a_positive_integer(page_size):
    experiment = _experiment(Mock())

    with pytest.raises(ValueError, match="page_size must be a positive integer"):
        experiment.get_items(page_size=page_size)


def test_get_items__rejects_a_page_size_above_the_cap():
    experiment = _experiment(Mock())
    over_cap = constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE + 1

    with pytest.raises(
        ValueError,
        match=(
            f"page_size must not exceed "
            f"{constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE}, got {over_cap}"
        ),
    ):
        experiment.get_items(page_size=over_cap)


def test_get_items__accepts_a_page_size_at_the_cap():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items(
        page_size=constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
    )

    assert (
        experiments_client.find_experiment_items_for_dataset.call_args.kwargs[
            "page_size"
        ]
        == constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
    )


@pytest.mark.parametrize(
    "num_threads",
    [0, -1, 1.5, True, None],
)
def test_get_items__rejects_a_num_threads_that_is_not_a_positive_integer(num_threads):
    experiment = _experiment(Mock())

    with pytest.raises(ValueError, match="num_threads must be a positive integer"):
        experiment.get_items(num_threads=num_threads)


def test_get_items__rejects_a_num_threads_above_the_cap():
    experiment = _experiment(Mock())
    over_cap = constants.DATASET_ITEMS_READ_MAX_THREADS + 1

    with pytest.raises(
        ValueError,
        match=(
            f"num_threads must not exceed "
            f"{constants.DATASET_ITEMS_READ_MAX_THREADS}, got {over_cap}"
        ),
    ):
        experiment.get_items(num_threads=over_cap)


def test_get_items__accepts_a_num_threads_at_the_cap():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items(
        num_threads=constants.DATASET_ITEMS_READ_MAX_THREADS
    )

    assert (
        experiments_client.find_experiment_items_for_dataset.call_args.kwargs[
            "num_threads"
        ]
        == constants.DATASET_ITEMS_READ_MAX_THREADS
    )


def test_get_items__defaults_to_the_page_size_and_thread_constants():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items()

    kwargs = experiments_client.find_experiment_items_for_dataset.call_args.kwargs
    assert kwargs["page_size"] == constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE
    assert kwargs["num_threads"] == constants.DATASET_ITEMS_READ_NUM_THREADS


@pytest.mark.parametrize(
    "kwargs,message",
    [
        ({"page_size": 0}, "page_size must be a positive integer"),
        ({"page_size": 10**9}, "page_size must not exceed"),
        ({"num_threads": None}, "num_threads must be a positive integer"),
        ({"num_threads": 10**9}, "num_threads must not exceed"),
    ],
)
def test_experiments_client__validates_before_touching_the_rest_client(kwargs, message):
    rest_client = Mock()
    client = experiments_client_module.ExperimentsClient(rest_client)

    with pytest.raises(ValueError, match=message):
        client.find_experiment_items_for_dataset(
            dataset_name="some-dataset",
            experiment_ids=["some-experiment-id"],
            **kwargs,
        )

    rest_client.datasets.get_dataset_by_identifier.assert_not_called()


def _read_failing(status_code: int, body: str) -> _FailingHttpxClient:
    httpx_client = _FailingHttpxClient(status_code, body)
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
    )
    rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        max_results=10,
        truncate=False,
    )
    return httpx_client


def test_find_experiment_items_for_dataset__non_2xx_raises_api_error_with_the_response():
    body = '{"code": 500, "message": "MEMORY_LIMIT_EXCEEDED"}'

    with pytest.raises(ApiError) as exception_info:
        _read_failing(500, body)

    error = exception_info.value
    assert error.status_code == 500
    assert error.headers == {
        "content-type": "application/json",
        "x-request-id": "req-1",
    }
    # A dict, not the raw text: `ApiError.body` readers in the SDK look for the
    # backend's `message` key, which is what the generated client gave them.
    assert error.body == {"code": 500, "message": "MEMORY_LIMIT_EXCEEDED"}


def test_find_experiment_items_for_dataset__non_json_error_body_stays_text():
    with pytest.raises(ApiError) as exception_info:
        _read_failing(502, "<html>Bad Gateway</html>")

    assert exception_info.value.body == "<html>Bad Gateway</html>"


def test_find_experiment_items_for_dataset__a_failed_first_page_stops_the_read():
    httpx_client = _FailingHttpxClient(503, '{"message": "unavailable"}')
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
    )

    with pytest.raises(ApiError):
        rest_operations.find_experiment_items_for_dataset(
            rest_client=rest_client,
            dataset_id="some-dataset-id",
            experiment_ids=["some-experiment-id"],
            max_results=10_000,
            truncate=False,
        )

    assert httpx_client.calls == 1


@pytest.mark.parametrize("max_results", [0, -1])
def test_find_experiment_items_for_dataset__non_positive_max_results_reads_nothing(
    max_results,
):
    result = _read(total=500, max_results=max_results)

    assert result["items"] == []
    # Not merely an empty result: the sequential read this replaced never sent a
    # request for a non-positive limit, and a first page read before the limit is
    # consulted would raise where callers used to get `[]`.
    assert result["requested_pages"] == []


@pytest.mark.parametrize("max_results", [0, -1])
def test_get_items__non_positive_max_results_reaches_no_compare_request(max_results):
    """The same guard from the public entry point, through the real client."""

    class _ExplodingHttpxClient:
        def request(self, path: str, *, method: str, params: Dict[str, Any]) -> Any:
            raise AssertionError(f"no request expected, got page {params['page']}")

    rest_client = Mock()
    rest_client._client_wrapper = types.SimpleNamespace(
        httpx_client=_ExplodingHttpxClient()
    )
    rest_client.datasets.get_dataset_by_identifier.return_value = types.SimpleNamespace(
        id="some-dataset-id"
    )
    experiment = _experiment(experiments_client_module.ExperimentsClient(rest_client))

    assert experiment.get_items(max_results=max_results) == []


def test_public_read_signatures__page_size_and_num_threads_are_keyword_only():
    """Positional order is a contract; these two were never meant to be part of it."""
    import inspect

    for callable_ in (
        experiment_module.Experiment.get_items,
        experiments_client_module.ExperimentsClient.find_experiment_items_for_dataset,
    ):
        parameters = inspect.signature(callable_).parameters
        for name in ("page_size", "num_threads"):
            assert parameters[name].kind is inspect.Parameter.KEYWORD_ONLY, (
                f"{callable_.__qualname__}.{name} must be keyword-only"
            )


def test_http_client__is_the_object_the_generated_endpoints_use():
    httpx_client = object()
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
    )

    assert rest_operations.http_client(rest_client) is httpx_client


@pytest.mark.parametrize(
    "rest_client",
    [
        types.SimpleNamespace(),
        types.SimpleNamespace(_client_wrapper=types.SimpleNamespace()),
        types.SimpleNamespace(_client_wrapper=None),
    ],
)
def test_http_client__says_what_moved_when_the_generated_layout_changes(rest_client):
    # A regenerated client that relocates this attribute should fail here, naming the
    # one function to update, rather than as an AttributeError inside a page fetch.
    with pytest.raises(
        exceptions.OpikException, match="generated client's layout has changed"
    ):
        rest_operations.http_client(rest_client)


def test_get_items__reaches_the_http_client_through_the_accessor():
    """The read's own path, not the accessor in isolation.

    `_fetch_page_json` spelling out `_client_wrapper.httpx_client` inline would pass
    every other test in this module -- both spellings find the same object on a client
    that has it -- and diverge only where the generated layout moved, which is the one
    case the accessor exists for. So that is where this pins it: through the public
    read, on a client missing the attribute.
    """
    rest_client = Mock()
    del rest_client._client_wrapper  # Mock would otherwise autocreate it
    rest_client.datasets.get_dataset_by_identifier.return_value = types.SimpleNamespace(
        id="some-dataset-id"
    )
    experiment = _experiment(experiments_client_module.ExperimentsClient(rest_client))

    with pytest.raises(
        exceptions.OpikException, match="generated client's layout has changed"
    ):
        experiment.get_items(max_results=10)


class _BodyHttpxClient:
    """Answers every page 200 with one fixed body, whatever it contains."""

    def __init__(self, body: str) -> None:
        self._body = body

    def request(self, path: str, *, method: str, params: Dict[str, Any]) -> Any:
        return types.SimpleNamespace(
            status_code=200,
            content=self._body.encode("utf-8"),
            text=self._body,
            headers={"content-type": "application/json"},
        )


def _read_body(body: str) -> None:
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=_BodyHttpxClient(body))
    )
    rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        max_results=10,
        truncate=False,
    )


def test_find_experiment_items_for_dataset__undecodable_success_body_is_an_api_error():
    # The generated endpoint decodes the 2xx body inside the same `try` as the error
    # one, so callers see an `ApiError` carrying the raw text rather than a decoder
    # error escaping from the middle of a read. Parsing the page ourselves must not
    # quietly change which exception a broken gateway produces.
    with pytest.raises(ApiError) as exception_info:
        _read_body("<html>502 from a proxy</html>")

    error = exception_info.value
    assert error.status_code == 200
    assert error.body == "<html>502 from a proxy</html>"


@pytest.mark.parametrize(
    "body, expected",
    [
        ('["not", "a", "page"]', "the page is a list"),
        ('{"content": {"0": {}}, "total": 1}', "`content` is a dict"),
        ('{"content": ["not-a-dataset-item"], "total": 1}', "entry is a str"),
        (
            '{"content": [{"id": "d-1", "experiment_items": "nope"}], "total": 1}',
            "`experiment_items` is a str",
        ),
        (
            '{"content": [{"id": "d-1", "data": "nope",'
            ' "experiment_items": [{"id": "e-1", "trace_id": "t-1",'
            ' "dataset_item_id": "d-1"}]}], "total": 1}',
            "`data` is a str",
        ),
        (
            '{"content": [{"id": "d-1", "experiment_items": ["not-an-item"]}],'
            ' "total": 1}',
            "`experiment_items` entry is a str",
        ),
    ],
)
def test_find_experiment_items_for_dataset__malformed_page_shapes_are_named(
    body, expected
):
    # Without these guards the failures are an `AttributeError` raised somewhere in
    # the middle of the parse, which says nothing about what the backend sent.
    with pytest.raises(exceptions.OpikException, match=expected):
        _read_body(body)


@pytest.mark.parametrize("field", ["feedback_scores", "assertion_results"])
def test_find_experiment_items_for_dataset__non_list_feedback_or_assertion_fields_do_not_parse(
    field,
):
    # The dangerous shape: `list()` over a dict yields its keys and over a string its
    # characters, so a malformed `assertion_results` would otherwise become a list of
    # plausible-looking nonsense instead of an error -- and a migration documented as
    # lossless would carry it to the destination.
    compare = {
        "id": "e-1",
        "trace_id": "t-1",
        "dataset_item_id": "d-1",
        field: {"first": {"passed": True}},
    }
    body = json.dumps(
        {"content": [{"id": "d-1", "experiment_items": [compare]}], "total": 1}
    )

    with pytest.raises(exceptions.OpikException, match=f"`{field}` is a dict"):
        _read_body(body)


@pytest.mark.parametrize("falsy", ['""', "0", "false"])
@pytest.mark.parametrize(
    "field", ["content", "experiment_items", "feedback_scores", "assertion_results"]
)
def test_find_experiment_items_for_dataset__falsy_non_list_fields_still_raise(
    field, falsy
):
    # `x or []` would default on these too, so a field the backend sent as `""`, `0` or
    # `false` would read as an empty list and the read would return a short result
    # instead of failing. Only an absent key and an explicit `null` are empty.
    compare = {"id": "e-1", "trace_id": "t-1", "dataset_item_id": "d-1"}
    if field in ("feedback_scores", "assertion_results"):
        body = (
            '{"content": [{"id": "d-1", "experiment_items":'
            f' [{json.dumps(compare)[:-1]}, "{field}": {falsy}}}]}}], "total": 1}}'
        )
    elif field == "experiment_items":
        body = (
            f'{{"content": [{{"id": "d-1", "experiment_items": {falsy}}}], "total": 1}}'
        )
    else:
        body = f'{{"content": {falsy}, "total": 1}}'

    with pytest.raises(exceptions.OpikException, match=f"`{field}` is a"):
        _read_body(body)


@pytest.mark.parametrize(
    "field", ["content", "experiment_items", "feedback_scores", "assertion_results"]
)
def test_find_experiment_items_for_dataset__null_list_fields_read_as_empty(field):
    # The other half of the same rule: an explicit `null` is the empty list, which is
    # what the backend actually sends for a row with no scores or assertions.
    compare = {
        "id": "e-1",
        "trace_id": "t-1",
        "dataset_item_id": "d-1",
        "feedback_scores": None,
        "assertion_results": None,
    }
    if field == "content":
        body = json.dumps({"content": None, "total": 0})
    elif field == "experiment_items":
        body = json.dumps(
            {"content": [{"id": "d-1", "experiment_items": None}], "total": 1}
        )
    else:
        body = json.dumps(
            {"content": [{"id": "d-1", "experiment_items": [compare]}], "total": 1}
        )

    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=_BodyHttpxClient(body))
    )
    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        max_results=10,
        truncate=False,
    )

    if field in ("content", "experiment_items"):
        assert items == []
    else:
        assert len(items) == 1
        assert items[0].feedback_scores == []
        assert items[0].assertion_results == []


@pytest.mark.parametrize("entry", ["null", '"oops"', "3"])
@pytest.mark.parametrize("field", ["feedback_scores", "assertion_results"])
def test_find_experiment_items_for_dataset__non_dict_entries_do_not_parse(field, entry):
    # A list of the right shape can still hold entries of the wrong one, and the two
    # fields failed differently: `feedback_scores` raised `AttributeError` from the
    # `.get()` in the comprehension, while `assertion_results` was copied straight
    # through and handed the caller `[None]`. The generated models rejected both.
    compare = (
        '{"id": "e-1", "trace_id": "t-1", "dataset_item_id": "d-1",'
        f' "{field}": [{entry}]}}'
    )
    body = (
        f'{{"content": [{{"id": "d-1", "experiment_items": [{compare}]}}], "total": 1}}'
    )

    with pytest.raises(exceptions.OpikException, match=f"an entry of `{field}` is a"):
        _read_body(body)


@pytest.mark.parametrize("field", ["feedback_scores", "assertion_results"])
def test_find_experiment_items_for_dataset__dict_entries_still_parse(field):
    # The guard must not reject the shape the backend actually sends.
    entry = {"name": "accuracy", "value": 1.0, "reason": "ok", "category_name": "c"}
    body = json.dumps(
        {
            "content": [
                {
                    "id": "d-1",
                    "experiment_items": [
                        {
                            "id": "e-1",
                            "trace_id": "t-1",
                            "dataset_item_id": "d-1",
                            field: [entry],
                        }
                    ],
                }
            ],
            "total": 1,
        }
    )
    rest_client = types.SimpleNamespace(
        _client_wrapper=types.SimpleNamespace(httpx_client=_BodyHttpxClient(body))
    )

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        max_results=10,
        truncate=False,
    )

    assert len(items) == 1
    if field == "feedback_scores":
        assert items[0].feedback_scores == [
            {
                "category_name": "c",
                "name": "accuracy",
                "reason": "ok",
                "value": 1.0,
            }
        ]
    else:
        assert items[0].assertion_results == [entry]
