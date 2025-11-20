from __future__ import annotations

from typing import Any

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

IF_TRAIN_DATASET = ("allenai/IF_multi_constraints_upto5", "train")
IF_TEST_DATASET = ("allenai/IFBench_test", "train")


def _ifbench_load_kwargs(source_key: str) -> dict[str, Any]:
    if source_key == "if_train":
        path, split = IF_TRAIN_DATASET
    elif source_key == "if_test":
        path, split = IF_TEST_DATASET
    else:
        raise ValueError(f"Unknown IFBench source '{source_key}'.")
    return {"path": path, "split": split}


IFBENCH_SPEC = DatasetSpec(
    name="ifbench",
    default_source_split="if_train",
    load_kwargs_resolver=_ifbench_load_kwargs,
    presets={
        "train": DatasetSplitPreset(
            source_split="if_train",
            start=0,
            count=150,
            dataset_name="ifbench_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="if_train",
            start=150,
            count=300,
            dataset_name="ifbench_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="if_test",
            start=0,
            count=294,
            dataset_name="ifbench_test",
        ),
    },
)

_IFBENCH_HANDLE = DatasetHandle(IFBENCH_SPEC)


def ifbench(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """General-purpose IFBench loader."""
    return _IFBENCH_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
