import json
from unittest import mock

import httpx
import pytest

from opik.rest_api.types import span_public as rest_api_types
from opik.rest_api.types import trace_thread as trace_thread_types
from opik.api_objects import rest_stream_parser, search_helpers
import opik.api_objects.helpers as api_object_helpers
from opik.api_objects.threads import threads_client as threads_client_module

SPANS_STREAM_JSON = [
    {
        "id": "0195f6f1-9da2-7630-b285-60cf5580372f",
        "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
        "trace_id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
        "parent_span_id": "0195f6f1-9d9f-77c0-8063-17c59f6d9875",
        "name": "synthesize",
        "type": "general",
        "start_time": "2025-01-03T11:03:17.875608Z",
        "end_time": "2025-01-03T11:03:18.591814Z",
        "input": {"query_str": "If Opik had a motto, what would it be?"},
        "output": {
            "output": '"Empowering continuous improvement through community-driven innovation."'
        },
        "created_at": "2025-04-02T14:39:44.412550Z",
        "last_updated_at": "2025-04-02T14:39:44.412550Z",
        "created_by": "admin",
        "last_updated_by": "admin",
        "duration": 716.206,
    },
    {
        "id": "0195f6f1-9d9f-77c0-8063-17c59f6d9875",
        "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
        "trace_id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
        "name": "query",
        "type": "general",
        "start_time": "2025-01-03T11:03:17.505783Z",
        "end_time": "2025-01-03T11:03:18.591897Z",
        "input": {"query_str": "If Opik had a motto, what would it be?"},
        "output": {
            "output": '"Empowering continuous improvement through community-driven innovation."'
        },
        "created_at": "2025-04-02T14:39:44.412550Z",
        "last_updated_at": "2025-04-02T14:39:44.412550Z",
        "created_by": "admin",
        "last_updated_by": "admin",
        "duration": 1086.114,
    },
]


@pytest.fixture
def spans_stream_source():
    spans_stream = [
        f"{json.dumps(span)}\r\n".encode("utf-8") for span in SPANS_STREAM_JSON
    ]
    yield spans_stream


def test_read_and_parse_stream__span(spans_stream_source):
    spans = rest_stream_parser.read_and_parse_stream(
        spans_stream_source, item_class=rest_api_types.SpanPublic
    )
    assert len(spans) == 2
    for i, span in enumerate(spans):
        expected = rest_api_types.SpanPublic.model_validate(SPANS_STREAM_JSON[i])
        assert span == expected


def test_read_and_parse_stream__limit_samples(spans_stream_source):
    spans = rest_stream_parser.read_and_parse_stream(
        spans_stream_source, item_class=rest_api_types.SpanPublic, nb_samples=1
    )
    assert len(spans) == 1
    expected = rest_api_types.SpanPublic.model_validate(SPANS_STREAM_JSON[0])
    assert spans[0] == expected


def test_read_and_parse_full_stream__happy_flow(spans_stream_source):
    spans = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size, last_retrieved_id: spans_stream_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=10,
    )
    assert len(spans) == 2
    for i, span in enumerate(spans):
        expected = rest_api_types.SpanPublic.model_validate(SPANS_STREAM_JSON[i])
        assert span == expected


def test_read_and_parse_full_stream__no_error__requested_batch_sizes_not_halved(
    spans_stream_source,
):
    requested_batch_sizes = []

    def read_source(current_batch_size, last_retrieved_id):
        requested_batch_sizes.append(current_batch_size)
        return spans_stream_source

    rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=400,
    )

    # Two spans returned (< page size) ends the loop after one request; the
    # requested page size is the configured one, never shrunk.
    assert requested_batch_sizes == [400]


def test_read_and_parse_full_stream__size_correlated_error__halves_page_and_retries_same_cursor(
    spans_stream_source,
):
    requested = []
    calls = {"n": 0}

    def read_source(current_batch_size, last_retrieved_id):
        requested.append((current_batch_size, last_retrieved_id))
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.RemoteProtocolError("incomplete chunked read")
        return spans_stream_source

    spans = rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=400,
    )

    # First request at 400 fails; retried at 200 from the SAME (None) cursor.
    assert requested == [(400, None), (200, None)]
    assert len(spans) == 2


def test_read_and_parse_full_stream__shrink_floor_reached__reraises(
    spans_stream_source,
):
    def read_source(current_batch_size, last_retrieved_id):
        raise httpx.ReadTimeout("timed out")

    # Start at the floor so the first failure can't shrink further.
    with pytest.raises(httpx.ReadTimeout):
        rest_stream_parser.read_and_parse_full_stream(
            read_source=read_source,
            parsed_item_class=rest_api_types.SpanPublic,
            max_results=None,
            max_endpoint_batch_size=rest_stream_parser.MIN_ENDPOINT_BATCH_SIZE,
        )


def test_read_and_parse_full_stream__non_size_correlated_error__propagates(
    spans_stream_source,
):
    def read_source(current_batch_size, last_retrieved_id):
        raise ValueError("not a connection error")

    with pytest.raises(ValueError):
        rest_stream_parser.read_and_parse_full_stream(
            read_source=read_source,
            parsed_item_class=rest_api_types.SpanPublic,
            max_results=None,
            max_endpoint_batch_size=400,
        )


THREADS_STREAM_JSON = [
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
]


@pytest.fixture
def threads_stream_source():
    yield [f"{json.dumps(thread)}\n".encode("utf-8") for thread in THREADS_STREAM_JSON]


# Three threads for the end-to-end pagination tests below: with a page size of
# 2, page 1 is a full page and page 2 holds the remainder.
PAGED_THREADS_JSON = THREADS_STREAM_JSON + [
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


def test_read_and_parse_full_stream__default_cursor_extractor__uses_item_id(
    spans_stream_source,
):
    cursors = []

    def read_source(current_batch_size, last_retrieved_id):
        cursors.append(last_retrieved_id)
        # Full page once, then an empty page to end the read.
        return spans_stream_source if len(cursors) == 1 else []

    rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=2,
    )

    # Without a cursor_extractor the cursor is the item's `id` (old behavior).
    assert cursors == [None, SPANS_STREAM_JSON[1]["id"]]


def test_read_and_parse_full_stream__cursor_extractor__threads_use_thread_model_id(
    threads_stream_source,
):
    cursors = []

    def read_source(current_batch_size, last_retrieved_id):
        cursors.append(last_retrieved_id)
        # Full page once, then an empty page to end the read.
        return threads_stream_source if len(cursors) == 1 else []

    threads = rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=trace_thread_types.TraceThread,
        max_results=None,
        max_endpoint_batch_size=2,
        cursor_extractor=lambda thread: thread.thread_model_id,
    )

    assert len(threads) == 2
    # The threads endpoint compares the cursor against `thread_model_id`
    # (a UUID), not the caller-supplied thread `id` (e.g. "thread_124").
    assert cursors == [None, THREADS_STREAM_JSON[1]["thread_model_id"]]


def test_read_and_parse_full_stream__null_cursor__stops_instead_of_looping(
    spans_stream_source,
):
    # A full page whose cursor is NULL (e.g. a NULL thread_model_id from the
    # LEFT JOIN on trace_threads_final): re-requesting with a NULL cursor
    # would return page 1 again, so stop instead of looping forever.
    calls = {"n": 0}

    def read_source(current_batch_size, last_retrieved_id):
        calls["n"] += 1
        return spans_stream_source

    spans = rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=2,
        cursor_extractor=lambda span: None,
    )

    assert calls["n"] == 1
    assert len(spans) == 2


def test_read_and_parse_full_stream__repeated_cursor__stops_instead_of_looping(
    spans_stream_source,
):
    # A full page whose cursor repeats the previous one (the backend
    # re-returned the same page): stop after the repeat instead of looping
    # forever.
    calls = {"n": 0}

    def read_source(current_batch_size, last_retrieved_id):
        calls["n"] += 1
        return spans_stream_source

    spans = rest_stream_parser.read_and_parse_full_stream(
        read_source=read_source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=2,
        cursor_extractor=lambda span: "same-cursor",
    )

    assert calls["n"] == 2
    assert len(spans) == 4


def test_search_threads_with_filters__paginates_by_thread_model_id():
    # Exercises the public search_helpers.search_threads_with_filters path end
    # to end: the real read_and_parse_full_stream against a fake HTTP layer.
    # The threads endpoint compares the cursor against `thread_model_id`, not
    # the caller-supplied thread `id` — deleting the extractor wiring here
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
