from __future__ import annotations

import warnings

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset import DatasetHandle, FilterBy


HOT_POT_SPEC = DatasetSpec(
    name="hotpot",
    hf_path="hotpotqa/hotpot_qa",
    hf_name="fullwiki",
    default_source_split="train",
    load_kwargs_resolver=lambda split: {
        "path": "hotpotqa/hotpot_qa",
        "name": "fullwiki",
        "split": split,
        "revision": "main",
    },
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
    filter_by: FilterBy | None = None,
) -> opik.Dataset:
    """General-purpose HotpotQA loader."""
    if split == "test":
        warnings.warn(
            "Hotpot test split does not include gold answers. "
            "Metrics that require answers (e.g., answer_correctness_score) will fail. "
            "Use split='train' or split='validation' for scoring.",
            stacklevel=2,
        )
    return _HOT_POT_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
        prefer_presets=(
            split is not None
            and filter_by is None
            and count is None
            and start is None
            and dataset_name is None
        ),
        filter_by=filter_by,
    )
