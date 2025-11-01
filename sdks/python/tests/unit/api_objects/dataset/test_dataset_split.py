from __future__ import annotations

from typing import Any, Dict, List
from unittest.mock import Mock

import pytest

from opik.api_objects.dataset.dataset import Dataset, DatasetSplit


def _make_dataset(items: List[Dict[str, Any]]) -> Dataset:
    dataset = Dataset("test-dataset", "desc", Mock())
    dataset.get_items = Mock(return_value=items)  # type: ignore[attr-defined]
    return dataset


def test_train_test_split_by_ratio_returns_disjoint_sets() -> None:
    items = [{"id": str(i), "input": f"q{i}"} for i in range(10)]
    dataset = _make_dataset(items)

    split = dataset.train_test_split(test_size=0.3, seed=99)

    assert isinstance(split, DatasetSplit)
    assert len(split.test) == 3
    assert len(split.train) == 7
    train_ids = {item["id"] for item in split.train}
    test_ids = {item["id"] for item in split.test}
    assert train_ids.isdisjoint(test_ids)


def test_train_test_split_by_ratio_handles_small_dataset() -> None:
    items = [{"id": "only", "input": "sample"}]
    dataset = _make_dataset(items)

    split = dataset.train_test_split(test_size=0.5, seed=1)

    assert split.test == []
    assert split.train == items


def test_train_test_split_by_field_reads_metadata() -> None:
    items = [
        {"id": "1", "metadata": {"split": "train"}},
        {"id": "2", "metadata": {"split": "test"}},
        {"id": "3", "metadata": {"split": "train"}},
    ]
    dataset = _make_dataset(items)

    split = dataset.train_test_split(split_field="split", test_label="test")

    assert [item["id"] for item in split.test] == ["2"]
    assert {item["id"] for item in split.train} == {"1", "3"}


def test_train_test_split_with_test_ids() -> None:
    items = [{"id": "a"}, {"id": "b"}, {"id": "c"}]
    dataset = _make_dataset(items)

    split = dataset.train_test_split(test_item_ids=["b"])

    assert [item["id"] for item in split.test] == ["b"]
    assert {item["id"] for item in split.train} == {"a", "c"}


def test_train_test_split_raises_if_multiple_strategies() -> None:
    dataset = _make_dataset([{"id": "a"}])

    with pytest.raises(ValueError, match="Only one test split strategy"):
        dataset.train_test_split(
            split_field="split",
            test_size=0.5,
        )


def test_train_test_split_with_test_dataset() -> None:
    train_items = [{"id": "t1"}, {"id": "t2"}]
    test_items = [{"id": "v1"}, {"id": "v2"}]

    dataset = _make_dataset(train_items)
    test_dataset = _make_dataset(test_items)

    split = dataset.train_test_split(test_dataset=test_dataset)

    assert split.train == train_items
    assert split.test == test_items
