from __future__ import annotations

import opik
from typing import Any

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle


def _halu_records_transform(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "input": rec["user_query"],
            "llm_output": rec["chatgpt_response"],
            "expected_hallucination_label": rec["hallucination"],
        }
        for rec in records
    ]


HALU_EVAL_SPEC = DatasetSpec(
    name="halu_eval",
    hf_path="pminervini/HaluEval",
    hf_name="general",
    default_source_split="data",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="data",
            start=0,
            count=300,
            dataset_name="halu_eval_300_train",
        )
    },
    records_transform=_halu_records_transform,
)

_HALU_EVAL_HANDLE = DatasetHandle(HALU_EVAL_SPEC)


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
    return _HALU_EVAL_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
