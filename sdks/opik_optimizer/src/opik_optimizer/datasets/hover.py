from __future__ import annotations

from typing import Any

import opik

from opik_optimizer.utils.dataset_utils import load_hf_dataset_slice

_HOVER_PRESETS = {
    "train": {
        "source_split": "train",
        "start": 0,
        "count": 150,
        "dataset_name": "hover_train",
    },
    "validation": {
        "source_split": "train",
        "start": 150,
        "count": 300,
        "dataset_name": "hover_validation",
    },
    "test": {
        "source_split": "validation",
        "start": 0,
        "count": 300,
        "dataset_name": "hover_test",
    },
}


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
    return load_hf_dataset_slice(
        base_name="hover",
        requested_split=split,
        presets=_HOVER_PRESETS,
        default_source_split="train",
        load_kwargs_resolver=_hover_load_kwargs,
        start=start,
        count=count,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
        prefer_presets=split is not None,
    )


def _hover_load_kwargs(source_split: str) -> dict[str, Any]:
    return {
        "path": "vincentkoc/hover-parquet",
        "split": source_split,
    }
