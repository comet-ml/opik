from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..helpers import stringify_column_value

# Constants for columnar search space building
MAX_UNIQUE_COLUMN_VALUES = 25


@dataclass(frozen=True, slots=True)
class ColumnarSearchSpace:
    columns: list[str]
    combo_labels: list[str]
    combo_to_indices: dict[str, list[int]]
    max_group_size: int

    @classmethod
    def empty(cls) -> ColumnarSearchSpace:
        return cls([], [], {}, 0)

    @property
    def is_enabled(self) -> bool:
        return bool(self.combo_labels)

    def select_index(self, combo_label: str, member_index: int) -> int:
        indices = self.combo_to_indices.get(combo_label)
        if not indices:
            raise ValueError(f"Unknown combo label requested: {combo_label}")
        if len(indices) == 1:
            return indices[0]
        return indices[member_index % len(indices)]


def build_columnar_search_space(
    dataset_items: list[dict[str, Any]],
    max_unique_column_values: int = MAX_UNIQUE_COLUMN_VALUES,
) -> ColumnarSearchSpace:
    """
    Infer a lightweight columnar index so Optuna can learn over categorical fields.

    We only keep columns that repeat across rows (avoid high-cardinality text) and
    cap unique values to keep the search space manageable.

    Args:
        dataset_items: List of dataset item dictionaries
        max_unique_column_values: Maximum number of unique values per column (default: 25)

    Returns:
        ColumnarSearchSpace instance
    """
    if not dataset_items:
        return ColumnarSearchSpace.empty()

    candidate_columns: list[str] = []
    for key in dataset_items[0]:
        if key == "id":
            continue

        unique_values: set[str] = set()
        skip_column = False
        for item in dataset_items:
            if key not in item:
                skip_column = True
                break
            str_value = stringify_column_value(item.get(key))
            if str_value is None:
                skip_column = True
                break
            unique_values.add(str_value)
            if len(unique_values) > max_unique_column_values:
                skip_column = True
                break

        if skip_column:
            continue

        if len(unique_values) < 2 or len(unique_values) >= len(dataset_items):
            continue

        candidate_columns.append(key)

    if not candidate_columns:
        return ColumnarSearchSpace.empty()

    combo_to_indices: dict[str, list[int]] = {}
    for idx, item in enumerate(dataset_items):
        combo_parts: list[str] = []
        skip_example = False
        for column in candidate_columns:
            str_value = stringify_column_value(item.get(column))
            if str_value is None:
                skip_example = True
                break
            combo_parts.append(f"{column}={str_value}")

        if skip_example:
            continue

        combo_label = "|".join(combo_parts)
        combo_to_indices.setdefault(combo_label, []).append(idx)

    if not combo_to_indices:
        return ColumnarSearchSpace.empty()

    max_group_size = max(len(indices) for indices in combo_to_indices.values())
    combo_labels = sorted(combo_to_indices.keys())
    return ColumnarSearchSpace(
        columns=candidate_columns,
        combo_labels=combo_labels,
        combo_to_indices=combo_to_indices,
        max_group_size=max_group_size,
    )
