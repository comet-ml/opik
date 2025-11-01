from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from opik_optimizer.utils import ValidationSplit


class DummyDataset:
    def __init__(self, train_items, test_items=None):
        self._train = train_items
        self._test = test_items

    def get_items(self, limit=None):
        items = list(self._train)
        return items[:limit] if limit is not None else items

    def train_test_split(self, **kwargs):
        if self._test is None:
            return SimpleNamespace(train=list(self._train), test=[])
        return SimpleNamespace(train=list(self._train), test=list(self._test))


def test_validation_split_ratio_resolves_datasets() -> None:
    dataset = DummyDataset(
        [{"id": "1"}, {"id": "2"}, {"id": "3"}, {"id": "4"}]
    )
    split = ValidationSplit.from_ratio(0.5, seed=123)

    train_items, val_items = split.resolve(
        dataset, n_samples=None, default_seed=0
    )

    assert len(val_items) == 2
    assert len(train_items) == 2
    assert {item["id"] for item in train_items}.isdisjoint(
        {item["id"] for item in val_items}
    )


def test_validation_split_dataset_pass_through() -> None:
    validation_dataset = DummyDataset([{"id": "v1"}, {"id": "v2"}])
    dataset = DummyDataset([{"id": "t1"}, {"id": "t2"}])

    split = ValidationSplit.from_dataset(validation_dataset)
    dataset.train_test_split = Mock(  # type: ignore[attr-defined]
        return_value=SimpleNamespace(
            train=[{"id": "t1"}, {"id": "t2"}],
            test=[{"id": "v1"}, {"id": "v2"}],
        )
    )

    train_items, val_items = split.resolve(
        dataset, n_samples=None, default_seed=0
    )

    dataset.train_test_split.assert_called_once()
    assert [item["id"] for item in train_items] == ["t1", "t2"]
    assert [item["id"] for item in val_items] == ["v1", "v2"]


def test_validation_split_rejects_multiple_strategies() -> None:
    split = ValidationSplit(
        dataset=Mock(),
        item_ids=("1",),
    )

    with pytest.raises(ValueError, match="Only one validation split strategy"):
        split.resolve(DummyDataset([]), n_samples=None, default_seed=0)


def test_validation_split_without_configuration_returns_training_only() -> None:
    dataset = DummyDataset([{"id": "1"}, {"id": "2"}])
    split = ValidationSplit()

    train_items, val_items = split.resolve(dataset, n_samples=1, default_seed=0)

    assert len(train_items) == 1
    assert val_items == []
