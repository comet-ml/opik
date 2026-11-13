from __future__ import annotations

from importlib import import_module
from types import SimpleNamespace
from typing import Any

import pytest

context7 = import_module("opik_optimizer.datasets.context7_eval")


@pytest.mark.parametrize("test_mode,expected_size", [(False, 3), (True, 2)])
def test_offline_fallback_honors_test_mode(
    monkeypatch: pytest.MonkeyPatch, test_mode: bool, expected_size: int
) -> None:
    def unavailable() -> None:
        raise RuntimeError("client unavailable")

    monkeypatch.setattr(context7.opik, "Opik", unavailable)
    dataset = context7.load_context7_dataset(test_mode=test_mode)
    assert isinstance(dataset, context7._ListDataset)
    assert len(dataset.get_items()) == expected_size
    assert all(item["id"] for item in dataset.get_items())
    if test_mode:
        assert dataset.name.endswith("_test")


@pytest.mark.parametrize("test_mode,expected_size", [(False, 3), (True, 2)])
def test_bundled_examples_are_inserted_into_an_empty_dataset(
    monkeypatch: pytest.MonkeyPatch, test_mode: bool, expected_size: int
) -> None:
    records: list[dict[str, Any]] = []
    dataset = SimpleNamespace(get_items=lambda: list(records), insert=records.extend)
    monkeypatch.setattr(
        context7.opik,
        "Opik",
        lambda: SimpleNamespace(get_or_create_dataset=lambda name: dataset),
    )
    result = context7.context7_eval(test_mode=test_mode)
    assert result is dataset
    assert len(records) == expected_size
    assert [
        {key: value for key, value in item.items() if key != "id"} for item in records
    ] == [
        {key: value for key, value in item.items() if key != "id"}
        for item in context7._load_examples()[:expected_size]
    ]


def test_existing_complete_dataset_is_reused(monkeypatch: pytest.MonkeyPatch) -> None:
    def unexpected_insert(records: Any) -> None:
        raise AssertionError("complete dataset should not be reinserted")

    dataset = SimpleNamespace(
        get_items=context7._load_examples, insert=unexpected_insert
    )
    monkeypatch.setattr(
        context7.opik,
        "Opik",
        lambda: SimpleNamespace(get_or_create_dataset=lambda name: dataset),
    )
    assert context7.context7_eval() is dataset
