from __future__ import annotations

import opik

from functools import lru_cache

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_rag_hallu_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="rag_hallucinations",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "aporia-ai/rag_hallucinations",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 300,
                "dataset_name": "rag_hallucination_train",
            }
        },
        prefer_presets=True,
    )


def rag_hallucinations(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """RAG Hallucinations dataset slices (context, question, answer, label)."""
    loader = _get_rag_hallu_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
