from __future__ import annotations

import json
from importlib.resources import files
import warnings

import opik
from datasets import load_dataset

from opik_optimizer.utils.dataset_utils import (
    resolve_test_mode_count,
    load_hf_dataset_slice,
)


def hotpot_300(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HotpotQA dataset.
    """
    warnings.warn(
        "hotpot_300() is deprecated; call hotpot(count=300) instead.",
        DeprecationWarning,
        stacklevel=2,
    )
    dataset_name = "hotpot_300_train" if not test_mode else "hotpot_300_sample"
    return _load_static_hotpot_slice(
        dataset_name=dataset_name,
        nb_items=300,
        test_mode=test_mode,
    )


def hotpot_500(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 500 samples of the HotpotQA dataset.
    """
    warnings.warn(
        "hotpot_500() is deprecated; call hotpot(count=500) instead.",
        DeprecationWarning,
        stacklevel=2,
    )
    dataset_name = "hotpot_500" if not test_mode else "hotpot_500_test"
    return _load_static_hotpot_slice(
        dataset_name=dataset_name,
        nb_items=500,
        test_mode=test_mode,
    )


HOT_POT_HF_DATASET = ("hotpot_qa", "fullwiki")
_HOT_POT_PRESETS = {
    "train": {
        "source_split": "train",
        "start": 0,
        "count": 150,
        "dataset_name": "hotpot_train",
    },
    "validation": {
        "source_split": "train",
        "start": 150,
        "count": 300,
        "dataset_name": "hotpot_validation",
    },
    "test": {
        "source_split": "validation",
        "start": 0,
        "count": 300,
        "dataset_name": "hotpot_test",
    },
}


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
    return load_hf_dataset_slice(
        base_name="hotpot",
        requested_split=split,
        presets=_HOT_POT_PRESETS,
        default_source_split="train",
        load_kwargs_resolver=_hotpot_load_kwargs,
        start=start,
        count=count,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
        prefer_presets=split is not None,
    )


def _load_static_hotpot_slice(
    *,
    dataset_name: str,
    nb_items: int,
    test_mode: bool,
) -> opik.Dataset:
    """Load legacy JSON slices shipped with the package."""
    expected = nb_items if not test_mode else resolve_test_mode_count(None)

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    items = dataset.get_items()
    if len(items) == expected:
        return dataset
    if items:
        raise ValueError(
            f"Dataset {dataset_name} contains {len(items)} items, expected {expected}. "
            "Delete it to recreate."
        )

    json_content = (files("opik_optimizer") / "data" / "hotpot-500.json").read_text(
        encoding="utf-8"
    )
    all_data = json.loads(json_content)
    slice_end = nb_items if not test_mode else expected
    trainset = all_data[:slice_end]
    dataset.insert(list(reversed(trainset)))
    return dataset


def _hotpot_load_kwargs(source_split: str) -> dict[str, Any]:
    return {
        "path": HOT_POT_HF_DATASET[0],
        "name": HOT_POT_HF_DATASET[1],
        "split": source_split,
    }
