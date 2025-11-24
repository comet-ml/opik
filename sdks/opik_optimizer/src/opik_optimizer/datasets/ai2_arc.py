from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

AI2_ARC_SPEC = DatasetSpec(
    name="ai2_arc",
    hf_path="ai2_arc",
    hf_name="ARC-Challenge",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="ai2_arc_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=150,
            dataset_name="ai2_arc_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="test",
            start=0,
            count=150,
            dataset_name="ai2_arc_test",
        ),
    },
)

_AI2_ARC_HANDLE = DatasetHandle(AI2_ARC_SPEC)


def ai2_arc(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """
    Load slices of the AI2 ARC Challenge dataset.

    By default (no overrides) this returns the 300-example subset used in our
    demos (`dataset_name="ai2_arc_train"`). Pass explicit `split`, `count`, or
    `start` arguments to pull arbitrary portions of the Hugging Face dataset.
    """
    return _AI2_ARC_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
