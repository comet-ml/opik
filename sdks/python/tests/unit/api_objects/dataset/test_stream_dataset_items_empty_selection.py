"""An explicit empty ``dataset_item_ids`` selects nothing, it is not "no filter".

Regression for https://github.com/comet-ml/opik/issues/9540: a fresh resume
checkpoint that resolved to zero items used to be treated as ``None`` by
``stream_dataset_items`` and replay every item in the dataset. The fake transport
below mirrors the backend's cursor pagination so these tests observe what a real
stream hands back, rather than a pre-baked page list.
"""

import json
from typing import Iterable, List, Optional
from unittest.mock import Mock

from opik.api_objects.dataset import rest_operations


def _row(item_id: str) -> bytes:
    return (
        json.dumps({"id": item_id, "source": "sdk", "data": {"q": "?"}}).encode()
        + b"\n"
    )


class _CursorTransport:
    """Serve ``rows where id < last_retrieved_id``, newest first, page-limited."""

    def __init__(self, rows: List[bytes]) -> None:
        self.rows = rows

    def stream(
        self,
        dataset_name: str,
        project_name: Optional[str],
        last_retrieved_id: Optional[str],
        steam_limit: int,
        filters: Optional[str],
        dataset_version: Optional[str],
    ) -> Iterable[bytes]:
        rows = self.rows
        if last_retrieved_id is not None:
            index = next(
                i
                for i, row in enumerate(rows)
                if json.loads(row)["id"] == last_retrieved_id
            )
            rows = rows[index + 1 :]
        return rows[:steam_limit]


def _stream_ids(selection: Optional[List[str]]) -> List[str]:
    transport = _CursorTransport([_row(f"r{i}") for i in (5, 4, 3, 2, 1)])
    client = Mock()
    client.datasets.stream_dataset_items.side_effect = transport.stream

    items = list(
        rest_operations.stream_dataset_items(
            rest_client=client,
            dataset_name="d",
            project_name=None,
            batch_size=2,
            dataset_item_ids=selection,
        )
    )
    return [item.id for item in items]


def test_explicit_empty_selection_yields_no_items():
    # On the unfixed source, ``set([]) if [] else None`` collapses to ``None``
    # ("no filter") and the whole dataset is evaluated. An explicit empty list
    # must instead filter out every item.
    assert _stream_ids([]) == []


def test_none_selection_still_yields_all_items():
    # ``None`` remains the "no ID filter" signal; the fix must not touch it.
    assert _stream_ids(None) == ["r5", "r4", "r3", "r2", "r1"]


def test_nonempty_selection_yields_requested_subset():
    # A real selection keeps working: only the pinned ids are yielded, across
    # more than one page.
    assert _stream_ids(["r4", "r1"]) == ["r4", "r1"]
