"""Tests for the page size the experiment Compare-view read uses."""

import types
from typing import Any, Dict, List, Optional
from unittest.mock import Mock

import pytest

from opik.api_objects import constants
from opik.api_objects.experiment import (
    experiment as experiment_module,
    rest_operations,
)


class _RecordingDatasetsClient:
    """Serves ``total`` dataset items, one experiment item each, in pages."""

    def __init__(self, total: int) -> None:
        self._total = total
        self.requested_sizes: List[int] = []

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
        self.requested_sizes.append(size)
        start = (page - 1) * size
        content = [
            _dataset_item(index)
            for index in range(start, min(start + size, self._total))
        ]
        return types.SimpleNamespace(content=content)


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
    return {"items": items, "requested_sizes": datasets_client.requested_sizes}


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


def test_get_items__defaults_to_the_page_size_constant():
    experiments_client = Mock()
    experiments_client.find_experiment_items_for_dataset.return_value = []

    _experiment(experiments_client).get_items()

    assert (
        experiments_client.find_experiment_items_for_dataset.call_args.kwargs[
            "page_size"
        ]
        == constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE
    )
