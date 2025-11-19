from __future__ import annotations

from typing import Any

import opik

from functools import lru_cache

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_ai2_arc_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="ai2_arc",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "ai2_arc",
            "name": "ARC-Challenge",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 300,
                "dataset_name": "ai2_arc_train",
            }
        },
        prefer_presets=True,
    )


def ai2_arc(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """
    Load slices of the AI2 ARC Challenge dataset.

    By default (no overrides) this returns the 300-example subset used in our
    demos (`dataset_name="ai2_arc_train"`). Pass explicit `split`, `count`, or
    `start` arguments to pull arbitrary portions of the Hugging Face dataset.
    """
    loader = _get_ai2_arc_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
