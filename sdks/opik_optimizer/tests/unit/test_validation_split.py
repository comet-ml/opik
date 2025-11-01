from __future__ import annotations

import random
from types import SimpleNamespace
from typing import Any, Protocol
from collections.abc import Sequence

import pytest

from opik_optimizer.utils.validation import DatasetSplitResult, ValidationSplit


class DatasetProtocol(Protocol):
    def get_items(self, limit: int | None = None) -> list[dict[str, Any]]: ...

    def train_test_split(self, **kwargs: Any) -> SimpleNamespace: ...


class DummyDataset(DatasetProtocol):
    def __init__(
        self,
        train_items: Sequence[dict[str, Any]],
        test_items: Sequence[dict[str, Any]] | None = None,
    ) -> None:
        self._train: list[dict[str, Any]] = list(train_items)
        self._test: list[dict[str, Any]] | None = (
            list(test_items) if test_items else None
        )

    def get_items(self, limit: int | None = None) -> list[dict[str, Any]]:
        items = list(self._train)
        return items[:limit] if limit is not None else items

    def train_test_split(self, **kwargs: Any) -> SimpleNamespace:
        ratio: float | None = kwargs.get("test_size")
        limit: int | None = kwargs.get("limit")
        seed: int = kwargs.get("seed", 0)

        items = list(self._train)
        if limit is not None:
            items = items[:limit]

        if ratio is None:
            test_items = list(self._test) if self._test is not None else []
            return SimpleNamespace(train=items, test=test_items)

        if not 0 < ratio < 1:
            raise ValueError("ratio must be between 0 and 1.")

        rng = random.Random(seed)
        shuffled = items[:]
        rng.shuffle(shuffled)
        count = max(1, int(round(len(shuffled) * ratio)))
        count = min(count, max(0, len(shuffled) - 1))
        test_items = shuffled[:count]
        train_items = shuffled[count:]
        return SimpleNamespace(train=train_items, test=test_items)


class RecordingDataset(DummyDataset):
    def __init__(
        self,
        train_items: Sequence[dict[str, Any]],
        test_items: Sequence[dict[str, Any]],
    ) -> None:
        super().__init__(train_items, test_items)
        self.call_kwargs: dict[str, Any] | None = None

    def train_test_split(self, **kwargs: Any) -> SimpleNamespace:
        self.call_kwargs = dict(kwargs)
        return super().train_test_split(**kwargs)


def test_validation_split_ratio_builds_datasets() -> None:
    dataset = DummyDataset([{"id": "1"}, {"id": "2"}, {"id": "3"}, {"id": "4"}])
    split = ValidationSplit.from_ratio(0.5, seed=123)

    result = split.build(dataset, n_samples=None, default_seed=0)

    assert isinstance(result, DatasetSplitResult)
    assert len(result.validation_items) == 2
    assert result.train_dataset is dataset
    assert len(result.train_items) == 2
    assert {item["id"] for item in result.train_items}.isdisjoint(
        {item["id"] for item in result.validation_items}
    )


def test_validation_split_dataset_pass_through() -> None:
    validation_dataset = DummyDataset([{"id": "v1"}, {"id": "v2"}])
    dataset = RecordingDataset(
        [{"id": "t1"}, {"id": "t2"}],
        [{"id": "v1"}, {"id": "v2"}],
    )

    split = ValidationSplit.from_dataset(validation_dataset)
    result = split.build(dataset, n_samples=None, default_seed=0)

    assert dataset.call_kwargs is not None
    assert result.train_dataset is dataset
    assert result.validation_dataset is validation_dataset
    assert [item["id"] for item in result.train_items] == ["t1", "t2"]
    assert [item["id"] for item in result.validation_items] == ["v1", "v2"]


def test_validation_split_rejects_multiple_strategies() -> None:
    split = ValidationSplit(
        dataset=DummyDataset([]),
        item_ids=("1",),
    )

    with pytest.raises(ValueError, match="Only one validation split strategy"):
        split.build(DummyDataset([]), n_samples=None, default_seed=0)


def test_validation_split_without_configuration_returns_training_only() -> None:
    dataset = DummyDataset([{"id": "1"}, {"id": "2"}])
    split = ValidationSplit()

    result = split.build(dataset, n_samples=1, default_seed=0)

    assert len(result.train_items) == 1
    assert result.validation_items == []


def test_validation_split_ratio_bounds() -> None:
    dataset = DummyDataset([{"id": "1"}, {"id": "2"}])
    split = ValidationSplit.from_ratio(1.2)

    with pytest.raises(ValueError):
        split.build(dataset, n_samples=None, default_seed=0)
