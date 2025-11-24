from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

MEDHALLU_SPEC = DatasetSpec(
    name="medhallu",
    hf_path="UTAustin-AIHealth/MedHallu",
    hf_name="pqa_labeled",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="medhallu_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="train",
            start=150,
            count=150,
            dataset_name="medhallu_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="train",
            start=300,
            count=150,
            dataset_name="medhallu_test",
        ),
    },
)

_MEDHALLU_HANDLE = DatasetHandle(MEDHALLU_SPEC)


def medhallu(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Medical hallucination QA dataset slices."""
    return _MEDHALLU_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
