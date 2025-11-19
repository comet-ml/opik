from __future__ import annotations

from typing import Any
from functools import lru_cache

import opik

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_medhallu_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="medhallu",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "UTAustin-AIHealth/MedHallu",
            "name": "pqa_labeled",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 300,
                "dataset_name": "medhallu_train",
            }
        },
        prefer_presets=True,
    )


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
    loader = _get_medhallu_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
