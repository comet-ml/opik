from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle, warn_deprecated_dataset


def hotpot_300(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HotpotQA dataset.
    """
    warn_deprecated_dataset("hotpot_300", "hotpot(count=300)")
    dataset_name = "hotpot_300_train" if not test_mode else "hotpot_300_sample"
    return hotpot(
        split="train",
        start=0,
        count=300,
        dataset_name=dataset_name,
        test_mode=test_mode,
    )


def hotpot_500(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 500 samples of the HotpotQA dataset.
    """
    warn_deprecated_dataset("hotpot_500", "hotpot(count=500)")
    dataset_name = "hotpot_500" if not test_mode else "hotpot_500_test"
    return hotpot(
        split="train",
        start=0,
        count=500,
        dataset_name=dataset_name,
        test_mode=test_mode,
    )


HOT_POT_SPEC = DatasetSpec(
    name="hotpot",
    hf_path="hotpot_qa",
    hf_name="fullwiki",
    default_source_split="train",
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="hotpot_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="train",
            start=150,
            count=300,
            dataset_name="hotpot_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=300,
            dataset_name="hotpot_test",
        ),
    },
)

_HOT_POT_HANDLE = DatasetHandle(HOT_POT_SPEC)


def hotpot(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """General-purpose HotpotQA loader."""
    return _HOT_POT_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
        prefer_presets=split is not None,
    )
