from __future__ import annotations

from functools import lru_cache

import opik

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_ragbench_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="ragbench_sentence_relevance",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "wandb/ragbench-sentence-relevance-balanced",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 300,
                "dataset_name": "ragbench_sentence_relevance_train",
            }
        },
        prefer_presets=True,
    )


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
    loader = _get_ragbench_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
