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


def _capture_cursor_extractor(monkeypatch):
    # Replaces read_and_parse_full_stream with a stub that records the
    # cursor_extractor the caller wired up, so the test asserts on wiring.
    captured = {}

    def fake_read_and_parse_full_stream(**kwargs):
        captured.update(kwargs)
        return []

    monkeypatch.setattr(
        rest_stream_parser,
        "read_and_parse_full_stream",
        fake_read_and_parse_full_stream,
    )
    return captured


def test_search_threads_with_filters__passes_thread_model_id_cursor_extractor(
    monkeypatch,
):
    # The threads endpoint compares the cursor against `thread_model_id`, not
    # the caller-supplied thread `id`. Deleting the extractor wiring here must
    # fail the suite instead of silently reintroducing the bug.
    captured = _capture_cursor_extractor(monkeypatch)

    search_helpers.search_threads_with_filters(
        rest_client=mock.MagicMock(),
        project_name="project",
        filters=None,
        max_results=10,
        truncate=True,
    )

    extractor = captured["cursor_extractor"]
    thread = trace_thread_types.TraceThread(
        id="thread_124",
        thread_model_id="0195f6f1-9d9f-77c0-8063-17c59f6d9875",
    )
    assert extractor(thread) == "0195f6f1-9d9f-77c0-8063-17c59f6d9875"


def test_threads_client_search_threads__passes_thread_model_id_cursor_extractor(
    monkeypatch,
):
    # Same wiring guard for the public ThreadsClient.search_threads entry point.
    captured = _capture_cursor_extractor(monkeypatch)
    monkeypatch.setattr(
        api_object_helpers, "parse_filter_expressions", lambda *args, **kwargs: []
    )

    client = mock.MagicMock()
    client._resolve_project_name.return_value = "project"
    threads_client = threads_client_module.ThreadsClient(client)
    threads_client.search_threads(project_name="project", max_results=10)

    extractor = captured["cursor_extractor"]
    thread = trace_thread_types.TraceThread(
        id="thread_124",
        thread_model_id="0195f6f1-9d9f-77c0-8063-17c59f6d9875",
    )
    assert extractor(thread) == "0195f6f1-9d9f-77c0-8063-17c59f6d9875"
