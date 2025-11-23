from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

PUPA_SPEC = DatasetSpec(
    name="pupa",
    hf_path="Columbia-NLP/PUPA",
    hf_name="pupa_new",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=111,
            dataset_name="pupa_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="train",
            start=111,
            count=111,
            dataset_name="pupa_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="train",
            start=222,
            count=221,
            dataset_name="pupa_test",
        ),
    },
)

_PUPA_HANDLE = DatasetHandle(PUPA_SPEC)


def pupa(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """General-purpose PUPA loader."""
    return _PUPA_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
