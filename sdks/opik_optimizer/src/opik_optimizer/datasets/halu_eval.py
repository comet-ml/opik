from __future__ import annotations

from typing import Any
from functools import lru_cache

import opik
import pandas as pd

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


def _load_halu_dataframe(split: str) -> pd.DataFrame:
    if split != "train":
        raise ValueError("HaluEval exposes only a training split.")
    return pd.read_parquet(
        "hf://datasets/pminervini/HaluEval/general/data-00000-of-00001.parquet"
    )


def _halu_records_transform(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "input": rec["user_query"],
            "llm_output": rec["chatgpt_response"],
            "expected_hallucination_label": rec["hallucination"],
        }
        for rec in records
    ]


@lru_cache(maxsize=1)
def _get_halu_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="halu_eval",
        default_source_split="train",
        load_kwargs_resolver=lambda split: {
            "path": "pminervini/HaluEval",
            "name": "general",
            "split": split,
        },
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 300,
                "dataset_name": "halu_eval_300_train",
            }
        },
        prefer_presets=True,
        records_transform=_halu_records_transform,
    )


def halu_eval_300(
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
    Load slices of the HaluEval dataset (user query, ChatGPT response, label).

    The default call returns the 300-example slice used in our demos. Override
    ``split``/``count``/``start``/``dataset_name`` to stream other sections of the
    dataset.
    """
    loader = _get_halu_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
