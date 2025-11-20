from __future__ import annotations

import hashlib
import os
import secrets
import time
import warnings
import itertools
from collections.abc import Callable, Iterable, Sequence
import logging
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from typing import Any

import opik
from datasets import load_dataset

from opik_optimizer.api_objects.types import DatasetSpec


logger = logging.getLogger(__name__)


@lru_cache(maxsize=None)
def dataset_suffix(package: str, filename: str) -> str:
    """Return a stable checksum-based suffix for a JSON/JSONL file shipped with the SDK."""
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


def add_record_index(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Shallow-copy records and inject a stable `_record_index` field for dedup-sensitive datasets."""
    return [{**record, "_record_index": idx} for idx, record in enumerate(records)]


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


def dataset_name_for_mode(dataset_name: str, test_mode: bool) -> str:
    """Apply the `_sample` suffix when test mode datasets are created."""
    if test_mode and not dataset_name.endswith("_sample"):
        return f"{dataset_name}_sample"
    return dataset_name


def create_dataset_from_records(
    *,
    dataset_name: str,
    records: Sequence[dict[str, Any]],
    expected_size: int,
    test_mode: bool,
) -> opik.Dataset:
    """Create or reuse an Opik dataset with the provided records and size checks."""
    full_name = dataset_name_for_mode(dataset_name, test_mode)
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


def stream_records_for_slice(
    *,
    load_fn: Callable[..., Any],
    load_kwargs: dict[str, Any],
    start: int,
    count: int | None,
) -> list[dict[str, Any]]:
    """
    Stream from HF without downloading full shards.
    """
    streaming_set = load_fn(streaming=True, **load_kwargs)
    iterator = iter(streaming_set)

    if start:
        iterator = itertools.islice(iterator, start, None)

    if count is None:
        return list(iterator)

    return list(itertools.islice(iterator, count))


def fetch_records_for_slice(
    *,
    slice_request: SliceRequest,
    load_kwargs_resolver: Callable[[str], dict[str, Any]],
    seed: int,
    custom_loader: Callable[[str, int, int | None, int], list[dict[str, Any]]]
    | None = None,
    load_fn: Callable[..., Any] = load_dataset,
) -> list[dict[str, Any]]:
    """
    Fetch the raw records for a slice request using either a custom loader or HF datasets.

    Args:
        slice_request: Resolved coordinates describing which split/start/count to stream.
        load_kwargs_resolver: Callable producing kwargs for ``load_dataset``.
        seed: Deterministic shuffle seed.
        custom_loader: Optional callable overriding the default HF download path.
        load_fn: Loader function (defaults to ``datasets.load_dataset``).
    """
    if custom_loader is not None:
        return custom_loader(
            slice_request.source_split,
            slice_request.start,
            slice_request.count,
            seed,
        )

    load_kwargs = load_kwargs_resolver(slice_request.source_split)

    # Streaming by default to avoid full HF downloads in smoke/test scenarios.
    # Disable by setting OPIK_USE_HF_STREAMING=false when a full materialized
    # dataset is explicitly required.
    use_streaming = os.getenv("OPIK_USE_HF_STREAMING", "true").lower() == "true"
    if use_streaming:
        try:
            return stream_records_for_slice(
                load_fn=load_fn,
                load_kwargs=load_kwargs,
                start=slice_request.start,
                count=slice_request.count,
            )
        except Exception:
            # If streaming is unavailable for this dataset, fall back to full download.
            pass

    return download_and_slice_hf_dataset(
        load_fn=load_fn,
        load_kwargs=load_kwargs,
        start=slice_request.start,
        count=slice_request.count,
        seed=seed,
    )


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


# Accepted short-hands for split names so callers can pass `split="val"` etc.
_SPLIT_ALIASES = {
    "val": "validation",
    "valid": "validation",
    "evaluation": "validation",
    "dev": "validation",
}


@dataclass(frozen=True)
class SliceRequest:
    """Resolved description of which HF slice to stream and what to call it."""

    source_split: str
    start: int
    count: int | None
    dataset_name: str


def resolve_slice_request(
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
) -> SliceRequest:
    """Resolve a user request into a concrete HF slice definition."""
    normalized_split = (requested_split or default_source_split).lower()
    normalized_split = _SPLIT_ALIASES.get(normalized_split, normalized_split)
    preset = presets.get(normalized_split)
    if requested_split is None and not prefer_presets:
        preset = None

    source_split = _resolve_slice_field(
        explicit=requested_split,
        preset=preset,
        preset_key="source_split",
        default=default_source_split,
        coerce=str,
        fallback_alias=True,
    )

    resolved_start = _resolve_slice_field(
        explicit=start,
        preset=preset,
        preset_key="start",
        default=default_start,
        coerce=int,
    )

    resolved_count = _resolve_slice_field(
        explicit=count,
        preset=preset,
        preset_key="count",
        default=default_count,
        coerce=lambda value: int(value) if value is not None else None,
    )

    if dataset_name:
        resolved_name = dataset_name
    elif preset and "dataset_name" in preset:
        resolved_name = preset["dataset_name"]
    else:
        resolved_name = default_dataset_name(
            base=base_name,
            split=normalized_split if requested_split is not None else source_split,
            start=resolved_start,
            count=resolved_count,
        )

    return SliceRequest(
        source_split=source_split,
        start=resolved_start,
        count=resolved_count,
        dataset_name=resolved_name,
    )


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
    """
    Backwards-compatible wrapper returning the tuple format previously used.

    Prefer :func:`resolve_slice_request` for new call sites.
    """
    slice_request = resolve_slice_request(
        base_name=base_name,
        requested_split=requested_split,
        presets=presets,
        default_source_split=default_source_split,
        default_start=default_start,
        default_count=default_count,
        start=start,
        count=count,
        dataset_name=dataset_name,
        prefer_presets=prefer_presets,
    )
    return (
        slice_request.source_split,
        slice_request.start,
        slice_request.count,
        slice_request.dataset_name,
    )


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
    records_transform: Callable[[list[dict[str, Any]]], list[dict[str, Any]]]
    | None = None,
    custom_loader: Callable[[str, int, int | None, int], list[dict[str, Any]]]
    | None = None,
) -> opik.Dataset:
    """Shared helper to download an HF slice and create an Opik dataset.

    The ``seed`` parameter is threaded all the way from public dataset helpers,
    ensuring callers can deterministically reproduce any slice by setting a
    global env var or passing ``seed=...`` explicitly.
    """
    use_presets = (
        prefer_presets if prefer_presets is not None else requested_split is not None
    )
    effective_count = resolve_test_mode_count(test_mode_count) if test_mode else count
    slice_request = resolve_slice_request(
        base_name=base_name,
        requested_split=requested_split,
        presets=presets,
        default_source_split=default_source_split,
        default_start=0,
        default_count=None,
        start=start,
        count=effective_count,
        dataset_name=dataset_name,
        prefer_presets=use_presets,
    )

    resolved_seed = resolve_dataset_seed(seed)
    effective_test_count = resolve_test_mode_count(test_mode_count)

    logger.info(
        "Dataset slice resolved: %s (split=%s start=%s count=%s test_mode=%s streaming=%s seed=%s)",
        slice_request.dataset_name,
        slice_request.source_split,
        slice_request.start,
        slice_request.count,
        test_mode,
        os.getenv("OPIK_USE_HF_STREAMING", "true"),
        resolved_seed,
    )

    records = fetch_records_for_slice(
        slice_request=slice_request,
        load_kwargs_resolver=load_kwargs_resolver,
        seed=resolved_seed,
        custom_loader=custom_loader,
        load_fn=load_fn,
    )

    slice_size = len(records)
    expected_items = effective_test_count if test_mode else slice_size
    if records_transform is not None:
        records = records_transform(records)
        slice_size = len(records)
        expected_items = effective_test_count if test_mode else slice_size
    logger.info(
        "Dataset fetch complete: %s (split=%s fetched=%s expected=%s test_mode=%s)",
        slice_request.dataset_name,
        slice_request.source_split,
        slice_size,
        expected_items,
        test_mode,
    )
    if test_mode:
        records = records[:expected_items]

    return create_dataset_from_records(
        dataset_name=slice_request.dataset_name,
        records=records,
        expected_size=expected_items,
        test_mode=test_mode,
    )


class DatasetHandle:
    """High-level interface for datasets defined via DatasetSpec."""

    def __init__(self, spec: DatasetSpec) -> None:
        self.spec = spec
        self._load_kwargs_resolver = (
            spec.load_kwargs_resolver or _default_load_kwargs_resolver(spec)
        )
        self._presets = {
            name: preset.model_dump() for name, preset in spec.presets.items()
        }

    def load(
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
        """
        Load the dataset slice described by this spec.

        Args mirror the public dataset helpers; notably ``seed`` controls the
        deterministic shuffle performed inside ``download_and_slice_hf_dataset``,
        so callers can fully reproduce slices by passing ``seed`` all the way
        through the public API.
        """
        if prefer_presets is None:
            no_overrides = (
                split is None
                and start is None
                and count is None
                and dataset_name is None
            )
            pref = self.spec.prefer_presets and no_overrides
        else:
            pref = prefer_presets

        return load_hf_dataset_slice(
            base_name=self.spec.name,
            requested_split=split,
            presets=self._presets,
            default_source_split=self.spec.default_source_split,
            load_kwargs_resolver=self._load_kwargs_resolver,
            start=start,
            count=count,
            dataset_name=dataset_name,
            test_mode=test_mode,
            seed=seed,
            test_mode_count=test_mode_count,
            prefer_presets=pref,
            records_transform=self.spec.records_transform,
            custom_loader=self.spec.custom_loader,
        )


def _default_load_kwargs_resolver(spec: DatasetSpec) -> Callable[[str], dict[str, Any]]:
    if spec.hf_path is None:
        raise ValueError(
            f"DatasetSpec '{spec.name}' must define either hf_path or load_kwargs_resolver."
        )

    def resolver(split: str) -> dict[str, Any]:
        kwargs: dict[str, Any] = {"path": spec.hf_path, "split": split}
        if spec.hf_name is not None:
            kwargs["name"] = spec.hf_name
        return kwargs

    return resolver


def warn_deprecated_dataset(old_name: str, replacement_hint: str) -> None:
    """Emit a consistent deprecation warning for legacy dataset helpers."""
    warnings.warn(
        f"{old_name}() is deprecated; use {replacement_hint} instead.",
        DeprecationWarning,
        stacklevel=2,
    )


__all__ = [
    "dataset_suffix",
    "generate_uuid7_str",
    "attach_uuids",
    "resolve_dataset_seed",
    "resolve_test_mode_count",
    "dataset_name_for_mode",
    "create_dataset_from_records",
    "download_and_slice_hf_dataset",
    "fetch_records_for_slice",
    "default_dataset_name",
    "add_record_index",
    "SliceRequest",
    "resolve_slice_request",
    "resolve_preset_split",
    "load_hf_dataset_slice",
    "DatasetHandle",
    "warn_deprecated_dataset",
]


def _resolve_slice_field(
    *,
    explicit: Any,
    preset: dict[str, Any] | None,
    preset_key: str,
    default: Any,
    coerce: Callable[[Any], Any],
    fallback_alias: bool = False,
) -> Any:
    """
    Helper to resolve `start`/`count`/`split` values with consistent precedence.

    Args:
        explicit: Value provided directly by the caller.
        preset: Optional preset dict for the requested split.
        preset_key: Key to look up inside the preset.
        default: Fallback if neither explicit nor preset provided.
        coerce: Callable to coerce the final value into the desired type.
        fallback_alias: When resolving the split name we already normalized via aliases;
            set this to ``True`` so we can reuse that normalization.
    """
    if explicit is not None:
        value = explicit
    elif preset and preset_key in preset:
        value = preset[preset_key]
    else:
        value = default

    if preset_key == "source_split" and fallback_alias and isinstance(value, str):
        value = _SPLIT_ALIASES.get(value.lower(), value)

    return coerce(value)
