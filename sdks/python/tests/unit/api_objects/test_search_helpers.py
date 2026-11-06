import json
import logging
from unittest import mock

import pytest

import opik.api_objects.helpers as api_object_helpers
from opik.api_objects import rest_stream_parser, search_helpers
from opik.api_objects.threads import threads_client as threads_client_module
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import span_public, trace_public


SPAN_RECORD = {
    "id": "0195f6f1-9da2-7630-b285-60cf5580372f",
    "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    "trace_id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
    "name": "synthesize",
    "type": "general",
    "start_time": "2025-01-03T11:03:17.875608Z",
    "end_time": "2025-01-03T11:03:18.591814Z",
    "input": {"q": "x"},
    "output": {"a": "y"},
    "created_at": "2025-04-02T14:39:44.412550Z",
    "last_updated_at": "2025-04-02T14:39:44.412550Z",
    "created_by": "admin",
    "last_updated_by": "admin",
    "duration": 1.0,
}

TRACE_RECORD = {
    "id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
    "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    "name": "root",
    "start_time": "2025-01-03T11:03:17.505783Z",
    "end_time": "2025-01-03T11:03:18.591897Z",
    "input": {"q": "x"},
    "output": {"a": "y"},
    "created_at": "2025-04-02T14:39:44.412550Z",
    "last_updated_at": "2025-04-02T14:39:44.412550Z",
    "created_by": "admin",
    "last_updated_by": "admin",
    "duration": 1.0,
}


def _stream(record: dict):
    """Mimic the bytes-iterator returned by the Fern-generated search clients."""
    yield f"{json.dumps(record)}\r\n".encode("utf-8")


@pytest.fixture
def patched_sleep():
    # Patch the rest_helpers retry-delay indirection, NOT the global time.sleep:
    # a global patch turns background daemon threads' pacing sleep into a busy
    # loop that can starve the interpreter and hang the suite.
    with mock.patch("opik.api_objects.rest_helpers._sleep") as sleep_mock:
        yield sleep_mock


def _sleep_called_with(mock_sleep: mock.Mock, seconds) -> bool:
    return any(call.args == (seconds,) for call in mock_sleep.call_args_list)


def test_search_spans__429_response__retries_with_reset_header(
    patched_sleep, capture_log
):
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = [
        ApiError(status_code=429, headers={"RateLimit-Reset": "2"}),
        _stream(SPAN_RECORD),
    ]

    result = search_helpers.search_spans_with_filters(
        rest_client=rest_client,
        trace_id=None,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert result[0] == span_public.SpanPublic.model_validate(SPAN_RECORD)
    assert rest_client.spans.search_spans.call_count == 2
    assert _sleep_called_with(patched_sleep, 2)
    assert any(
        "search_spans" in record.message and record.levelno == logging.WARNING
        for record in capture_log.records
    )


def test_search_traces__429_response__retries_with_reset_header(
    patched_sleep, capture_log
):
    rest_client = mock.Mock()
    rest_client.traces.search_traces.side_effect = [
        ApiError(status_code=429, headers={"RateLimit-Reset": "3"}),
        _stream(TRACE_RECORD),
    ]

    result = search_helpers.search_traces_with_filters(
        rest_client=rest_client,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert result[0] == trace_public.TracePublic.model_validate(TRACE_RECORD)
    assert rest_client.traces.search_traces.call_count == 2
    assert _sleep_called_with(patched_sleep, 3)
    assert any(
        "search_traces" in record.message and record.levelno == logging.WARNING
        for record in capture_log.records
    )


def test_search_spans__non_429_error__propagates_without_retry():
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = ApiError(
        status_code=400, body="bad request"
    )

    with pytest.raises(ApiError) as exc_info:
        search_helpers.search_spans_with_filters(
            rest_client=rest_client,
            trace_id=None,
            project_name="proj",
            filters=None,
            max_results=10,
            truncate=False,
        )

    assert exc_info.value.status_code == 400
    # call_count == 1 already proves no retry happened — no need to assert on
    # patched_sleep, which is polluted by background-thread sleeps.
    assert rest_client.spans.search_spans.call_count == 1


def test_search_traces__non_429_error__propagates_without_retry():
    rest_client = mock.Mock()
    rest_client.traces.search_traces.side_effect = ApiError(
        status_code=500, body="boom"
    )

    with pytest.raises(ApiError) as exc_info:
        search_helpers.search_traces_with_filters(
            rest_client=rest_client,
            project_name="proj",
            filters=None,
            max_results=10,
            truncate=False,
        )

    assert exc_info.value.status_code == 500
    assert rest_client.traces.search_traces.call_count == 1


def test_search_spans__max_batch_size__passed_as_limit_to_rest_client():
    rest_client = mock.Mock()
    rest_client.spans.search_spans.return_value = _stream(SPAN_RECORD)

    search_helpers.search_spans_with_filters(
        rest_client=rest_client,
        trace_id=None,
        project_name="proj",
        filters=None,
        max_results=10_000,
        truncate=False,
        max_batch_size=400,
    )

    # max_results (10_000) is the total cap; max_batch_size (400) is the
    # per-request page size that reaches the Fern client as ``limit``.
    _, kwargs = rest_client.spans.search_spans.call_args
    assert kwargs["limit"] == 400


def test_search_traces__max_batch_size__passed_as_limit_to_rest_client():
    rest_client = mock.Mock()
    rest_client.traces.search_traces.return_value = _stream(TRACE_RECORD)

    search_helpers.search_traces_with_filters(
        rest_client=rest_client,
        project_name="proj",
        filters=None,
        max_results=10_000,
        truncate=False,
        max_batch_size=250,
    )

    _, kwargs = rest_client.traces.search_traces.call_args
    assert kwargs["limit"] == 250


def test_search_spans__429_without_reset_header__falls_back_to_one_second_sleep(
    patched_sleep,
):
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = [
        ApiError(status_code=429, headers={}),
        _stream(SPAN_RECORD),
    ]

    result = search_helpers.search_spans_with_filters(
        rest_client=rest_client,
        trace_id=None,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert rest_client.spans.search_spans.call_count == 2
    assert _sleep_called_with(patched_sleep, 1)


# Three threads for the end-to-end pagination tests below: with a page size of
# 2, page 1 is a full page and page 2 holds the remainder.
PAGED_THREADS_JSON = [
    {
        "id": "thread_123",
        "thread_model_id": "0195f6f1-9da2-7630-b285-60cf5580372f",
        "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    },
    {
        "id": "thread_124",
        "thread_model_id": "0195f6f1-9d9f-77c0-8063-17c59f6d9875",
        "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    },
    {
        "id": "thread_125",
        "thread_model_id": "0195f6f1-9e11-7630-b285-60cf5580372f",
        "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    },
]


def _paged_threads_backend():
    """Fake `search_trace_threads`: records the cursors it was called with and
    serves page 1 (cursor None, 2 threads = a full page of 2) then page 2
    (cursor = page 1's last thread_model_id, 1 thread). Any other cursor gets
    an empty page."""
    requested_cursors = []

    def fake_search_trace_threads(**kwargs):
        cursor = kwargs.get("last_retrieved_thread_model_id")
        requested_cursors.append(cursor)
        if cursor is None:
            page = PAGED_THREADS_JSON[:2]
        elif cursor == PAGED_THREADS_JSON[1]["thread_model_id"]:
            page = PAGED_THREADS_JSON[2:]
        else:
            page = []
        return [f"{json.dumps(thread)}\n".encode("utf-8") for thread in page]

    return requested_cursors, fake_search_trace_threads


def test_search_threads_with_filters__paginates_by_thread_model_id():
    # Exercises the public search_helpers.search_threads_with_filters path end
    # to end: the real read_and_parse_full_stream against a fake HTTP layer.
    # The threads endpoint compares the cursor against `thread_model_id`, not
    # the caller-supplied thread `id` — deleting the cursor_field wiring here
    # must fail the suite instead of silently reintroducing the bug.
    requested_cursors, fake_search_trace_threads = _paged_threads_backend()
    rest_client = mock.MagicMock()
    rest_client.traces.search_trace_threads.side_effect = fake_search_trace_threads

    threads = search_helpers.search_threads_with_filters(
        rest_client=rest_client,
        project_name="project",
        filters=None,
        max_results=10,
        truncate=True,
        max_batch_size=2,
    )

    assert [thread.thread_model_id for thread in threads] == [
        thread["thread_model_id"] for thread in PAGED_THREADS_JSON
    ]
    # Page 2 advanced past page 1's last thread_model_id (a UUID), not its `id`.
    assert requested_cursors == [None, PAGED_THREADS_JSON[1]["thread_model_id"]]


def test_threads_client_search_threads__paginates_by_thread_model_id(monkeypatch):
    # Same end-to-end guard for the public ThreadsClient.search_threads entry
    # point. search_threads exposes no page-size knob, so a call-through spy
    # shrinks the page to 2 and delegates to the real parser.
    monkeypatch.setattr(
        api_object_helpers, "parse_filter_expressions", lambda *args, **kwargs: []
    )
    real_read_and_parse_full_stream = rest_stream_parser.read_and_parse_full_stream

    def shrinking_page_spy(read_source, *args, **kwargs):
        kwargs["max_endpoint_batch_size"] = 2
        return real_read_and_parse_full_stream(read_source, *args, **kwargs)

    monkeypatch.setattr(
        rest_stream_parser, "read_and_parse_full_stream", shrinking_page_spy
    )

    requested_cursors, fake_search_trace_threads = _paged_threads_backend()
    client = mock.MagicMock()
    client._resolve_project_name.return_value = "project"
    client.rest_client.traces.search_trace_threads.side_effect = (
        fake_search_trace_threads
    )

    threads = threads_client_module.ThreadsClient(client).search_threads(
        project_name="project", max_results=10
    )

    assert [thread.thread_model_id for thread in threads] == [
        thread["thread_model_id"] for thread in PAGED_THREADS_JSON
    ]
    # Page 2 advanced past page 1's last thread_model_id (a UUID), not its `id`.
    assert requested_cursors == [None, PAGED_THREADS_JSON[1]["thread_model_id"]]


def test_search_threads_with_filters__unreadable_tail_record_is_not_reserved(
    caplog,
):
    # Rows 5, 4, 3 (unreadable), 2, 1, page size 3, backend paging by
    # `thread_model_id < cursor`. The cursor after page 1 must be the
    # backend's position ("3"), not the last parsed thread ("4"), or record
    # 3 is served twice and counted as dropped twice.
    threads_json = [
        {"id": "thread_5", "thread_model_id": "5", "project_id": "p"},
        {"id": "thread_4", "thread_model_id": "4", "project_id": "p"},
        {
            "id": "thread_3",
            "thread_model_id": "3",
            "project_id": "p",
            "start_time": "not-a-date",
        },
        {"id": "thread_2", "thread_model_id": "2", "project_id": "p"},
        {"id": "thread_1", "thread_model_id": "1", "project_id": "p"},
    ]
    requested_cursors = []

    def fake_search_trace_threads(**kwargs):
        cursor = kwargs.get("last_retrieved_thread_model_id")
        requested_cursors.append(cursor)
        rows = threads_json
        if cursor is not None:
            rows = [t for t in rows if t["thread_model_id"] < cursor]
        page = rows[: kwargs.get("limit", 3)]
        return [f"{json.dumps(thread)}\n".encode("utf-8") for thread in page]

    rest_client = mock.MagicMock()
    rest_client.traces.search_trace_threads.side_effect = fake_search_trace_threads

    with caplog.at_level(logging.WARNING, logger="opik.api_objects.rest_stream_parser"):
        threads = search_helpers.search_threads_with_filters(
            rest_client=rest_client,
            project_name="project",
            filters=None,
            max_results=10,
            truncate=True,
            max_batch_size=3,
        )

    assert [thread.thread_model_id for thread in threads] == ["5", "4", "2", "1"]
    assert requested_cursors == [None, "3"]
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.levelno >= logging.WARNING
    ]
    assert any(
        "finished with 1 record(s) dropped" in warning for warning in warnings
    ), warnings
