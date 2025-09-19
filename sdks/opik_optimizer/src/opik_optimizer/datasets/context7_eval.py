from __future__ import annotations

import json
from dataclasses import dataclass
from importlib import resources
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

try:  # pragma: no cover - optional dependency
    import opik  # type: ignore
except ImportError:  # pragma: no cover - fallback for tests
    opik = None


DATA_FILENAME = "context7_eval.jsonl"
DATASET_NAME = "context7_eval"


def _load_examples() -> List[Dict[str, Any]]:
    text = (
        resources.files("opik_optimizer.data").joinpath(DATA_FILENAME).read_text(
            encoding="utf-8"
        )
    )
    return [json.loads(line) for line in text.splitlines() if line.strip()]


@dataclass
class _ListDataset:
    name: str
    _items: List[Dict[str, Any]]

    def __post_init__(self) -> None:
        for idx, item in enumerate(self._items):
            item.setdefault("id", f"{self.name}-{idx}")
        self.id = self.name

    def copy(self) -> "_ListDataset":
        return _ListDataset(self.name, [dict(item) for item in self._items])

    def get_items(self, nb_samples: Optional[int] = None) -> List[Dict[str, Any]]:
        if nb_samples is None:
            return [dict(item) for item in self._items]
        return [dict(item) for item in self._items[:nb_samples]]


def load_context7_dataset(test_mode: bool = False):
    """Return the context7 synthetic dataset as an Opik dataset when available."""

    examples = _load_examples()
    if opik is None:
        suffix = "_test" if test_mode else ""
        return _ListDataset(f"{DATASET_NAME}{suffix}", examples)

    dataset_name = f"{DATASET_NAME}{'_test' if test_mode else ''}"
    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    items = dataset.get_items()
    expected_len = len(examples) if not test_mode else min(len(examples), 2)

    if len(items) == expected_len:
        return dataset
    if len(items) != 0:  # pragma: no cover - defensive path
        raise ValueError(
            f"Dataset {dataset_name} already exists with {len(items)} items. Delete it to regenerate."
        )

    if test_mode:
        dataset.insert(examples[:expected_len])
    else:
        dataset.insert(examples)
    return dataset


__all__ = ["load_context7_dataset"]
