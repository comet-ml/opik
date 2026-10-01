"""Pagination of stream_dataset_items must not stop at a page of unreadable records.

The backend pages with `dataset_item_id < :lastRetrievedId`
(`DatasetItemVersionDAO`), so the source below holds one row set and serves the
rows below the cursor rather than handing out a pre-baked page list — a scripted
list can describe sequences the backend never produces, which is what a
maintainer rejected on the equivalent span test.

`data` is a required `Dict[str, Optional[Any]]` on the generated model, so a
record whose `data` is not an object is one the SDK cannot parse while the
server can still hold it: the version-skew shape.
"""

import json
from typing import Any, Dict, Iterable, List, Optional
from unittest.mock import Mock

import pytest

from opik.api_objects.dataset import rest_operations
from opik.rest_api.core.api_error import ApiError


def _row(item_id: str, *, data: Optional[Dict[str, Any]] = {"q": "?"}) -> bytes:
    return json.dumps({"id": item_id, "source": "sdk", "data": data}).encode() + b"\n"


def _unreadable_row(item_id: str) -> bytes:
    # `data` is not an object, so the generated DatasetItem rejects it.
    return (
        json.dumps({"id": item_id, "source": "sdk", "data": [1, 2, 3]}).encode() + b"\n"
    )


class _RowSource:
    """Serves `rows where id < last_retrieved_id`, newest id first."""

    def __init__(self, rows: List[bytes]) -> None:
        self.rows = rows
        self.requests: List[tuple] = []

    def stream(
        self,
        dataset_name: str,
        project_name: Optional[str],
        last_retrieved_id: Optional[str],
        steam_limit: int,
        filters: Optional[str],
        dataset_version: Optional[str],
    ) -> Iterable[bytes]:
        self.requests.append((steam_limit, last_retrieved_id))
        rows = self.rows
        if last_retrieved_id is not None:
            # `id < :lastRetrievedId` over a newest-first list: everything after
            # the cursor row, never the cursor row itself.
            rows = rows[rows.index(_row_of(last_retrieved_id, rows)) + 1 :]
        return rows[:steam_limit]


def _row_of(item_id: str, rows: List[bytes]) -> bytes:
    for row in rows:
        if json.loads(row)["id"] == item_id:
            return row
    raise AssertionError(f"{item_id} is not in the source")


def _run(rows: List[bytes], batch_size: int = 3):
    source = _RowSource(rows)
    client = Mock()
    client.datasets.stream_dataset_items.side_effect = source.stream

    items = list(
        rest_operations.stream_dataset_items(
            rest_client=client,
            dataset_name="d",
            project_name=None,
            batch_size=batch_size,
        )
    )
    return [item.id for item in items], source.requests


# Nine rows, newest id first, with a full page of unreadable records in the middle.
_ROWS = (
    [_row(f"r{i:02d}") for i in (9, 8, 7)]
    + [_unreadable_row(f"b{i}") for i in (1, 2, 3)]
    + [_row(f"r{i:02d}") for i in (6, 5, 4, 3, 2, 1)]
)


def test_stream_dataset_items__a_page_of_unreadable_records_does_not_end_the_read():
    # On main this stopped after the bad page: `len(dataset_items) == 0` was
    # treated as end-of-stream, so the six good rows behind it were never
    # requested at all and the read reported seven of nine items as complete.
    item_ids, requests = _run(_ROWS)

    assert item_ids == [
        "r09",
        "r08",
        "r07",
        "r06",
        "r05",
        "r04",
        "r03",
        "r02",
        "r01",
    ]
    # The cursor is the last id the backend sent, which on the unreadable page is
    # a row the SDK cannot use -- so the read resumes past it (b3) instead of
    # asking for that page again.
    assert [cursor for _, cursor in requests] == [
        None,
        "r07",
        "b3",
        "r04",
        "r01",
    ]


def test_stream_dataset_items__a_single_unreadable_record_does_not_end_the_read():
    # The same shape but only one bad record, which lands inside a page that
    # still parses to something. This already worked; it must keep working.
    rows = (
        [_row(f"r{i:02d}") for i in (9, 8)]
        + [_unreadable_row("b1")]
        + [_row(f"r{i:02d}") for i in (7, 6, 5, 4, 3)]
    )

    item_ids, _ = _run(rows)

    assert item_ids == ["r09", "r08", "r07", "r06", "r05", "r04", "r03"]


def test_stream_dataset_items__clean_read_is_unaffected():
    rows = [_row(f"r{i:02d}") for i in (6, 5, 4, 3, 2, 1)]

    item_ids, requests = _run(rows)

    assert item_ids == ["r06", "r05", "r04", "r03", "r02", "r01"]
    # Unchanged from main: a page that came back exactly full is not known to be
    # the last one, so the read asks once more and stops on the empty page.
    assert [cursor for _, cursor in requests] == [None, "r04", "r01"]


def test_stream_dataset_items__a_full_page_that_cannot_advance__stops_instead_of_looping():
    # Records the backend sent that are not JSON objects at all leave no id to
    # resume from, so the cursor cannot move. The read has to stop rather than
    # ask for the same page forever.
    rows = [_row(f"r{i:02d}") for i in (9, 8, 7)] + [
        b"{not json at all\n",
        b"also not json\n",
        b"nor this\n",
    ]

    item_ids, requests = _run(rows)

    assert item_ids == ["r09", "r08", "r07"]
    assert len(requests) == 2


@pytest.mark.parametrize("nb_samples", [1, 2, 5])
def test_stream_dataset_items__nb_samples__still_caps_the_read(nb_samples):
    rows = [_row(f"r{i:02d}") for i in (9, 8, 7, 6, 5, 4, 3, 2, 1)]

    client = Mock()
    source = _RowSource(rows)
    client.datasets.stream_dataset_items.side_effect = source.stream

    items = list(
        rest_operations.stream_dataset_items(
            rest_client=client,
            dataset_name="d",
            project_name=None,
            batch_size=3,
            nb_samples=nb_samples,
        )
    )

    assert len(items) == nb_samples


def test_stream_dataset_items__dataset_item_ids__still_filters_and_stops_when_all_found():
    rows = [_row(f"r{i:02d}") for i in (9, 8, 7, 6, 5)]

    client = Mock()
    source = _RowSource(rows)
    client.datasets.stream_dataset_items.side_effect = source.stream

    items = list(
        rest_operations.stream_dataset_items(
            rest_client=client,
            dataset_name="d",
            project_name=None,
            batch_size=2,
            dataset_item_ids=["r07", "r05"],
        )
    )

    assert [item.id for item in items] == ["r07", "r05"]


def test_stream_dataset_items__an_api_error_still_propagates():
    client = Mock()
    client.datasets.stream_dataset_items.side_effect = ApiError(status_code=500)

    with pytest.raises(ApiError):
        list(
            rest_operations.stream_dataset_items(
                rest_client=client,
                dataset_name="d",
                project_name=None,
                batch_size=3,
            )
        )
