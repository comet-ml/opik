from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

GSM8K_SPEC = DatasetSpec(
    name="gsm8k",
    hf_path="gsm8k",
    hf_name="main",
    default_source_split="train",
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=300,
            dataset_name="gsm8k_train",
        )
    },
)

_GSM8K_HANDLE = DatasetHandle(GSM8K_SPEC)


def gsm8k(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Grade-school math word problems (GSM8K) slices."""
    return _GSM8K_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
