from __future__ import annotations


import opik

from opik_optimizer.utils.dataset_utils import load_hf_dataset_slice

PUPA_LOAD_KWARGS = {"path": "Columbia-NLP/PUPA", "name": "pupa_new", "split": "train"}
_PUPA_PRESETS = {
    "train": {
        "source_split": "train",
        "start": 0,
        "count": 111,
        "dataset_name": "pupa_train",
    },
    "validation": {
        "source_split": "train",
        "start": 111,
        "count": 111,
        "dataset_name": "pupa_validation",
    },
    "test": {
        "source_split": "train",
        "start": 222,
        "count": 221,
        "dataset_name": "pupa_test",
    },
}


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
    return load_hf_dataset_slice(
        base_name="pupa",
        requested_split=split,
        presets=_PUPA_PRESETS,
        default_source_split="train",
        load_kwargs_resolver=lambda _: PUPA_LOAD_KWARGS,
        start=start,
        count=count,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
        prefer_presets=split is not None,
    )
