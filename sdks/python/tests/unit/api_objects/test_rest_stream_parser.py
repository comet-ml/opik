import json
import logging
import re

import httpx
import pytest

from opik.rest_api.types import span_public as rest_api_types
from opik.api_objects import rest_stream_parser

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


# --- pagination must not be driven by the post-filter count ---------------


def _span_record(span_id: str) -> dict:
    return {**SPANS_STREAM_JSON[0], "id": span_id}


def _record_without_start_time(span_id: str) -> dict:
    # What backend/SDK version skew looks like from here: a record the
    # generated model rejects because a required field is absent.
    record = _span_record(span_id)
    del record["start_time"]
    return record


def _ndjson(*records) -> list:
    return [json.dumps(record).encode("utf-8") + b"\r\n" for record in records]


class _PagedSource:
    """Serves pre-baked pages and records the cursor each request used."""

    def __init__(self, pages):
        self._pages = list(pages)
        self.requested = []

    def __call__(self, current_batch_size, last_retrieved_id):
        self.requested.append((current_batch_size, last_retrieved_id))
        if not self._pages:
            raise AssertionError("read_source called more times than pages given")
        return self._pages.pop(0)


def _read(source, max_endpoint_batch_size=2):
    return rest_stream_parser.read_and_parse_full_stream(
        read_source=source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=max_endpoint_batch_size,
    )


def test_read_and_parse_full_stream__dropped_record_does_not_end_the_stream():
    # The backend served a full 2-record page both times; one record on each
    # page is unparseable, so only 1 item survives per page. A short page is
    # what ends the read -- a dropped record must not look like one.
    source = _PagedSource(
        pages=[
            _ndjson(_record_without_start_time("bad-1"), _span_record("span-a")),
            _ndjson(_span_record("span-b"), _record_without_start_time("bad-2")),
            _ndjson(_span_record("span-c")),
        ]
    )

    spans = _read(source)

    assert [span.id for span in spans] == ["span-a", "span-b", "span-c"]
    assert source.requested == [(2, None), (2, "span-a"), (2, "span-b")]


def test_read_and_parse_full_stream__undecodable_line_does_not_end_the_stream():
    source = _PagedSource(
        pages=[
            [b"{not json}\r\n", *_ndjson(_span_record("span-a"))],
            _ndjson(_span_record("span-b")),
        ]
    )

    spans = _read(source)

    assert [span.id for span in spans] == ["span-a", "span-b"]
    assert source.requested == [(2, None), (2, "span-a")]


def test_read_and_parse_full_stream__clean_pages_still_stop_at_a_short_page():
    source = _PagedSource(
        pages=[
            _ndjson(_span_record("span-a"), _span_record("span-b")),
            _ndjson(_span_record("span-c"), _span_record("span-d")),
            _ndjson(_span_record("span-e")),
        ]
    )

    spans = _read(source)

    assert [span.id for span in spans] == [
        "span-a",
        "span-b",
        "span-c",
        "span-d",
        "span-e",
    ]
    assert source.requested == [(2, None), (2, "span-b"), (2, "span-d")]


def test_read_and_parse_full_stream__fully_dropped_page_stops_without_looping():
    # A page whose records all fail to parse advances neither the cursor nor
    # the result, so the next request would ask for exactly the same page.
    # The read must stop there rather than spin.
    source = _PagedSource(
        pages=[
            _ndjson(_span_record("span-a"), _span_record("span-b")),
            _ndjson(
                _record_without_start_time("bad-1"),
                _record_without_start_time("bad-2"),
            ),
        ]
    )

    spans = _read(source)

    assert [span.id for span in spans] == ["span-a", "span-b"]
    assert len(source.requested) == 2


def test_read_and_parse_full_stream__dropped_records_are_reported(caplog):
    source = _PagedSource(
        pages=[
            _ndjson(
                _record_without_start_time("bad-1"),
                _record_without_start_time("bad-2"),
                _span_record("span-a"),
            ),
            _ndjson(_span_record("span-b")),
        ]
    )

    with caplog.at_level(logging.WARNING, logger="opik.api_objects.rest_stream_parser"):
        spans = _read(source, max_endpoint_batch_size=3)

    assert [span.id for span in spans] == ["span-a", "span-b"]
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.levelno >= logging.WARNING
    ]
    reported = re.findall(
        r"finished with (\d+) record\(s\) dropped", "\n".join(warnings)
    )
    assert reported == ["2"], warnings
