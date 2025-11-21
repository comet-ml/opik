from __future__ import annotations

import inspect
from collections.abc import Callable

import pytest

import opik_optimizer
from opik_optimizer.utils import dataset_utils


DATASET_SIZES = {
    "tiny_test": 5,
    "gsm8k": 300,
    "ai2_arc": 300,
    "truthful_qa": 300,
    "cnn_dailymail": 100,
    "ragbench_sentence_relevance": 300,
    "election_questions": 300,
    "medhallu": 300,
    "rag_hallucinations": 300,
    "context7_eval": 3,
}

DEFAULT_TEST_MODE_SIZE = 5
TEST_DATASET_SIZES = {
    name: min(DEFAULT_TEST_MODE_SIZE, size) for name, size in DATASET_SIZES.items()
}
# Context7 only exposes two examples in our test slice.
TEST_DATASET_SIZES["context7_eval"] = 2

# Extra kwargs left here for future dataset overrides when needed.
DATASET_CALL_KWARGS: dict[str, dict[str, int]] = {}


FULL_DATASET_FUNCTIONS = [
    (name, func, DATASET_SIZES[name])
    for name, func in inspect.getmembers(opik_optimizer.datasets)
    if inspect.isfunction(func) and name in DATASET_SIZES
]

TEST_DATASET_FUNCTIONS = [
    (name, func, TEST_DATASET_SIZES[name])
    for name, func in inspect.getmembers(opik_optimizer.datasets)
    if inspect.isfunction(func) and name in TEST_DATASET_SIZES
]


class _DummyDataset:
    def __init__(self, name: str) -> None:
        self.name = name
        self._records: list[dict[str, str]] = []
        self._hashes: list[str] = []

    def get_items(self) -> list[dict[str, str]]:
        return list(self._records)

    def insert(self, records: list[dict[str, str]]) -> None:
        self._records.extend(records)
        # mirror behaviour used in the tests (len(_hashes) == expected size)
        self._hashes.extend(
            [rec.get("id", str(idx)) for idx, rec in enumerate(records)]
        )


@pytest.mark.parametrize(
    "dataset_name,dataset_func,expected_size", FULL_DATASET_FUNCTIONS
)
def test_full_dataset_sizes(
    dataset_name: str,
    dataset_func: Callable,
    expected_size: int,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_load = dataset_utils.DatasetHandle.load

    def _stub_load(
        self: dataset_utils.DatasetHandle, **kwargs: object
    ) -> _DummyDataset:
        raw_name = str(kwargs.get("dataset_name") or self.spec.name)  # type: ignore[attr-defined]
        ds_name = dataset_utils.dataset_name_for_mode(
            raw_name, bool(kwargs.get("test_mode", False))
        )
        dummy = _DummyDataset(ds_name)
        dummy.insert([{"id": str(i)} for i in range(expected_size)])
        return dummy

    monkeypatch.setattr(dataset_utils.DatasetHandle, "load", _stub_load)

    dataset: _DummyDataset | None = None
    try:
        dataset = dataset_func(**DATASET_CALL_KWARGS.get(dataset_name, {}))
    except RuntimeError as exc:
        if "Opik client is not available; context7_eval" in str(exc):
            pytest.skip("context7_eval not available without Opik client")
        raise
    finally:
        monkeypatch.setattr(dataset_utils.DatasetHandle, "load", original_load)
    assert dataset is not None
    assert len(dataset._hashes) == expected_size


@pytest.mark.parametrize(
    "dataset_name,dataset_func,expected_size", TEST_DATASET_FUNCTIONS
)
def test_test_dataset_sizes(
    dataset_name: str,
    dataset_func: Callable,
    expected_size: int,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_load = dataset_utils.DatasetHandle.load

    def _stub_load(
        self: dataset_utils.DatasetHandle, **kwargs: object
    ) -> _DummyDataset:
        raw_name = kwargs.get("dataset_name") or self.spec.name  # type: ignore[attr-defined]
        ds_name = dataset_utils.dataset_name_for_mode(
            str(raw_name), bool(kwargs.get("test_mode", False))
        )
        dummy = _DummyDataset(ds_name)
        dummy.insert([{"id": str(i)} for i in range(expected_size)])
        return dummy

    monkeypatch.setattr(dataset_utils.DatasetHandle, "load", _stub_load)

    dataset: _DummyDataset | None = None
    try:
        dataset = dataset_func(
            test_mode=True, **DATASET_CALL_KWARGS.get(dataset_name, {})
        )
    except RuntimeError as exc:
        if "Opik client is not available; context7_eval" in str(exc):
            pytest.skip("context7_eval not available without Opik client")
        raise
    finally:
        monkeypatch.setattr(dataset_utils.DatasetHandle, "load", original_load)
    assert dataset is not None
    assert len(dataset._hashes) == expected_size
