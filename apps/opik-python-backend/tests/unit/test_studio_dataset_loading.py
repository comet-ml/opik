"""Unit tests for dataset loading and the item count that sizes the mini-batch.

The count feeds GEPA's reflection mini-batch, so it must match what the SDK will
actually train on — and every failure to read the dataset must arrive as the
typed error the caller documents.
"""

from unittest.mock import MagicMock

import pytest

from opik_backend.studio.config import DATASET_SAMPLES
from opik_backend.studio.exceptions import DatasetNotFoundError, EmptyDatasetError
from opik_backend.studio.helpers import (
    count_optimizable_items,
    load_and_validate_dataset,
)


def _client(items=None, *, get_dataset_error=None, get_items_error=None):
    client = MagicMock()
    if get_dataset_error is not None:
        client.get_dataset.side_effect = get_dataset_error
        return client
    dataset = MagicMock()
    if get_items_error is not None:
        dataset.get_items.side_effect = get_items_error
    else:
        dataset.get_items.return_value = items if items is not None else []
    client.get_dataset.return_value = dataset
    return client


class TestCountOptimizableItems:
    """The SDK's sampling drops rows without an id, so counting them would size
    the mini-batch above the real trainset."""

    def test_counts_only_items_with_an_id(self):
        items = [{"id": "1"}, {"id": None}, {"id": "2"}, {"no_id": True}]
        assert count_optimizable_items(items) == 2

    def test_empty_and_non_dict_rows_are_ignored(self):
        assert count_optimizable_items([]) == 0
        assert count_optimizable_items(["oops", None, 42]) == 0


class TestLoadAndValidateDataset:
    def test_returns_dataset_and_optimizable_count(self):
        client = _client([{"id": "1"}, {"id": "2"}, {"id": None}])

        dataset, count = load_and_validate_dataset(client, "ds")

        assert dataset is client.get_dataset.return_value
        assert count == 2

    def test_fetch_is_bounded_to_dataset_samples(self):
        client = _client([{"id": "1"}])

        load_and_validate_dataset(client, "ds")

        client.get_dataset.return_value.get_items.assert_called_once_with(
            nb_samples=DATASET_SAMPLES
        )

    def test_missing_dataset_raises_typed_error(self):
        client = _client(get_dataset_error=RuntimeError("404 not found"))

        with pytest.raises(DatasetNotFoundError):
            load_and_validate_dataset(client, "ds")

    def test_item_fetch_failure_also_raises_typed_error(self):
        """Access/transport failures on the item fetch are just as much
        "dataset unusable" — they must not escape as a raw exception."""
        client = _client(get_items_error=ConnectionError("connection reset"))

        with pytest.raises(DatasetNotFoundError):
            load_and_validate_dataset(client, "ds")

    def test_empty_dataset_raises_empty_error_not_not_found(self):
        client = _client([])

        with pytest.raises(EmptyDatasetError):
            load_and_validate_dataset(client, "ds")

    def test_items_without_ids_are_not_empty_but_count_zero(self):
        """Pathological but must not crash: the dataset has rows, none usable."""
        client = _client([{"no_id": 1}])

        dataset, count = load_and_validate_dataset(client, "ds")

        assert dataset is not None
        assert count == 0
