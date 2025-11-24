from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

RAGBENCH_SPEC = DatasetSpec(
    name="ragbench_sentence_relevance",
    hf_path="wandb/ragbench-sentence-relevance-balanced",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="ragbench_sentence_relevance_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=150,
            dataset_name="ragbench_sentence_relevance_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="test",
            start=0,
            count=150,
            dataset_name="ragbench_sentence_relevance_test",
        ),
    },
)

_RAGBENCH_HANDLE = DatasetHandle(RAGBENCH_SPEC)


def ragbench_sentence_relevance(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """RAGBench sentence relevance slices."""
    return _RAGBENCH_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
