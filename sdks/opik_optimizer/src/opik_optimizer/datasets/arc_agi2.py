from __future__ import annotations

import logging
import random
from typing import Any, Mapping

import opik
from datasets import load_dataset

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle, resolve_dataset_seed

logger = logging.getLogger(__name__)

# NOTE: We currently load ARC-AGI-2 exclusively from Hugging Face
# (arc-agi-community/arc-agi-2). TODO: add optional JSON path loading for
# offline verification if needed.


def _format_grid(grid: list[list[int]]) -> str:
    return "\n".join(" ".join(str(cell) for cell in row) for row in grid)


def _render_examples(examples: list[dict[str, Any]]) -> str:
    sections = []
    for idx, ex in enumerate(examples):
        inp = _format_grid(ex.get("input", []))
        out = _format_grid(ex.get("output", []))
        sections.append(f"Train {idx} input:\n{inp}\nTrain {idx} output:\n{out}")
    return "\n\n".join(sections)


def _render_test_inputs(test_inputs: list[list[list[int]]]) -> str:
    return "\n\n".join(
        f"Test {idx} input:\n{_format_grid(grid)}" for idx, grid in enumerate(test_inputs)
    )


def _normalize_record(
    record: Mapping[str, Any], solutions: Mapping[str, Any] | None
) -> dict[str, Any]:
    task_id = (
        record.get("task_id")
        or record.get("id")
        or record.get("uuid")
        or record.get("hash")
        or "unknown_task"
    )
    train_examples = record.get("train") or record.get("training_examples") or []
    test_examples = record.get("test") or record.get("test_inputs") or []
    test_outputs = record.get("test_outputs") or record.get("outputs") or None
    if test_outputs is None and solutions is not None:
        test_outputs = solutions.get(task_id)

    # Some schemas embed input/output per test example.
    if not test_outputs and test_examples and isinstance(test_examples[0], dict):
        outputs = [ex.get("output") for ex in test_examples if "output" in ex]
        if outputs:
            test_outputs = outputs
        test_examples = [ex.get("input") for ex in test_examples]

    record_out = {
        "task_id": task_id,
        "training_examples": train_examples,
        "test_inputs": test_examples,
        "test_outputs": test_outputs,
        "training_examples_text": _render_examples(train_examples),
        "test_inputs_text": _render_test_inputs(test_examples),
    }
    return record_out


def _build_records(
    challenges: Mapping[str, Any] | list[Mapping[str, Any]],
    solutions: Mapping[str, Any] | None,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    if isinstance(challenges, Mapping):
        iterator = challenges.items()
    else:
        iterator = [(None, payload) for payload in challenges]

    for task_id, payload in iterator:
        record = _normalize_record(payload, solutions if solutions else None)
        if task_id is not None and record.get("task_id") == "unknown_task":
            record["task_id"] = task_id
        records.append(record)
    return records


def _shuffle_and_slice(
    records: list[dict[str, Any]], start: int | None, count: int | None, seed: int
) -> list[dict[str, Any]]:
    rng = random.Random(seed)
    rng.shuffle(records)
    start_idx = start or 0
    if count is None:
        return records[start_idx:]
    return records[start_idx : start_idx + count]


def _load_arc_agi2_split(
    source_split: str,
    start: int,
    count: int | None,
    seed: int,
) -> list[dict[str, Any]]:
    try:
        hf_ds = load_dataset("arc-agi-community/arc-agi-2", split=source_split)
    except Exception as exc:  # pragma: no cover - network/gated dataset dependent
        raise RuntimeError(
            "Failed to load ARC-AGI-2 from Hugging Face. Ensure access to "
            "'arc-agi-community/arc-agi-2'. TODO: add JSON path fallback for offline use."
        ) from exc

    records = _build_records(hf_ds.to_list(), solutions=None)
    return _shuffle_and_slice(records, start=start, count=count, seed=seed)


ARC_AGI2_SPEC = DatasetSpec(
    name="arc_agi2",
    default_source_split="train",
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train", start=0, count=200, dataset_name="arc_agi2_train"
        ),
        "validation": DatasetSplitPreset(
            source_split="validation",
            start=0,
            count=200,
            dataset_name="arc_agi2_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="test", start=0, count=100, dataset_name="arc_agi2_test"
        ),
    },
    custom_loader=_load_arc_agi2_split,
)

_ARC_AGI2_HANDLE = DatasetHandle(ARC_AGI2_SPEC)


def arc_agi2(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
    prefer_presets: bool | None = None,
) -> "opik.Dataset":
    """
    Load slices of the ARC-AGI-2 dataset from Hugging Face (arc-agi-community/arc-agi-2).
    """
    resolved_seed = resolve_dataset_seed(seed)
    return _ARC_AGI2_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=resolved_seed,
        test_mode_count=test_mode_count,
        prefer_presets=prefer_presets,
    )
