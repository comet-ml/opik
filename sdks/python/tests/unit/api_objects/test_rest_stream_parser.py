import json

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


def _paged_source(rows):
    """A backend that pages from the last id it was given, as the real one does."""

    def read_source(batch_size, last_retrieved_id):
        start = 0
        if last_retrieved_id is not None:
            start = (
                next(i for i, r in enumerate(rows) if r["id"] == last_retrieved_id) + 1
            )
        page = rows[start : start + batch_size]
        return [("\n".join(json.dumps(row) for row in page) + "\n").encode("utf-8")]

    return read_source


def _span_rows(count, *, malformed_at=()):
    """Rows for the real `SpanPublic` model, some of which it cannot construct.

    `start_time` is the one required field. These models accept unknown fields and
    unknown enum values, so a field added by a newer backend parses fine -- what does
    not is a required field arriving absent or with the wrong type, which is what a
    partial row or a serialisation change looks like on the wire.
    """
    rows = []
    for i in range(count):
        row = {"id": f"{i:03d}", "start_time": "2025-01-03T11:03:17.875608Z"}
        if i in malformed_at:
            row["start_time"] = None
        rows.append(row)
    return rows


def test_read_and_parse_full_stream__unparseable_record__does_not_end_pagination():
    # The record count decides whether a page was the last one. Counting the
    # successfully parsed items instead made a page short by one dropped record look
    # like the end of the data, so a single unparseable record silently returned a
    # fraction of the results (4 of 12 for the case below).
    rows = _span_rows(12, malformed_at=(3,))

    items = rest_stream_parser.read_and_parse_full_stream(
        _paged_source(rows),
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    # The unparseable record is still dropped; everything after it is not.
    assert [item.id for item in items] == [f"{i:03d}" for i in range(12) if i != 3]


def test_read_and_parse_full_stream__unparseable_record__respects_max_results():
    rows = _span_rows(12, malformed_at=(3,))

    items = rest_stream_parser.read_and_parse_full_stream(
        _paged_source(rows),
        rest_api_types.SpanPublic,
        max_results=7,
        max_endpoint_batch_size=5,
    )

    assert len(items) == 7
    assert "003" not in [item.id for item in items]


def test_read_and_parse_full_stream__whole_page_unparseable__stops_instead_of_looping():
    # Nothing parsed means the cursor cannot advance, so asking again would re-read
    # the same page forever. Stop rather than spin.
    rows = _span_rows(12, malformed_at=range(12))

    items = rest_stream_parser.read_and_parse_full_stream(
        _paged_source(rows),
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert items == []


def test_read_and_parse_full_stream__items_without_ids__stop_instead_of_duplicating():
    # `id` is optional on these models, so a full page can parse and still leave the
    # cursor at None. Continuing would restart from the first page and append the same
    # items again on every pass.
    rows = [{"start_time": "2025-01-03T11:03:17.875608Z"} for _ in range(5)]

    def read_source(batch_size, last_retrieved_id):
        body = "\n".join(json.dumps(row) for row in rows) + "\n"
        return [body.encode("utf-8")]

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source,
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert len(items) == 5, "one page of items, not the same page over and over"


def test_read_and_parse_full_stream__blank_lines__are_not_counted_as_records():
    # A trailing or doubled newline is not a record; counting it would make a final
    # short page look full and cost one extra request.
    rows = _span_rows(3)

    def read_source(batch_size, last_retrieved_id):
        assert last_retrieved_id is None, "the 3 rows fit in one page of 5"
        body = "\n\n".join(json.dumps(row) for row in rows) + "\n\n"
        return [body.encode("utf-8")]

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source,
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert [item.id for item in items] == ["000", "001", "002"]
