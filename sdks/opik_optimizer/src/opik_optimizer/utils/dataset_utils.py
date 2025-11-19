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
    count: int,
    seed: int,
) -> list[dict[str, Any]]:
    """Download a HF dataset split, shuffle deterministically, and slice."""
    hf_dataset = load_fn(**load_kwargs)
    shuffled = hf_dataset.shuffle(seed=seed)
    end_index = start + count
    if end_index > len(shuffled):
        raise ValueError(
            f"Requested slice [{start}, {end_index}) exceeds dataset size {len(shuffled)}."
        )
    subset = shuffled.select(range(start, end_index))
    return subset.to_list()


__all__ = [
    "dataset_suffix",
    "generate_uuid7_str",
    "attach_uuids",
    "resolve_dataset_seed",
    "resolve_test_mode_count",
    "create_dataset_from_records",
    "download_and_slice_hf_dataset",
]
