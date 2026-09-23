"""`Experiment.get_items` against a real backend, over several pages.

The unit tests drive the read through a stand-in for the datasets client, so they
assert the paging arithmetic and nothing about the request the REST client actually
sends or the Compare response it parses. A `page_size` the backend ignores, a page
number it reads differently, or a response shape the parser mishandles passes every
one of them. This reads a real experiment at a deliberately small page size, with
pages fetched concurrently, and checks the items come back complete, once each, and
in the order a sequential read produces.
"""

import datetime
import threading
from typing import Any, Dict, List, Optional, Tuple

import pytest

import opik
from opik import synchronization
from opik.api_objects.dataset import dataset as dataset_module
from opik.api_objects.experiment import (
    experiment as experiment_module,
    rest_operations,
)

from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)

_START = datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)
_END = _START + datetime.timedelta(seconds=1)

# 150 items at a 25-item page size is 6 pages: page 1 alone, then a full wave of
# 4 workers and a short one, so the concurrent path is really exercised.
ITEM_COUNT = 150
PAGE_SIZE = 25
NUM_THREADS = 4


def _create_dataset(
    opik_client: opik.Opik, name: str, item_count: int
) -> Tuple[dataset_module.Dataset, Dict[int, Dict[str, Any]]]:
    """A dataset of `item_count` items, each as the dataset read returns it.

    Keyed by the item's index. The items are the read's own dictionaries -- the
    item's data plus its `id` -- which is the shape the experiment read has to
    reproduce in `dataset_item_data`.
    """
    dataset = opik_client.create_dataset(name, project_name=PROJECT_NAME)
    dataset.insert({"input": {"index": index}} for index in range(item_count))

    items: List[Dict[str, Any]] = []

    def _all_readable() -> bool:
        nonlocal items
        items = dataset.get_items()
        return len(items) == item_count

    assert synchronization.until(_all_readable, max_try_seconds=60), (
        f"Only {len(items)} of {item_count} dataset items became readable"
    )
    return dataset, {item["input"]["index"]: item for item in items}


def _upload_items(
    experiment: experiment_module.Experiment, ids_by_index: Dict[int, str]
) -> None:
    experiment.batch_upload_items(
        [
            opik.ExperimentItemBulkRecord(
                dataset_item_id=item_id,
                trace=opik.ExperimentItemBulkTrace(
                    name="read-trace",
                    start_time=_START,
                    end_time=_END,
                    input={"index": index},
                    output={"answer": f"answer {index}"},
                ),
            )
            for index, item_id in ids_by_index.items()
        ],
        project_name=PROJECT_NAME,
    )

    read = 0

    def _readable() -> bool:
        nonlocal read
        read = len(
            experiment.get_items(max_results=ITEM_COUNT * 2, page_size=PAGE_SIZE)
        )
        return read >= ITEM_COUNT

    if not synchronization.until(_readable, max_try_seconds=60, allow_errors=True):
        # Errors are tolerated *while* polling, as everywhere else in this suite: the
        # upload is eventually consistent, so an early read can legitimately fail.
        # They must not be tolerated on timeout, though -- a read that was raising
        # would otherwise be reported as one that merely returned too few rows. Re-run
        # outside the suppression so the real exception, with its traceback, reaches
        # pytest; the count is the message only if the read now succeeds.
        _readable()
        raise AssertionError(
            f"Only {read} of {ITEM_COUNT} experiment items became readable"
        )


class _RecordedRequests:
    """The `(page, size)` of every Compare request the read actually sent.

    Recorded at the HTTP client the REST client wraps, not at the generated datasets
    client: the read parses the endpoint's JSON itself and never calls
    `find_dataset_items_with_experiment_items`, so patching that seam would watch a
    method nothing invokes and record nothing at all.

    The client is reached through `rest_operations.http_client`, the same accessor the
    read itself uses, so the test records whatever the production path sends rather
    than a second guess at where that client lives.
    """

    #: Only the Compare page endpoint. Its `/stats` and `/output/columns` siblings
    #: share the prefix, so match the end of the path rather than the start.
    _COMPARE_PATH = "/items/experiments/items"

    def __init__(self, opik_client: opik.Opik, monkeypatch: pytest.MonkeyPatch) -> None:
        http_client = rest_operations.http_client(opik_client._rest_client)
        original = http_client.request
        self._lock = threading.Lock()
        self.calls: List[Tuple[int, int]] = []

        def _recording(path: Optional[str] = None, **kwargs: Any) -> Any:
            if path is not None and path.endswith(self._COMPARE_PATH):
                params = kwargs.get("params") or {}
                with self._lock:
                    self.calls.append((params["page"], params["size"]))
            return original(path, **kwargs)

        monkeypatch.setattr(http_client, "request", _recording)

    def reset(self) -> None:
        with self._lock:
            self.calls = []


def test_get_items__small_page_size_and_several_threads__reads_every_item_once_in_order(
    opik_client: opik.Opik,
    dataset_name: str,
    experiment_name: str,
    monkeypatch: pytest.MonkeyPatch,
):
    dataset, items_by_index = _create_dataset(opik_client, dataset_name, ITEM_COUNT)
    ids_by_index = {index: item["id"] for index, item in items_by_index.items()}
    experiment = opik_client.create_experiment(
        dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
    )
    _upload_items(experiment, ids_by_index)

    recorded = _RecordedRequests(opik_client, monkeypatch)
    threaded = experiment.get_items(
        max_results=ITEM_COUNT, page_size=PAGE_SIZE, num_threads=NUM_THREADS
    )

    # The page size reached the backend: it answered in pages of that size, and
    # `total` bounded the read to exactly the pages the items span.
    assert {size for _, size in recorded.calls} == {PAGE_SIZE}
    assert sorted(page for page, _ in recorded.calls) == [1, 2, 3, 4, 5, 6]

    assert len(threaded) == ITEM_COUNT
    dataset_item_ids = [item.dataset_item_id for item in threaded]
    assert set(dataset_item_ids) == set(ids_by_index.values())
    assert len(set(dataset_item_ids)) == ITEM_COUNT, "an item came back twice"

    # Content survives the Compare response, not merely the row count.
    index_by_id = {item_id: index for index, item_id in ids_by_index.items()}
    for item in threaded:
        index = index_by_id[item.dataset_item_id]
        assert item.evaluation_task_output == {"answer": f"answer {index}"}
        # `dataset_item_data` is the Compare row's `data` with the dataset item's own
        # id folded in by `_collect_page`. The dataset read hands back exactly that --
        # the item's data plus its id -- so the two reads must agree item for item,
        # down to the id only the experiment read adds.
        assert item.dataset_item_data == items_by_index[index]
        assert item.dataset_item_data["input"] == {"index": index}
        assert item.dataset_item_data["id"] == item.dataset_item_id

    # Same order as a sequential read of the same pages -- whatever order the
    # backend pages in, concurrency must not change it.
    recorded.reset()
    sequential = experiment.get_items(
        max_results=ITEM_COUNT, page_size=PAGE_SIZE, num_threads=1
    )
    assert [page for page, _ in recorded.calls] == [1, 2, 3, 4, 5, 6]
    assert [item.id for item in sequential] == [item.id for item in threaded]

    # `max_results` cuts that same sequence short rather than returning a
    # different one, and stops the read at the pages it needs.
    recorded.reset()
    truncated = experiment.get_items(
        max_results=60, page_size=PAGE_SIZE, num_threads=NUM_THREADS
    )
    assert [item.id for item in truncated] == [item.id for item in threaded[:60]]
    assert sorted(page for page, _ in recorded.calls) == [1, 2, 3]
