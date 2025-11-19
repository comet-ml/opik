from __future__ import annotations

from typing import Any
from functools import lru_cache

import opik

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


def _truthful_transform(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    data: list[dict[str, Any]] = []
    for rec in records:
        gen_item = rec["gen"]
        mc_item = rec["mc"]
        correct_answers = set(gen_item["correct_answers"])
        for target in ("mc1_targets", "mc2_targets"):
            if target in mc_item:
                choices = mc_item[target]["choices"]
                labels = mc_item[target]["labels"]
                correct_answers.update(choice for choice, label in zip(choices, labels) if label == 1)
        all_answers = set(gen_item["correct_answers"] + gen_item["incorrect_answers"])
        for target in ("mc1_targets", "mc2_targets"):
            if target in mc_item:
                all_answers.update(mc_item[target]["choices"])
        example = {
            "question": gen_item["question"],
            "answer": gen_item["best_answer"],
            "choices": list(all_answers),
            "correct_answer": gen_item["best_answer"],
            "input": gen_item["question"],
            "output": gen_item["best_answer"],
            "context": gen_item.get("source", ""),
            "type": "TEXT",
            "category": gen_item["category"],
            "source": "MANUAL",
            "correct_answers": list(correct_answers),
            "incorrect_answers": gen_item["incorrect_answers"],
        }
        data.append(example)
    return data


def _truthful_custom_loader(source_split: str, start: int, count: int | None, seed: int) -> list[dict[str, Any]]:
    import datasets as ds

    download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
    gen_dataset = ds.load_dataset("truthful_qa", "generation", download_config=download_config)[source_split]
    mc_dataset = ds.load_dataset("truthful_qa", "multiple_choice", download_config=download_config)[source_split]
    available = max(0, len(gen_dataset) - start)
    total = available if count is None else min(count, available)
    pairs = list(
        zip(
            gen_dataset.select(range(start, start + total)),
            mc_dataset.select(range(start, start + total)),
        )
    )
    return [{"gen": gen, "mc": mc} for gen, mc in pairs]


@lru_cache(maxsize=1)
def _get_truthful_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="truthful_qa",
        default_source_split="validation",
        load_kwargs_resolver=lambda split: {"path": "truthful_qa", "name": "generation", "split": split},
        presets={
            "validation": {
                "source_split": "validation",
                "start": 0,
                "count": 300,
                "dataset_name": "truthful_qa_train",
            }
        },
        prefer_presets=True,
        records_transform=_truthful_transform,
        custom_loader=_truthful_custom_loader,
    )


def truthful_qa(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """TruthfulQA slices combining generation and multiple-choice views."""
    loader = _get_truthful_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
