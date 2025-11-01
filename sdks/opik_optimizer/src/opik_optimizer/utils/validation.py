"""
Validation split helpers shared across optimizers.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any
from collections.abc import Iterable, Sequence

if TYPE_CHECKING:
    from opik import Dataset


@dataclass(frozen=True, slots=True)
class ValidationSplit:
    """Describe how to build a validation subset from an Opik dataset.

    Exactly one strategy should be supplied when constructing an instance. Prefer the
    convenience constructors such as :meth:`ValidationSplit.from_ratio`.
    """

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
        """Use another dataset as the validation source."""
        return cls(dataset=dataset, limit=limit)

    @classmethod
    def from_item_ids(
        cls,
        item_ids: Iterable[str],
        *,
        limit: int | None = None,
    ) -> ValidationSplit:
        """Reserve specific dataset item IDs for validation."""
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
        """Use a dataset column (or metadata field) that already stores split labels."""
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
        """Sample a random validation subset using ``ratio`` of the examples."""
        return cls(ratio=ratio, seed=seed, limit=limit)

    def is_configured(self) -> bool:
        """Return True when a validation strategy has actually been provided."""
        return any(
            (
                self.dataset is not None,
                bool(self.item_ids),
                self.column is not None,
                self.ratio is not None,
            )
        )

    def resolve(
        self,
        dataset: Dataset,
        *,
        n_samples: int | None,
        default_seed: int,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        """Resolve training and validation collections for the given dataset."""
        if not self.is_configured():
            limit = self.limit if self.limit is not None else n_samples
            items = dataset.get_items(limit)
            return list(items), []

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

        if not validation_items and limit is not None:
            train_items = train_items[:limit]

        return train_items, validation_items


__all__ = ["ValidationSplit"]
