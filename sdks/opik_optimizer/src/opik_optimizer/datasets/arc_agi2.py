from __future__ import annotations

import json
import logging
import os
import random
from pathlib import Path
from typing import Any
from collections.abc import Iterable
from collections.abc import Mapping

import opik
from datasets import load_dataset

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle, resolve_dataset_seed

logger = logging.getLogger(__name__)

# We load ARC-AGI-2 from HF when available and fall back to a local JSON drop
# (e.g., ARC Prize dumps) if HF is unavailable. Set ARC_AGI2_DATA_DIR to a folder
# containing arc-agi_* JSON files to force local loading.


def _format_grid(grid: list[list[int]]) -> str:
    return "\n".join(" ".join(str(cell) for cell in row) for row in grid)


def _render_examples(examples: list[dict[str, Any]]) -> str:
    sections = []
    for idx, ex in enumerate(examples, start=1):
        inp = _format_grid(ex.get("input", []))
        out = _format_grid(ex.get("output", []))
        sections.append(
            f"Example #{idx}\n"
            f"Input:\n<ArcGrid>\n{inp}\n</ArcGrid>\n\n"
            f"Output:\n<ArcGrid>\n{out}\n</ArcGrid>"
        )
    return "\n\n".join(sections)


def _render_test_inputs(test_inputs: list[list[list[int]]]) -> str:
    sections = []
    for idx, grid in enumerate(test_inputs, start=1):
        sections.append(
            f"Challenge #{idx}\nInput:\n<ArcGrid>\n{_format_grid(grid)}\n</ArcGrid>"
        )
    return "\n\n".join(sections)


def _grid_shape(grid: list[list[int]]) -> str:
    try:
        rows = len(grid)
        cols = len(grid[0]) if rows else 0
        ragged = any(len(r) != cols for r in grid)
        return f"{rows}x{cols}{' (ragged)' if ragged else ''}"
    except Exception:
        return "unknown"


def _render_shape_summary(
    train_examples: list[dict[str, Any]], test_inputs: list[list[list[int]]]
) -> str:
    parts: list[str] = []
    for idx, ex in enumerate(train_examples):
        parts.append(
            f"train {idx}: {_grid_shape(ex.get('input', []))} -> {_grid_shape(ex.get('output', []))}"
        )
    for idx, grid in enumerate(test_inputs):
        parts.append(f"test {idx}: {_grid_shape(grid)}")
    return "\n".join(parts)


def _normalize_record(
    record: Mapping[str, Any], solutions: Mapping[str, Any] | None
) -> dict[str, Any]:
    """
    Normalize ARC-AGI-2 record into a consistent shape:
    {
        task_id: str,
        training_examples: list[{input, output}],
        test_inputs: list[input],
        test_outputs: list[output] | None,
        training_examples_text: str,
        test_inputs_text: str,
    }
    """
    task_id = (
        record.get("task_id")
        or record.get("id")
        or record.get("uuid")
        or record.get("hash")
        or "unknown_task"
    )
    train_examples = record.get("train") or record.get("training_examples") or []
    test_examples_raw = record.get("test") or record.get("test_inputs") or []

    # Ensure test_inputs is a list of grids
    if test_examples_raw and isinstance(test_examples_raw[0], dict):
        test_inputs = [ex.get("input") for ex in test_examples_raw]
        inferred_outputs = [ex.get("output") for ex in test_examples_raw if "output" in ex]
    else:
        test_inputs = list(test_examples_raw)
        inferred_outputs = []

    test_outputs = record.get("test_outputs") or record.get("outputs") or None
    if test_outputs is None and inferred_outputs:
        test_outputs = inferred_outputs
    if test_outputs is None and solutions is not None:
        test_outputs = solutions.get(task_id)

    record_out = {
        "task_id": task_id,
        "training_examples": train_examples,
        "test_inputs": test_inputs,
        "test_outputs": test_outputs,
        "training_examples_text": _render_examples(train_examples),
        "test_inputs_text": _render_test_inputs(test_inputs),
        "shape_summary": _render_shape_summary(train_examples, test_inputs),
    }
    return record_out


def _build_records(
    challenges: Mapping[str, Any] | list[Mapping[str, Any]],
    solutions: Mapping[str, Any] | None,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    if isinstance(challenges, Mapping):
        iterator: Iterable[tuple[str | None, Mapping[str, Any]]] = challenges.items()
    else:
        iterator = [(None, payload) for payload in challenges]

    for task_id, payload in iterator:
        record = _normalize_record(payload, solutions if solutions else None)
        if task_id is not None and record.get("task_id") == "unknown_task":
            record["task_id"] = task_id
        records.append(record)
    if not records:
        raise ValueError("No ARC-AGI-2 records loaded.")
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


def _filter_by_task_id(
    records: list[dict[str, Any]],
    filter_task_id: str | None,
    *,
    log_fn: callable | None = None,
) -> list[dict[str, Any]]:
    """
    Filter the loaded records so ARC_AGI2_TASK_ID can force a specific task while
    keeping backwards compatibility with random sampling when unset.
    """
    if not filter_task_id:
        return records

    matched = [rec for rec in records if rec.get("task_id") == filter_task_id]
    if matched:
        if log_fn:
            log_fn(f"Filtered down to task_id={filter_task_id} ({len(matched)} record).")
        return matched

    if log_fn:
        log_fn(
            f"ARC_AGI2_TASK_ID={filter_task_id} not found in loaded records; "
            f"keeping {len(records)} item(s)."
        )
    return records


def _load_arc_agi2_split(
    source_split: str,
    start: int,
    count: int | None,
    seed: int,
) -> list[dict[str, Any]]:
    data_dir_env = os.getenv("ARC_AGI2_DATA_DIR")
    debug_load = os.getenv("ARC_AGI2_DATA_DEBUG", "0") not in {
        "",
        "0",
        "false",
        "False",
    }
    prefer_local = os.getenv("ARC_AGI2_PREFER_LOCAL", "1") not in {
        "",
        "0",
        "false",
        "False",
    }
    filter_task_id = os.getenv("ARC_AGI2_TASK_ID")
    last_exc: Exception | None = None
    repo_dir = Path(__file__).resolve().parents[5] / "external" / "ARC-AGI-2" / "data"
    data_dir = Path(data_dir_env).expanduser() if data_dir_env else None
    if data_dir is None and repo_dir.exists():
        data_dir = repo_dir

    def _log(msg: str) -> None:
        if debug_load:
            logger.info("[arc_agi2] %s", msg)

    def _validate(records: list[dict[str, Any]], source: str) -> list[dict[str, Any]]:
        if not records:
            raise ValueError(f"No ARC-AGI-2 records loaded from {source}.")
        if count is not None and len(records) < count:
            raise RuntimeError(
                f"{source} provided {len(records)} items, fewer than requested {count}. "
                "Adjust ARC_AGI2_DATA_DIR/ARC_AGI2_PREFER_LOCAL or reduce count."
            )
        return records

    # Prefer local JSON if available (most reliable in restricted envs).
    if prefer_local and data_dir and data_dir.exists():
        split_dir = data_dir / ("training" if source_split == "train" else "evaluation")
        if not split_dir.exists():
            raise FileNotFoundError(
                f"ARC_AGI2_DATA_DIR={data_dir} missing split folder {split_dir}"
            )

        if filter_task_id:
            target_file = split_dir / f"{filter_task_id}.json"
            if target_file.exists():
                payload = json.loads(target_file.read_text())
                rec = _normalize_record(payload, solutions=None)
                if rec.get("task_id") == "unknown_task":
                    rec["task_id"] = target_file.stem
                if not rec.get("training_examples"):
                    raise ValueError(
                        f"Task {filter_task_id} under {target_file} has no training examples."
                    )
                _log(f"Loaded task_id={filter_task_id} directly from {target_file}.")
                return [rec]
            else:
                logger.warning(
                    "ARC_AGI2_TASK_ID=%s not found under %s; falling back to random sampling",
                    filter_task_id,
                    split_dir,
                )

        json_files = sorted(split_dir.glob("*.json"))
        if not json_files:
            raise FileNotFoundError(f"No JSON files found under {split_dir}")

        rng = random.Random(seed)
        rng.shuffle(json_files)
        selected = json_files[start:] if count is None else json_files[start : start + count]
        records: list[dict[str, Any]] = []
        for jf in selected:
            try:
                payload = json.loads(jf.read_text())
                rec = _normalize_record(payload, solutions=None)
                if rec.get("task_id") == "unknown_task":
                    rec["task_id"] = jf.stem
                if rec.get("training_examples"):
                    records.append(rec)
                else:
                    logger.warning("Skipping %s because it has no training examples", jf)
            except Exception as exc:
                logger.warning("Failed to parse %s: %s", jf, exc)

        records = _validate(records, f"local JSON ({split_dir})")
        records = _filter_by_task_id(records, filter_task_id, log_fn=_log)
        _log(
            f"Loaded {len(records)} ARC-AGI-2 items from {split_dir} "
            f"(requested count={count}, start={start})"
        )
        return records

    # HF path (optional, falls back to local)
    try:
        hf_ds = load_dataset("arc-agi-community/arc-agi-2", split=source_split)
        records = _build_records(hf_ds.to_list(), solutions=None)
        records = _filter_by_task_id(records, filter_task_id, log_fn=_log)
        _log(f"HF load succeeded: {len(records)} items for split {source_split}")
        sliced = _shuffle_and_slice(records, start=start, count=count, seed=seed)
        return _validate(sliced, "Hugging Face")
    except Exception as exc:  # pragma: no cover - network/gated dataset dependent
        last_exc = exc
        logger.warning(
            "HF load failed for ARC-AGI-2 split '%s' (%s); attempting local JSON fallback",
            source_split,
            exc,
        )

    if data_dir and data_dir.exists():
        split_dir = data_dir / ("training" if source_split == "train" else "evaluation")
        if filter_task_id:
            target_file = split_dir / f"{filter_task_id}.json"
            if target_file.exists():
                payload = json.loads(target_file.read_text())
                rec = _normalize_record(payload, solutions=None)
                if rec.get("task_id") == "unknown_task":
                    rec["task_id"] = target_file.stem
                if not rec.get("training_examples"):
                    raise ValueError(
                        f"Task {filter_task_id} under {target_file} has no training examples."
                    )
                _log(f"Loaded task_id={filter_task_id} directly from {target_file}.")
                return [rec]
            else:
                logger.warning(
                    "ARC_AGI2_TASK_ID=%s not found under %s; falling back to random sampling",
                    filter_task_id,
                    split_dir,
                )

        json_files = sorted(split_dir.glob("*.json"))
        rng = random.Random(seed)
        rng.shuffle(json_files)
        selected = json_files[start:] if count is None else json_files[start : start + count]
        records = []
        for jf in selected:
            try:
                payload = json.loads(jf.read_text())
                rec = _normalize_record(payload, solutions=None)
                if rec.get("task_id") == "unknown_task":
                    rec["task_id"] = jf.stem
                if rec.get("training_examples"):
                    records.append(rec)
                else:
                    logger.warning("Skipping %s because it has no training examples", jf)
            except Exception as exc:
                logger.warning("Failed to parse %s: %s", jf, exc)
        records = _validate(records, f"local JSON ({split_dir})")
        records = _filter_by_task_id(records, filter_task_id, log_fn=_log)
        _log(
            f"Loaded {len(records)} ARC-AGI-2 items from {split_dir} "
            f"(requested count={count}, start={start})"
        )
        return records

    raise RuntimeError(
        "Failed to load ARC-AGI-2 from HF and no local JSON fallback available. "
        "Set ARC_AGI2_DATA_DIR to the folder containing arc-agi_* JSON files or "
        "ensure HF access to arc-agi-community/arc-agi-2."
    ) from last_exc


def _records_transform_task_filter(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Optional filtering by ARC_AGI2_TASK_ID env var to pick a specific task."""
    task_id = os.getenv("ARC_AGI2_TASK_ID")
    if not task_id:
        return records

    def _log(msg: str) -> None:
        logger.info("[arc_agi2] %s", msg)

    return _filter_by_task_id(records, task_id, log_fn=_log)


ARC_AGI2_SPEC = DatasetSpec(
    name="arc_agi2",
    default_source_split="train",
    hf_path="arc-agi-community/arc-agi-2",
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
    records_transform=_records_transform_task_filter,
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
) -> opik.Dataset:
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
