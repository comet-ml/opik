from __future__ import annotations

import hashlib
import os
import secrets
import time
from functools import lru_cache
from importlib import resources
from typing import Any
from collections.abc import Sequence
from collections.abc import Callable, Iterable

import opik
from datasets import load_dataset


@lru_cache(maxsize=None)
def dataset_suffix(package: str, filename: str) -> str:
    """Return a stable checksum-based suffix for a JSONL dataset file."""
    text = resources.files(package).joinpath(filename).read_text(encoding="utf-8")
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:8]


def generate_uuid7_str() -> str:
    """Generate a UUIDv7-compatible string, emulating the layout if unavailable."""
    import uuid

    if hasattr(uuid, "uuid7"):
        return str(uuid.uuid7())  # type: ignore[attr-defined]

    unix_ts_ms = int(time.time() * 1000) & ((1 << 48) - 1)
    rand_a = secrets.randbits(12)
    rand_b = secrets.randbits(62)

    uuid_int = unix_ts_ms << 80
    uuid_int |= 0x7 << 76  # version 7
    uuid_int |= rand_a << 64
    uuid_int |= 0b10 << 62  # RFC4122 variant
    uuid_int |= rand_b

    return str(uuid.UUID(int=uuid_int))


def attach_uuids(records: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Copy records and assign a fresh UUIDv7 `id` to each."""
    payload: list[dict[str, Any]] = []
    for record in records:
        rec = dict(record)
        rec["id"] = generate_uuid7_str()
        payload.append(rec)
    return payload


def resolve_dataset_seed(seed: int | None) -> int:
    """Return the provided seed or fall back to the global default (env override)."""
    return seed if seed is not None else int(os.getenv("OPIK_DATASET_SEED", "42"))


def resolve_test_mode_count(test_count: int | None) -> int:
    """Return the provided test-mode size or the global default."""
    return (
        test_count
        if test_count is not None
        else int(os.getenv("OPIK_DATASET_TEST_COUNT", "5"))
    )


def create_dataset_from_records(
    *,
    dataset_name: str,
    records: Sequence[dict[str, Any]],
    expected_size: int,
    test_mode: bool,
) -> opik.Dataset:
    """Create or reuse an Opik dataset with the provided records and size checks."""
    suffix = "_sample" if test_mode and not dataset_name.endswith("_sample") else ""
    full_name = f"{dataset_name}{suffix}"
    client = opik.Opik()
    dataset = client.get_or_create_dataset(full_name)
    existing = dataset.get_items()

    if existing:
        if len(existing) != expected_size:
            raise ValueError(
                f"Dataset {full_name} contains {len(existing)} items, expected {expected_size}. "
                "Delete it to recreate."
            )
        return dataset

    records_with_ids = attach_uuids(records)
    dataset.insert(records_with_ids)
    return dataset


def download_and_slice_hf_dataset(
    *,
    load_fn: Callable[..., Any],
    load_kwargs: dict[str, Any],
    start: int,
    count: int | None,
    seed: int,
) -> list[dict[str, Any]]:
    """Download a HF dataset split, shuffle deterministically, and slice.

    Args:
        load_fn: Hugging Face `load_dataset` (or compatible) callable.
        load_kwargs: Keyword arguments passed to `load_fn`.
        start: Starting index within the shuffled split.
        count: Number of rows to return. If ``None`` we include everything from
            ``start`` to the end of the split.
        seed: Deterministic shuffle seed.
    """
    hf_dataset = load_fn(**load_kwargs)
    shuffled = hf_dataset.shuffle(seed=seed)
    total = len(shuffled)

    if start < 0 or start > total:
        raise ValueError(
            f"Requested start index {start} is outside dataset size {total}."
        )

    end_index = total if count is None else start + count
    if end_index > total:
        raise ValueError(
            f"Requested slice [{start}, {end_index}) exceeds dataset size {total}."
        )

    subset = shuffled.select(range(start, end_index))
    return subset.to_list()


def default_dataset_name(
    *,
    base: str,
    split: str,
    start: int,
    count: int | None,
) -> str:
    """Generate a deterministic dataset name for ad-hoc slices."""
    parts = [base]
    if split:
        parts.append(split)
    if start:
        parts.append(f"from{start}")
    parts.append(str(count) if count is not None else "full")
    return "_".join(parts)


def resolve_preset_split(
    *,
    base_name: str,
    requested_split: str | None,
    presets: dict[str, dict[str, Any]],
    default_source_split: str,
    default_start: int = 0,
    default_count: int | None = None,
    start: int | None = None,
    count: int | None = None,
    dataset_name: str | None = None,
    prefer_presets: bool = True,
) -> tuple[str, int, int | None, str]:
    """Resolve a user request into concrete HF slice coordinates."""
    normalized_split = (requested_split or default_source_split).lower()
    normalized_split = _SPLIT_ALIASES.get(normalized_split, normalized_split)
    preset = presets.get(normalized_split)
    if requested_split is None and not prefer_presets:
        preset = None

    source_split = (
        preset.get("source_split")
        if preset and "source_split" in preset
        else default_source_split
    )
    resolved_start = start if start is not None else (
        preset.get("start") if preset and "start" in preset else default_start
    )
    resolved_count = count if count is not None else (
        preset.get("count") if preset and "count" in preset else default_count
    )

    if dataset_name:
        resolved_name = dataset_name
    elif preset and "dataset_name" in preset:
        resolved_name = preset["dataset_name"]
    else:
        resolved_name = default_dataset_name(
            base=base_name,
            split=normalized_split,
            start=resolved_start,
            count=resolved_count,
        )

    return source_split, resolved_start, resolved_count, resolved_name


_SPLIT_ALIASES = {
    "val": "validation",
    "dev": "validation",
}


def load_hf_dataset_slice(
    *,
    base_name: str,
    requested_split: str | None,
    presets: dict[str, dict[str, Any]],
    default_source_split: str,
    load_kwargs_resolver: Callable[[str], dict[str, Any]],
    load_fn: Callable[..., Any] = load_dataset,
    start: int | None = None,
    count: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
    prefer_presets: bool | None = None,
    records_transform: Callable[[list[dict[str, Any]]], list[dict[str, Any]]] | None = None,
    custom_loader: Callable[[str, int, int | None, int], list[dict[str, Any]]] | None = None,
) -> opik.Dataset:
    """Shared helper to download an HF slice and create an Opik dataset."""
    use_presets = (
        prefer_presets if prefer_presets is not None else requested_split is not None
    )
    source_split, resolved_start, resolved_count, target_name = resolve_preset_split(
        base_name=base_name,
        requested_split=requested_split,
        presets=presets,
        default_source_split=default_source_split,
        start=start,
        count=count,
        dataset_name=dataset_name,
        prefer_presets=use_presets,
    )

    resolved_seed = resolve_dataset_seed(seed)
    effective_test_count = resolve_test_mode_count(test_mode_count)

    if custom_loader is not None:
        records = custom_loader(source_split, resolved_start, resolved_count, resolved_seed)
    else:
        load_kwargs = load_kwargs_resolver(source_split)
        records = download_and_slice_hf_dataset(
            load_fn=load_fn,
            load_kwargs=load_kwargs,
            start=resolved_start,
            count=resolved_count,
            seed=resolved_seed,
        )

    slice_size = len(records)
    expected_items = effective_test_count if test_mode else slice_size
    if records_transform is not None:
        records = records_transform(records)
        slice_size = len(records)
        expected_items = effective_test_count if test_mode else slice_size
    if test_mode:
        records = records[:expected_items]

    return create_dataset_from_records(
        dataset_name=target_name,
        records=records,
        expected_size=expected_items,
        test_mode=test_mode,
    )


class OptimizerDatasetLoader:
    """Convenience wrapper for HuggingFace datasets with optional split presets."""

    def __init__(
        self,
        *,
        base_name: str,
        default_source_split: str = "train",
        load_kwargs_resolver: Callable[[str], dict[str, Any]],
        presets: dict[str, dict[str, Any]] | None = None,
        prefer_presets: bool = False,
        records_transform: Callable[[list[dict[str, Any]]], list[dict[str, Any]]] | None = None,
        custom_loader: Callable[[str, int, int | None, int], list[dict[str, Any]]] | None = None,
    ) -> None:
        self.base_name = base_name
        self.default_source_split = default_source_split
        self.load_kwargs_resolver = load_kwargs_resolver
        self.presets = presets or {}
        self.prefer_presets = prefer_presets
        self.records_transform = records_transform
        self.custom_loader = custom_loader

    def __call__(
        self,
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
        if prefer_presets is None:
            no_overrides = (
                split is None
                and start is None
                and count is None
                and dataset_name is None
            )
            pref = self.prefer_presets and no_overrides
        else:
            pref = prefer_presets
        return load_hf_dataset_slice(
            base_name=self.base_name,
            requested_split=split,
            presets=self.presets,
            default_source_split=self.default_source_split,
            load_kwargs_resolver=self.load_kwargs_resolver,
            start=start,
            count=count,
            dataset_name=dataset_name,
            test_mode=test_mode,
            seed=seed,
            test_mode_count=test_mode_count,
            prefer_presets=pref,
            records_transform=self.records_transform,
            custom_loader=self.custom_loader,
        )


__all__ = [
    "dataset_suffix",
    "generate_uuid7_str",
    "attach_uuids",
    "resolve_dataset_seed",
    "resolve_test_mode_count",
    "create_dataset_from_records",
    "download_and_slice_hf_dataset",
    "default_dataset_name",
    "resolve_preset_split",
    "load_hf_dataset_slice",
    "OptimizerDatasetLoader",
]
