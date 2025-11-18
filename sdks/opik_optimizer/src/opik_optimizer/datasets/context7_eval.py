from __future__ import annotations

import json
from dataclasses import dataclass
from importlib import resources
from typing import Any, Union

try:  # pragma: no cover - optional dependency
    import opik  # type: ignore
except ImportError:  # pragma: no cover - fallback for tests
    opik = None

from opik_optimizer.utils.dataset_utils import attach_uuids, dataset_suffix

OpikDataset = Any

DATA_PACKAGE = "opik_optimizer.data"
DATA_FILENAME = "context7_eval.jsonl"
DATASET_NAME = "context7_eval_train"


def _load_examples() -> list[dict[str, Any]]:
    text = (
        resources.files(DATA_PACKAGE)
        .joinpath(DATA_FILENAME)
        .read_text(encoding="utf-8")
    )
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def _dataset_name(test_mode: bool) -> str:
    suffix = dataset_suffix(DATA_PACKAGE, DATA_FILENAME)
    return f"{DATASET_NAME}_{suffix}{'_test' if test_mode else ''}"


@dataclass
class _ListDataset:
    name: str
    _items: list[dict[str, Any]]

    def __post_init__(self) -> None:
        for idx, item in enumerate(self._items):
            item.setdefault("id", f"{self.name}-{idx}")
        self.id = self.name

    def copy(self) -> _ListDataset:
        return _ListDataset(self.name, [dict(item) for item in self._items])

    def get_items(self, nb_samples: int | None = None) -> list[dict[str, Any]]:
        if nb_samples is None:
            return [dict(item) for item in self._items]
        return [dict(item) for item in self._items[:nb_samples]]


DatasetResult = Union["_ListDataset", OpikDataset]


def load_context7_dataset(test_mode: bool = False) -> DatasetResult:
    """Return the context7 synthetic dataset as an Opik dataset when available."""

    examples = _load_examples()
    dataset_name = _dataset_name(test_mode)

    if opik is None:
        return _ListDataset(dataset_name, examples)

    try:
        client = opik.Opik()
        dataset: OpikDataset = client.get_or_create_dataset(dataset_name)
        items = dataset.get_items()
        expected_len = len(examples) if not test_mode else min(len(examples), 2)

        if len(items) == expected_len:
            return dataset
        if len(items) != 0:  # pragma: no cover - defensive path
            raise ValueError(
                f"Dataset {dataset_name} already exists with {len(items)} items. Delete it to regenerate."
            )

        if test_mode:
            dataset.insert(attach_uuids(examples[:expected_len]))
        else:
            dataset.insert(attach_uuids(examples))
        return dataset
    except Exception:
        # If Opik client fails (e.g., no API key configured), fall back to local dataset
        return _ListDataset(dataset_name, examples)


def context7_eval(test_mode: bool = False) -> OpikDataset:
    dataset = load_context7_dataset(test_mode=test_mode)
    if isinstance(dataset, _ListDataset):
        raise RuntimeError(
            "Opik client is not available; context7_eval requires Opik dataset support."
        )
    return dataset


__all__ = ["load_context7_dataset", "context7_eval"]
