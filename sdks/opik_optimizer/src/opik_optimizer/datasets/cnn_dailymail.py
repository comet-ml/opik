from __future__ import annotations

from typing import Any
from functools import lru_cache

import opik

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_cnn_dailymail_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="cnn_dailymail",
        default_source_split="validation",
        load_kwargs_resolver=lambda split: {
            "path": "cnn_dailymail",
            "name": "3.0.0",
            "split": split,
        },
        presets={
            "validation": {
                "source_split": "validation",
                "start": 0,
                "count": 100,
                "dataset_name": "cnn_dailymail_train",
            }
        },
        prefer_presets=True,
    )


def cnn_dailymail(
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
    Load slices of the CNN/DailyMail summarization benchmark.

    The default call returns the first 100 items from the validation split to
    mirror earlier demo behavior. Provide explicit `split`, `count`, or `start`
    arguments to stream any region of the dataset.
    """
    loader = _get_cnn_dailymail_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
