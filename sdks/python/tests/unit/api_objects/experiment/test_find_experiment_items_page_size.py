"""Tests for the page size and concurrency of the experiment Compare-view read."""

import threading
import types
from typing import Any, Dict, List, Optional
from unittest.mock import Mock

import pytest

from opik.api_objects import constants
from opik.api_objects.experiment import (
    experiment as experiment_module,
    experiments_client as experiments_client_module,
    rest_operations,
)


class _RecordingDatasetsClient:
    """Serves ``total`` dataset items, one experiment item each, in pages."""

    def __init__(self, total: int) -> None:
        self._total = total
        self.reported_total = total
        self._lock = threading.Lock()
        self.requested_sizes: List[int] = []
        self.requested_pages: List[int] = []

    def find_dataset_items_with_experiment_items(
        self,
        *,
        id: str,
        page: int,
        size: int,
        experiment_ids: str,
        truncate: bool,
        filters: Optional[str],
    ) -> Any:
        with self._lock:
            self.requested_sizes.append(size)
            self.requested_pages.append(page)
        start = (page - 1) * size
        content = [
            _dataset_item(index)
            for index in range(start, min(start + size, self._total))
        ]
        return types.SimpleNamespace(content=content, total=self.reported_total)


def _dataset_item(index: int) -> Any:
    compare = types.SimpleNamespace(
        id=f"experiment-item-{index}",
        trace_id=f"trace-{index}",
        dataset_item_id=f"dataset-item-{index}",
        input=None,
        output=None,
        feedback_scores=None,
        assertion_results=None,
    )
    return types.SimpleNamespace(
        id=f"dataset-item-{index}",
        data={"index": index},
        experiment_items=[compare],
    )


def _read(total: int, **kwargs: Any) -> Dict[str, Any]:
    datasets_client = _RecordingDatasetsClient(total)
    rest_client = types.SimpleNamespace(datasets=datasets_client)

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        **kwargs,
    )
    return {
        "items": items,
        "requested_sizes": datasets_client.requested_sizes,
        "requested_pages": datasets_client.requested_pages,
    }


def test_find_experiment_items_for_dataset__default_page_size_is_the_constant():
    result = _read(total=2500, max_results=2500)

    assert result["requested_sizes"] == [constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE] * 3
    assert len(result["items"]) == 2500


def test_find_experiment_items_for_dataset__page_size_override_is_used():
    result = _read(total=250, max_results=250, page_size=100)

    assert result["requested_sizes"] == [100, 100, 100]
    assert len(result["items"]) == 250


def test_find_experiment_items_for_dataset__max_results_still_truncates():
    result = _read(total=2500, max_results=1500)

    ids = [item.id for item in result["items"]]
    assert ids == [f"experiment-item-{index}" for index in range(1500)]


def test_find_experiment_items_for_dataset__pages_are_assembled_in_page_order():
    result = _read(total=1000, max_results=1000, page_size=100, num_threads=8)

    ids = [item.id for item in result["items"]]
    assert ids == [f"experiment-item-{index}" for index in range(1000)]
    assert sorted(result["requested_pages"]) == list(range(1, 11))


def test_find_experiment_items_for_dataset__single_thread_reads_pages_in_order():
    result = _read(total=1000, max_results=1000, page_size=100, num_threads=1)

    assert result["requested_pages"] == list(range(1, 11))
    assert len(result["items"]) == 1000


def test_find_experiment_items_for_dataset__does_not_fetch_pages_it_does_not_need():
    result = _read(total=10000, max_results=250, page_size=100, num_threads=8)

    assert sorted(result["requested_pages"]) == [1, 2, 3]
    assert len(result["items"]) == 250


def test_find_experiment_items_for_dataset__stops_at_the_last_page():
    result = _read(total=150, max_results=10000, page_size=100, num_threads=8)

    assert sorted(result["requested_pages"]) == [1, 2]
    assert len(result["items"]) == 150


class _BarrierDatasetsClient(_RecordingDatasetsClient):
    """Blocks every page until ``parties`` of them are in flight at once."""

    def __init__(self, total: int, parties: int) -> None:
        super().__init__(total)
        self._barrier = threading.Barrier(parties, timeout=10)
        self.barrier_broke = False

    def find_dataset_items_with_experiment_items(self, **kwargs: Any) -> Any:
        page = super().find_dataset_items_with_experiment_items(**kwargs)
        if kwargs["page"] > 1:
            try:
                self._barrier.wait()
            except threading.BrokenBarrierError:
                self.barrier_broke = True
        return page


def test_find_experiment_items_for_dataset__pages_after_the_first_overlap():
    # A sequential implementation cannot reach the barrier's party count, so it
    # times out and this fails rather than silently passing.
    datasets_client = _BarrierDatasetsClient(total=500, parties=4)
    rest_client = types.SimpleNamespace(datasets=datasets_client)

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        max_results=500,
        page_size=100,
        num_threads=4,
    )

    assert not datasets_client.barrier_broke
    assert [item.id for item in items] == [
        f"experiment-item-{index}" for index in range(500)
    ]


@pytest.mark.parametrize("total", [None, "many", -1])
def test_find_experiment_items_for_dataset__unusable_total_falls_back_to_walking(total):
    datasets_client = _RecordingDatasetsClient(250)
    datasets_client.reported_total = total
    rest_client = types.SimpleNamespace(datasets=datasets_client)

    items = rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id="some-dataset-id",
        experiment_ids=["some-experiment-id"],
        truncate=False,
        max_results=10000,
        page_size=100,
    )

    assert len(items) == 250


def _experiment(experiments_client: Any) -> experiment_module.Experiment:
    return experiment_module.Experiment(
        id="some-experiment-id",
        name="some-experiment",
        dataset_name="some-dataset",
        rest_client=Mock(),
        streamer=Mock(),
        experiments_client=experiments_client,
    )


@pytest.mark.parametrize(
    "page_size",
    [0, -1, 1.5, True, None],
)
def test_get_items__rejects_a_page_size_that_is_not_a_positive_integer(page_size):
    experiment = _experiment(Mock())

    with pytest.raises(ValueError, match="page_size must be a positive integer"):
        experiment.get_items(page_size=page_size)


def test_get_items__rejects_a_page_size_above_the_cap():
    experiment = _experiment(Mock())
    over_cap = constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE + 1

    with pytest.raises(
        ValueError,
        match=(
            f"page_size must not exceed "
            f"{constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE}, got {over_cap}"
        ),
    ):
        experiment.get_items(page_size=over_cap)


def test_get_items__accepts_a_page_size_at_the_cap():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items(
        page_size=constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
    )

    assert (
        experiments_client.find_experiment_items_for_dataset.call_args.kwargs[
            "page_size"
        ]
        == constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
    )


@pytest.mark.parametrize(
    "num_threads",
    [0, -1, 1.5, True, None],
)
def test_get_items__rejects_a_num_threads_that_is_not_a_positive_integer(num_threads):
    experiment = _experiment(Mock())

    with pytest.raises(ValueError, match="num_threads must be a positive integer"):
        experiment.get_items(num_threads=num_threads)


def test_get_items__rejects_a_num_threads_above_the_cap():
    experiment = _experiment(Mock())
    over_cap = constants.DATASET_ITEMS_READ_MAX_THREADS + 1

    with pytest.raises(
        ValueError,
        match=(
            f"num_threads must not exceed "
            f"{constants.DATASET_ITEMS_READ_MAX_THREADS}, got {over_cap}"
        ),
    ):
        experiment.get_items(num_threads=over_cap)


def test_get_items__accepts_a_num_threads_at_the_cap():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items(
        num_threads=constants.DATASET_ITEMS_READ_MAX_THREADS
    )

    assert (
        experiments_client.find_experiment_items_for_dataset.call_args.kwargs[
            "num_threads"
        ]
        == constants.DATASET_ITEMS_READ_MAX_THREADS
    )


def test_get_items__defaults_to_the_page_size_and_thread_constants():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items()

    kwargs = experiments_client.find_experiment_items_for_dataset.call_args.kwargs
    assert kwargs["page_size"] == constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE
    assert kwargs["num_threads"] == constants.DATASET_ITEMS_READ_NUM_THREADS


@pytest.mark.parametrize(
    "kwargs,message",
    [
        ({"page_size": 0}, "page_size must be a positive integer"),
        ({"page_size": 10**9}, "page_size must not exceed"),
        ({"num_threads": None}, "num_threads must be a positive integer"),
        ({"num_threads": 10**9}, "num_threads must not exceed"),
    ],
)
def test_experiments_client__validates_before_touching_the_rest_client(kwargs, message):
    rest_client = Mock()
    client = experiments_client_module.ExperimentsClient(rest_client)

    with pytest.raises(ValueError, match=message):
        client.find_experiment_items_for_dataset(
            dataset_name="some-dataset",
            experiment_ids=["some-experiment-id"],
            **kwargs,
        )

    rest_client.datasets.get_dataset_by_identifier.assert_not_called()
