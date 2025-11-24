from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

CNN_DAILYMAIL_SPEC = DatasetSpec(
    name="cnn_dailymail",
    hf_path="cnn_dailymail",
    hf_name="3.0.0",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="cnn_dailymail_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=150,
            dataset_name="cnn_dailymail_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="test",
            start=0,
            count=150,
            dataset_name="cnn_dailymail_test",
        ),
    },
)

_CNN_DAILYMAIL_HANDLE = DatasetHandle(CNN_DAILYMAIL_SPEC)


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
    return _CNN_DAILYMAIL_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
