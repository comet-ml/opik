from __future__ import annotations

import hashlib
import secrets
import time
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from typing import TYPE_CHECKING, Any
from collections.abc import Iterable, Sequence

if TYPE_CHECKING:  # pragma: no cover - typing only
    from opik import Dataset


@lru_cache(maxsize=None)
def dataset_suffix(package: str, filename: str) -> str:
    """Return a stable checksum-based suffix for a JSONL dataset file."""
    text = resources.files(package).joinpath(filename).read_text(encoding="utf-8")
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:8]


def generate_uuid7_str() -> str:
    """Generate a UUIDv7-compatible string, emulating the layout if unavailable."""
    import uuid

    if hasattr(uuid, "uuid7"):
        return str(uuid.uuid7())  # type: ignore[attr-defined]

    unix_ts_ms = int(time.time() * 1000) & ((1 << 48) - 1)
    rand_a = secrets.randbits(12)
    rand_b = secrets.randbits(62)

    uuid_int = unix_ts_ms << 80
    uuid_int |= 0x7 << 76  # version 7
    uuid_int |= rand_a << 64
    uuid_int |= 0b10 << 62  # RFC4122 variant
    uuid_int |= rand_b

    return str(uuid.UUID(int=uuid_int))


def attach_uuids(records: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Copy records and assign a fresh UUIDv7 `id` to each."""
    payload: list[dict[str, Any]] = []
    for record in records:
        rec = dict(record)
        rec["id"] = generate_uuid7_str()
        payload.append(rec)
    return payload


@dataclass(frozen=True, slots=True)
class DatasetSplitResult:
    """Outcome of applying a validation split to a dataset."""

    train_dataset: Dataset
    validation_dataset: Dataset | None
    train_items: list[dict[str, Any]]
    validation_items: list[dict[str, Any]]

    def train_ids(self) -> list[str]:
        return [
            item["id"]
            for item in self.train_items
            if isinstance(item, dict) and "id" in item
        ]

    def validation_ids(self) -> list[str]:
        return [
            item["id"]
            for item in self.validation_items
            if isinstance(item, dict) and "id" in item
        ]


@dataclass(frozen=True, slots=True)
class ValidationSplit:
    """Describe how to build a validation subset from an Opik dataset."""

    dataset: Dataset | None = None
    item_ids: Sequence[str] | None = None
    column: str | None = None
    train_label: str = "train"
    validation_label: str = "validation"
    ratio: float | None = None
    seed: int | None = None
    limit: int | None = None

    @classmethod
    def from_dataset(
        cls, dataset: Dataset, *, limit: int | None = None
    ) -> ValidationSplit:
        return cls(dataset=dataset, limit=limit)

    @classmethod
    def from_item_ids(
        cls,
        item_ids: Iterable[str],
        *,
        limit: int | None = None,
    ) -> ValidationSplit:
        return cls(item_ids=tuple(item_ids), limit=limit)

    @classmethod
    def from_column(
        cls,
        column: str,
        *,
        validation_label: str = "validation",
        train_label: str = "train",
        limit: int | None = None,
    ) -> ValidationSplit:
        return cls(
            column=column,
            validation_label=validation_label,
            train_label=train_label,
            limit=limit,
        )

    @classmethod
    def from_ratio(
        cls,
        ratio: float,
        *,
        seed: int | None = None,
        limit: int | None = None,
    ) -> ValidationSplit:
        return cls(ratio=ratio, seed=seed, limit=limit)

    def is_configured(self) -> bool:
        return any(
            (
                self.dataset is not None,
                bool(self.item_ids),
                self.column is not None,
                self.ratio is not None,
            )
        )

    def build(
        self,
        dataset: Dataset,
        *,
        n_samples: int | None,
        default_seed: int,
    ) -> DatasetSplitResult:
        if not self.is_configured():
            limit = self.limit if self.limit is not None else n_samples
            items = dataset.get_items(limit)
            return DatasetSplitResult(dataset, None, list(items), [])

        strategies_selected = sum(
            1
            for condition in (
                self.dataset is not None,
                bool(self.item_ids),
                self.column is not None,
                self.ratio is not None,
            )
            if condition
        )
        if strategies_selected > 1:
            raise ValueError(
                "Only one validation split strategy can be provided at a time."
            )

        if self.ratio is not None and not 0 < self.ratio < 1:
            raise ValueError("ratio must be between 0 and 1 (exclusive).")

        limit = self.limit if self.limit is not None else n_samples
        split = dataset.train_test_split(
            test_dataset=self.dataset,
            test_item_ids=self.item_ids,
            split_field=self.column,
            train_label=self.train_label,
            test_label=self.validation_label,
            test_size=self.ratio,
            seed=self.seed if self.seed is not None else default_seed,
            limit=limit,
        )
        train_items = list(split.train)
        validation_items = list(split.test)

        validation_dataset = self.dataset if self.dataset is not None else dataset

        if not validation_items and limit is not None:
            train_items = train_items[:limit]
            validation_dataset = None

        return DatasetSplitResult(
            dataset, validation_dataset, train_items, validation_items
        )

    def resolve(
        self,
        dataset: Dataset,
        *,
        n_samples: int | None,
        default_seed: int,
    ) -> DatasetSplitResult:
        return self.build(dataset, n_samples=n_samples, default_seed=default_seed)


__all__ = [
    "dataset_suffix",
    "generate_uuid7_str",
    "attach_uuids",
    "DatasetSplitResult",
    "ValidationSplit",
]
