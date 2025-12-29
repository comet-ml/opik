from __future__ import annotations

import logging
import os
import random
from typing import Any
from collections.abc import Callable, Iterable, Mapping

import opik
from datasets import load_dataset

from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import (
    DatasetHandle,
    FilterBy,
    resolve_dataset_seed,
)
from opik_optimizer.utils.image_utils import encode_image_to_base64_uri

logger = logging.getLogger(__name__)

# ARC-AGI-2 is loaded from Hugging Face. ARC_AGI2_GROUP_BY_TASK can disable
# per-task grouping. ARC_AGI2_INCLUDE_IMAGES can embed base64 PNGs.

ARC_AGI2_HF_PATH = "vincentkoc/arc-agi-2"


def _bool_env(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value not in {"", "0", "false", "False"}


def _normalize_list_field(value: Any) -> list[Any] | None:
    if value is None:
        return None
    if isinstance(value, list):
        return value
    return [value]


def _encode_image_list(
    values: list[Any] | None, *, image_format: str
) -> list[str | None] | None:
    if values is None:
        return None
    return [
        encode_image_to_base64_uri(item, image_format=image_format) for item in values
    ]


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
    record: Mapping[str, Any],
    *,
    include_images: bool,
    image_format: str,
) -> dict[str, Any]:
    """
    Normalize ARC-AGI-2 record into a consistent shape:
    {
        task_id: str,
        training_examples: list[{input, output}],
        test_inputs: list[input],
        test_outputs: list[output] | None,
    }
    Derived text fields are added by `_finalize_record`.
    """
    task_id = (
        record.get("task_id")
        or record.get("id")
        or record.get("uuid")
        or record.get("hash")
        or "unknown_task"
    )
    row_id = record.get("id")
    split = record.get("split")
    train_examples = record.get("train") or record.get("training_examples") or []
    test_examples_raw = record.get("test") or record.get("test_inputs") or []

    # Ensure test_inputs is a list of grids
    if test_examples_raw and isinstance(test_examples_raw[0], dict):
        test_inputs = [ex.get("input") for ex in test_examples_raw]
        inferred_outputs = [
            ex.get("output") for ex in test_examples_raw if "output" in ex
        ]
    else:
        test_inputs = list(test_examples_raw)
        inferred_outputs = []

    test_outputs = record.get("test_outputs") or record.get("outputs") or None
    if test_outputs is None and inferred_outputs:
        test_outputs = inferred_outputs

    record_out: dict[str, Any] = {
        "task_id": task_id,
        "training_examples": train_examples,
        "test_inputs": test_inputs,
        "test_outputs": test_outputs,
    }
    if row_id:
        record_out["id"] = row_id
        record_out["test_ids"] = [row_id]
    if split:
        record_out["split"] = split
    test_text_fields = (
        "test_input_texts",
        "test_output_texts",
        "test_prompts",
        "test_targets",
        "test_conversations",
    )
    for field in test_text_fields:
        values = _normalize_list_field(record.get(field))
        if values is not None:
            record_out[field] = values
    if include_images:
        # Preserve raw image fields (encoded to data URIs) and build canonical
        # shortcuts `train_images`, `train_output_images`, `test_images`.
        image_fields = (
            "train_input_image_color",
            "train_input_image_annotated",
            "train_output_image_color",
            "train_output_image_annotated",
            "test_input_image_color",
            "test_input_image_annotated",
            "test_output_image_color",
            "test_output_image_annotated",
        )
        for field in image_fields:
            values = _normalize_list_field(record.get(field))
            if values is not None:
                record_out[field] = _encode_image_list(
                    values, image_format=image_format
                )

        def _first_list(names: list[str]) -> list[Any] | None:
            for name in names:
                vals = record_out.get(name) or record.get(name)
                if isinstance(vals, list) and vals:
                    return _encode_image_list(vals, image_format=image_format)
            return None

        train_images = _first_list(
            [
                "train_input_image_color",
                "train_input_image_annotated",
                "train_images",
                "train_input_images",
            ]
        )
        if train_images:
            record_out["train_images"] = train_images

        train_output_images = _first_list(
            [
                "train_output_image_color",
                "train_output_image_annotated",
                "train_output_images",
            ]
        )
        if train_output_images:
            record_out["train_output_images"] = train_output_images

        test_images = _first_list(
            [
                "test_input_image_color",
                "test_input_image_annotated",
                "test_images",
                "test_input_images",
            ]
        )
        if test_images:
            record_out["test_images"] = test_images
    return record_out


def _finalize_record(record: dict[str, Any]) -> dict[str, Any]:
    training_examples = record.get("training_examples") or []
    test_inputs = record.get("test_inputs") or []
    record["training_examples_text"] = _render_examples(training_examples)
    record["test_inputs_text"] = _render_test_inputs(test_inputs)
    record["shape_summary"] = _render_shape_summary(training_examples, test_inputs)
    return record


def _parse_test_index(record_id: str | None) -> int | None:
    if not record_id:
        return None
    marker = "__test_"
    if marker not in record_id:
        return None
    suffix = record_id.rsplit(marker, 1)[-1]
    try:
        return int(suffix)
    except ValueError:
        return None


def _extend_list_field(
    entry: dict[str, Any],
    field: str,
    values: Any,
    *,
    test_count: int,
    existing_tests: int,
    log_fn: Callable[[str], None] | None = None,
) -> None:
    if values is None:
        if field in entry and entry[field] is not None:
            entry[field].extend([None] * test_count)
        return

    values_list = values if isinstance(values, list) else [values]
    if len(values_list) != test_count and log_fn:
        log_fn(
            f"ARC-AGI-2 {field} length mismatch: "
            f"expected {test_count}, got {len(values_list)}"
        )
    if len(values_list) < test_count:
        values_list = values_list + [None] * (test_count - len(values_list))
    if len(values_list) > test_count:
        values_list = values_list[:test_count]
    if field not in entry or entry[field] is None:
        entry[field] = [None] * existing_tests
    entry[field].extend(values_list)


def _merge_test_record(
    entry: dict[str, Any],
    record: dict[str, Any],
    *,
    allow_missing_outputs: bool = True,
    log_fn: Callable[[str], None] | None = None,
) -> None:
    test_inputs = record.get("test_inputs") or []
    existing_tests = len(entry["test_inputs"])
    entry["test_inputs"].extend(test_inputs)
    test_count = len(test_inputs)

    record_outputs = record.get("test_outputs")
    if record_outputs is None:
        if allow_missing_outputs:
            entry["test_outputs"] = None
    else:
        if entry["test_outputs"] is not None:
            _extend_list_field(
                entry,
                "test_outputs",
                record_outputs,
                test_count=test_count,
                existing_tests=existing_tests,
                log_fn=log_fn,
            )

    test_list_fields = (
        "test_ids",
        "test_input_texts",
        "test_output_texts",
        "test_prompts",
        "test_targets",
        "test_conversations",
        "test_input_image_color",
        "test_input_image_annotated",
        "test_output_image_color",
        "test_output_image_annotated",
    )
    for field in test_list_fields:
        _extend_list_field(
            entry,
            field,
            record.get(field),
            test_count=test_count,
            existing_tests=existing_tests,
            log_fn=log_fn,
        )


def _group_hf_rows_by_task_id(
    rows: list[Mapping[str, Any]],
    *,
    include_images: bool,
    image_format: str,
    log_fn: Callable[[str], None] | None = None,
) -> list[dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = {}
    indexed_tests: dict[str, dict[int, dict[str, Any]]] = {}

    image_fields_train = (
        "train_input_image_color",
        "train_input_image_annotated",
        "train_output_image_color",
        "train_output_image_annotated",
    )
    test_list_fields = (
        "test_ids",
        "test_input_texts",
        "test_output_texts",
        "test_prompts",
        "test_targets",
        "test_conversations",
        "test_input_image_color",
        "test_input_image_annotated",
        "test_output_image_color",
        "test_output_image_annotated",
    )

    for row in rows:
        record = _normalize_record(
            row,
            include_images=include_images,
            image_format=image_format,
        )
        task_id = record.get("task_id") or "unknown_task"
        entry = grouped.get(task_id)
        if entry is None:
            entry = {
                "task_id": task_id,
                "training_examples": [],
                "test_inputs": [],
                "test_outputs": [],
            }
            grouped[task_id] = entry
            indexed_tests[task_id] = {}

        split = record.get("split")
        if split:
            if "split" not in entry:
                entry["split"] = split
            elif entry["split"] != split and log_fn:
                log_fn(
                    f"ARC-AGI-2 task_id={task_id} has mismatched split values in HF rows."
                )

        training_examples = record.get("training_examples") or []
        if training_examples:
            if (
                entry["training_examples"]
                and entry["training_examples"] != training_examples
            ):
                if log_fn:
                    log_fn(
                        f"ARC-AGI-2 task_id={task_id} has mismatched training examples in HF rows."
                    )
            if not entry["training_examples"]:
                entry["training_examples"] = training_examples

        for field in image_fields_train:
            value = record.get(field)
            if value is None:
                continue
            if field not in entry:
                entry[field] = value
            elif entry[field] != value and log_fn:
                log_fn(
                    f"ARC-AGI-2 task_id={task_id} has mismatched {field} values in HF rows."
                )

        test_index = _parse_test_index(row.get("id"))
        if test_index is not None and len(record.get("test_inputs") or []) == 1:
            indexed_tests[task_id][test_index] = record
        else:
            _merge_test_record(entry, record, log_fn=log_fn)

    for task_id, tests in indexed_tests.items():
        entry = grouped[task_id]
        for idx in sorted(tests):
            _merge_test_record(entry, tests[idx], log_fn=log_fn)

    records: list[dict[str, Any]] = []
    for task_id, entry in grouped.items():
        training_examples = entry.get("training_examples") or []
        test_inputs = entry.get("test_inputs") or []
        record_out: dict[str, Any] = {
            "task_id": task_id,
            "training_examples": training_examples,
            "test_inputs": test_inputs,
            "test_outputs": entry.get("test_outputs"),
        }
        if "split" in entry:
            record_out["split"] = entry["split"]
        for field in image_fields_train:
            if field in entry:
                record_out[field] = entry[field]
        for field in test_list_fields:
            if field in entry:
                record_out[field] = entry[field]
        records.append(_finalize_record(record_out))

    if not records:
        raise ValueError("No ARC-AGI-2 records loaded from HF rows.")
    return records


def _build_records(
    challenges: Mapping[str, Any] | list[Mapping[str, Any]],
    *,
    include_images: bool,
    image_format: str,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    if isinstance(challenges, Mapping):
        iterator: Iterable[tuple[str | None, Mapping[str, Any]]] = challenges.items()
    else:
        iterator = [(None, payload) for payload in challenges]

    for task_id, payload in iterator:
        record = _normalize_record(
            payload,
            include_images=include_images,
            image_format=image_format,
        )
        if task_id is not None and record.get("task_id") == "unknown_task":
            record["task_id"] = task_id
        records.append(_finalize_record(record))
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


def _load_hf_rows(source_split: str, *, use_streaming: bool) -> list[Mapping[str, Any]]:
    load_kwargs = {"path": ARC_AGI2_HF_PATH, "split": source_split}
    if use_streaming:
        try:
            return list(load_dataset(streaming=True, **load_kwargs))
        except Exception:
            return load_dataset(**load_kwargs).to_list()
    return load_dataset(**load_kwargs).to_list()


def _load_arc_agi2_split(
    source_split: str,
    start: int,
    count: int | None,
    seed: int,
) -> list[dict[str, Any]]:
    debug_load = _bool_env("ARC_AGI2_DATA_DEBUG", False)
    group_by_task = _bool_env("ARC_AGI2_GROUP_BY_TASK", True)
    include_images = _bool_env("ARC_AGI2_INCLUDE_IMAGES", False)
    image_format = os.getenv("ARC_AGI2_IMAGE_FORMAT", "PNG")
    use_streaming = _bool_env("OPIK_USE_HF_STREAMING", True)

    def _log(msg: str) -> None:
        if debug_load:
            logger.info("[arc_agi2] %s", msg)

    def _validate(records: list[dict[str, Any]], source: str) -> list[dict[str, Any]]:
        if not records:
            raise ValueError(f"No ARC-AGI-2 records loaded from {source}.")
        if count is not None and len(records) < count:
            raise RuntimeError(
                f"{source} provided {len(records)} items, fewer than requested {count}. "
                "Reduce count or relax filters."
            )
        return records

    # Hugging Face path (required).
    try:
        hf_rows = _load_hf_rows(source_split, use_streaming=use_streaming)
        if group_by_task:
            records = _group_hf_rows_by_task_id(
                hf_rows,
                include_images=include_images,
                image_format=image_format,
                log_fn=_log,
            )
        else:
            records = _build_records(
                hf_rows,
                include_images=include_images,
                image_format=image_format,
            )
        _log(
            f"HF load succeeded: {len(records)} items for split {source_split} "
            f"(group_by_task={group_by_task}, include_images={include_images})"
        )
        sliced = _shuffle_and_slice(records, start=start, count=count, seed=seed)
        return _validate(sliced, f"Hugging Face ({ARC_AGI2_HF_PATH})")
    except Exception as exc:  # pragma: no cover - network/gated dataset dependent
        raise RuntimeError(
            f"Failed to load ARC-AGI-2 from Hugging Face ({ARC_AGI2_HF_PATH}): {exc}"
        ) from exc


ARC_AGI2_SPEC = DatasetSpec(
    name="arc_agi2",
    default_source_split="train",
    hf_path=ARC_AGI2_HF_PATH,
    prefer_presets=True,
    presets={
        "train": DatasetSplitPreset(
            source_split="train", start=0, count=200, dataset_name="arc_agi2_train"
        ),
        "validation": DatasetSplitPreset(
            source_split="evaluation",
            start=0,
            count=120,
            dataset_name="arc_agi2_validation",
        ),
        "test": DatasetSplitPreset(
            source_split="evaluation", start=0, count=120, dataset_name="arc_agi2_test"
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
    filter_by: FilterBy | None = None,
) -> opik.Dataset:
    """
    Load slices of the ARC-AGI-2 dataset from Hugging Face (vincentkoc/arc-agi-2).
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
        filter_by=filter_by,
    )
