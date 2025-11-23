from __future__ import annotations

import opik

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle, add_record_index


RAG_HALLU_SPEC = DatasetSpec(
    name="rag_hallucinations",
    hf_path="aporia-ai/rag_hallucinations",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train",
            start=0,
            count=150,
            dataset_name="rag_hallucination_train",
        ),
        "validation": DatasetSplitPreset(
            source_split="train",
            start=150,
            count=150,
            dataset_name="rag_hallucination_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="train",
            start=300,
            count=150,
            dataset_name="rag_hallucination_test",
        ),
    },
    records_transform=add_record_index,  # de-dupe sensitive dataset, inject ids
)

_RAG_HALLU_HANDLE = DatasetHandle(RAG_HALLU_SPEC)


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
    return _RAG_HALLU_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
