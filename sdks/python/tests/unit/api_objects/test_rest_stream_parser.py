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


class _RowSource:
    """Serves rows the way the backend does: newest id first, filtered by cursor.

    The backend pages with ``id < :last_received_id`` (SpanDAO, TraceDAO, and
    ``dataset_item_id < :lastRetrievedId`` in DatasetItemVersionDAO), so a source
    that hands out pre-baked pages can describe sequences the backend never
    produces — re-serving a row the previous page already sent, for one. Rows
    are ``(id, record)`` pairs; a ``record`` of ``bytes`` is emitted verbatim so
    a row can be a line that never decodes.
    """

    def __init__(self, rows):
        self._rows = sorted(rows, key=lambda row: row[0], reverse=True)
        self.requested = []

    def __call__(self, current_batch_size, last_retrieved_id):
        self.requested.append((current_batch_size, last_retrieved_id))
        rows = self._rows
        if last_retrieved_id is not None:
            rows = [row for row in rows if row[0] < last_retrieved_id]
        lines = []
        for _, record in rows[:current_batch_size]:
            if isinstance(record, bytes):
                lines.append(record)
            else:
                lines.append(json.dumps(record).encode("utf-8") + b"\r\n")
        return lines


def _row(span_id, *, bad=False, raw=None):
    if raw is not None:
        return (span_id, raw)
    record = _record_without_start_time(span_id) if bad else _span_record(span_id)
    return (span_id, record)


def _rows(*specs):
    """Build rows from ``("r06",)``, ``("r04", "bad")`` or ``("r02", "raw")`` specs."""
    rows = []
    for spec in specs:
        span_id, kind = (spec + ("good",))[:2] if len(spec) == 1 else spec
        if kind == "bad":
            rows.append(_row(span_id, bad=True))
        elif kind == "raw":
            rows.append(_row(span_id, raw=b"{not json}\r\n"))
        else:
            rows.append(_row(span_id))
    return rows


def _read(source, max_endpoint_batch_size=2, strict=False):
    return rest_stream_parser.read_and_parse_full_stream(
        read_source=source,
        parsed_item_class=rest_api_types.SpanPublic,
        max_results=None,
        max_endpoint_batch_size=max_endpoint_batch_size,
        strict=strict,
    )


def test_read_and_parse_full_stream__dropped_record_does_not_end_the_stream():
    # The backend served a full 2-record page both times; one record on each
    # page is unparseable, so only 1 item survives per page. A short page is
    # what ends the read -- a dropped record must not look like one.
    # The cursor is the last id the backend sent, so "bad-2" is not served again.
    source = _RowSource(
        _rows(
            ("span-a",),
            ("span-b",),
            ("span-c",),
            ("bad-1", "bad"),
            ("bad-2", "bad"),
        )
    )

    spans = _read(source)

    assert [span.id for span in spans] == ["span-c", "span-b", "span-a"]
    # The third request carries "bad-2", the last id the backend sent. With the
    # cursor taken from the last *parsed* record it would be "span-a", and the
    # backend would serve bad-2 and bad-1 a second time.
    assert source.requested == [(2, None), (2, "span-b"), (2, "bad-2")]


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
    # Nothing on the page decodes, so there is no id to page on and the cursor
    # cannot move: the next request would ask for exactly the same page. The
    # read must stop there rather than spin.
    source = _RowSource(_rows(("row-a", "raw"), ("row-b", "raw")))

    spans = _read(source)

    assert spans == []
    assert len(source.requested) == 1


def test_read_and_parse_full_stream__page_of_unreadable_records_does_not_end_the_read():
    # The backend's position still moved, so the records behind an unreadable
    # page are readable and must not be abandoned.
    source = _RowSource(
        _rows(
            ("r06",),
            ("r05",),
            ("r04", "bad"),
            ("r03", "bad"),
            ("r02",),
            ("r01",),
        )
    )

    spans = _read(source)

    assert [span.id for span in spans] == ["r06", "r05", "r02", "r01"]
    assert source.requested == [(2, None), (2, "r05"), (2, "r03"), (2, "r01")]


def test_read_and_parse_full_stream__strict_raises_on_a_page_without_usable_records(
    caplog,
):
    # The migrate callers turn a short read into deletions, so they ask for a
    # failure instead of a truncated result.
    source = _RowSource(
        _rows(("r06",), ("r05",), ("r04", "bad"), ("r03", "bad"), ("r02",))
    )

    with caplog.at_level(logging.WARNING, logger="opik.api_objects.rest_stream_parser"):
        with pytest.raises(ValueError, match="could not be parsed"):
            _read(source, strict=True)


def test_read_and_parse_full_stream__strict_raises_on_one_dropped_record_among_good_ones(
    caplog,
):
    # 20 rows, one of them unreadable, page size 4. The read returns 19 items
    # and the caller has no way to tell item 7 is missing, so `strict` has to
    # fail: the migrate paths would otherwise replay it as a deletion.
    specs = tuple(
        (f"r{index:02d}", "bad") if index == 6 else (f"r{index:02d}",)
        for index in range(20, 0, -1)
    )
    source = _RowSource(_rows(*specs))

    with caplog.at_level(logging.WARNING, logger="opik.api_objects.rest_stream_parser"):
        with pytest.raises(ValueError, match="1 of the 4 record"):
            _read(source, max_endpoint_batch_size=4, strict=True)

    # It stopped on the page that dropped r06, four rows in, instead of
    # reading the four pages behind it.
    assert len(source.requested) == 4


def test_read_and_parse_full_stream__a_dropped_record_among_good_ones_keeps_the_rest_without_strict():
    # The same page without `strict`: 19 items and a warning, which is what the
    # search paths ask for.
    specs = tuple(
        (f"r{index:02d}", "bad") if index == 6 else (f"r{index:02d}",)
        for index in range(20, 0, -1)
    )
    source = _RowSource(_rows(*specs))

    spans = _read(source, max_endpoint_batch_size=4)

    assert len(spans) == 19
    assert "r06" not in [span.id for span in spans]


def test_read_and_parse_full_stream__dropped_records_are_counted_once(caplog):
    # Every fourth row is unreadable. A cursor taken from the last parsed record
    # re-serves the unreadable tail of each page, so the aggregate warning used
    # to report more dropped records than the backend ever sent.
    specs = tuple(
        (f"r{index:02d}", "bad" if index % 4 == 0 else "good")
        for index in range(20, 0, -1)
    )
    source = _RowSource(_rows(*specs))

    with caplog.at_level(logging.WARNING, logger="opik.api_objects.rest_stream_parser"):
        spans = _read(source, max_endpoint_batch_size=4)

    assert [span.id for span in spans] == [
        f"r{index:02d}" for index in range(19, 0, -1) if index % 4 != 0
    ]
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.levelno >= logging.WARNING
    ]
    reported = re.findall(
        r"finished with (\d+) record\(s\) dropped", "\n".join(warnings)
    )
    assert reported == ["5"], warnings


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
