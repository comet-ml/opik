from __future__ import annotations

from dataclasses import dataclass


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
