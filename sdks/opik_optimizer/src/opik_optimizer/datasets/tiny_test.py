from __future__ import annotations

from functools import lru_cache

import opik
import datasets as ds

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


def _tiny_records_transform(records: list[dict[str, str]]) -> list[dict[str, Any]]:
    transformed: list[dict[str, Any]] = []
    for rec in records:
        transformed.append(
            {
                "text": rec.get("text", ""),
                "label": rec.get("label", ""),
                "metadata": {"context": rec.get("context", "")},
            }
        )
    return transformed


@lru_cache(maxsize=1)
def _get_tiny_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="tiny_test",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "json",
            "data_files": "hf://datasets/vincentkoc/tiny_qa_benchmark_pp/data/core_en/core_en.jsonl",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 5,
                "dataset_name": "tiny_test_train",
            }
        },
        prefer_presets=True,
        records_transform=_tiny_records_transform,
    )


def tiny_test(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Tiny QA benchmark slices (core_en subset)."""
    loader = _get_tiny_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
