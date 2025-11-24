from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

HOVER_SPEC = DatasetSpec(
    name="hover",
    hf_path="vincentkoc/hover-parquet",
    default_source_split="train",
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="hover_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="train",
            start=150,
            count=300,
            dataset_name="hover_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=300,
            dataset_name="hover_test",
        ),
    },
)

_HOVER_HANDLE = DatasetHandle(HOVER_SPEC)


def hover(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """General-purpose HoVer loader."""
    return _HOVER_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
