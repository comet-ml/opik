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


def test_read_and_parse_full_stream__whole_page_unparseable__reads_past_it():
    # The cursor comes from the raw record, so a page where nothing parsed still
    # moves the read on instead of re-reading the same page.
    rows = _span_rows(12, malformed_at=range(12))

    items = rest_stream_parser.read_and_parse_full_stream(
        _paged_source(rows),
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert items == []
    assert items.dropped_records == 12
    assert not items.truncated


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
    assert items.truncated


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


def test_read_and_parse_full_stream__page_ending_in_unparseable_record__not_reread():
    # The next page is requested from the last record the backend sent, even when
    # that record could not be parsed. Paging from the last parsed item instead
    # re-requested the bad record and dropped it again on the next page.
    rows = _span_rows(10, malformed_at=(4,))
    source = _paged_source(rows)
    cursors = []

    def read_source(batch_size, last_retrieved_id):
        cursors.append(last_retrieved_id)
        return source(batch_size, last_retrieved_id)

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source,
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert cursors == [None, "004", "009"]
    assert [item.id for item in items] == [f"{i:03d}" for i in range(10) if i != 4]
    assert items.dropped_records == 1


def test_read_and_parse_full_stream__alternating_cursor__stops_without_duplicates():
    # A full page whose last record has no id sends the cursor back to None, which
    # was already requested for the first page. Comparing only with the previous
    # cursor let it alternate ('004' -> None -> '004' -> ...) and re-append the same
    # items forever.
    first_page = _span_rows(5)
    second_page = [
        {"id": f"{i:03d}", "start_time": "2025-01-03T11:03:17.875608Z"}
        for i in range(5, 9)
    ] + [{"start_time": "2025-01-03T11:03:17.875608Z"}]
    calls = []

    def read_source(batch_size, last_retrieved_id):
        calls.append(last_retrieved_id)
        assert len(calls) <= 2, "the read should stop instead of cycling"
        page = first_page if last_retrieved_id is None else second_page
        return [("\n".join(json.dumps(row) for row in page) + "\n").encode("utf-8")]

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source,
        rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=5,
    )

    assert calls == [None, "004"]
    assert len(items) == 10
    assert items.truncated


def test_read_and_parse_full_stream__incomplete_read__reported_to_caller(caplog):
    # Dropped records used to leave only one ERROR per record behind, with nothing
    # the caller could check. The result now says how many were dropped, and a
    # single summary warning is logged.
    rows = _span_rows(7, malformed_at=(1, 5))

    with caplog.at_level("WARNING", logger=rest_stream_parser.LOGGER.name):
        items = rest_stream_parser.read_and_parse_full_stream(
            _paged_source(rows),
            rest_api_types.SpanPublic,
            max_results=None,
            max_endpoint_batch_size=5,
        )

    assert len(items) == 5
    assert items.dropped_records == 2
    assert not items.truncated
    summaries = [r for r in caplog.records if "Incomplete read" in r.getMessage()]
    assert len(summaries) == 1


def test_read_and_parse_full_stream__complete_read__nothing_reported(caplog):
    with caplog.at_level("WARNING", logger=rest_stream_parser.LOGGER.name):
        items = rest_stream_parser.read_and_parse_full_stream(
            _paged_source(_span_rows(7)),
            rest_api_types.SpanPublic,
            max_results=None,
            max_endpoint_batch_size=5,
        )

    assert len(items) == 7
    assert items.dropped_records == 0
    assert not items.truncated
    assert not [r for r in caplog.records if "Incomplete read" in r.getMessage()]
