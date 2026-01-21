"""Sampling helpers to keep dataset selection consistent across optimizers."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import random

from .. import constants
from . import rng as rng_utils

DEFAULT_STRATEGY = constants.DEFAULT_N_SAMPLE_STRATEGY

__all__ = ["SamplingPlan", "resolve_sampling", "DEFAULT_STRATEGY"]


@dataclass(frozen=True)
class SamplingPlan:
    """Normalized sampling parameters for a dataset."""

    nb_samples: int | None
    dataset_item_ids: list[str] | None
    mode: str
    dataset_size: int | None


def _normalize_n_samples(n_samples: int | str | None) -> int | None:
    if isinstance(n_samples, str):
        if n_samples.lower() in {"full", "all"}:
            return None
        try:
            parsed = int(n_samples)
        except ValueError as exc:
            raise ValueError(
                "n_samples must be an int, 'full', 'all', or None"
            ) from exc
        if parsed <= 0:
            raise ValueError("n_samples must be > 0 when provided as int")
        return parsed

    if isinstance(n_samples, int):
        if n_samples <= 0:
            raise ValueError("n_samples must be > 0 when provided as int")
        return n_samples

    return None


def _extract_ids(dataset: Any) -> list[str]:
    try:
        items = dataset.get_items()
    except Exception:
        return []
    ids = [
        str(item["id"])
        for item in items
        if isinstance(item, dict) and item.get("id") is not None
    ]
    return ids


def _epoch_shuffled_ids(
    *,
    dataset: Any,
    nb_samples: int,
    rng: random.Random,
) -> list[str]:
    ids = _extract_ids(dataset)
    if not ids:
        return []
    ids_sorted = sorted(ids)
    rng.shuffle(ids_sorted)
    return ids_sorted[:nb_samples]


def resolve_sampling(
    dataset: Any,
    n_samples: int | str | None,
    *,
    dataset_item_ids: list[str] | None = None,
    strategy: str = DEFAULT_STRATEGY,
    phase: str = "train",
    seed: int | None = None,
) -> SamplingPlan:
    """Normalize sampling inputs into a single plan."""
    normalized = _normalize_n_samples(n_samples)
    dataset_size: int | None = None
    try:
        dataset_size = len(dataset.get_items())
    except Exception:
        dataset_size = None

    if dataset_item_ids:
        return SamplingPlan(
            nb_samples=len(dataset_item_ids),
            dataset_item_ids=list(dataset_item_ids),
            mode=f"{phase}:ids",
            dataset_size=dataset_size,
        )

    if normalized is None:
        return SamplingPlan(
            nb_samples=None,
            dataset_item_ids=None,
            mode=f"{phase}:full",
            dataset_size=dataset_size,
        )

    effective = normalized
    if dataset_size is not None and effective > dataset_size:
        effective = dataset_size

    rng = rng_utils.make_rng(seed, phase, strategy, effective or 0)
    if strategy != DEFAULT_STRATEGY:
        raise ValueError(f"Unsupported sampling strategy: {strategy}")

    sampled_ids = _epoch_shuffled_ids(dataset=dataset, nb_samples=effective, rng=rng)
    if sampled_ids:
        return SamplingPlan(
            nb_samples=len(sampled_ids),
            dataset_item_ids=sampled_ids,
            mode=f"{phase}:{strategy}",
            dataset_size=dataset_size,
        )

    return SamplingPlan(
        nb_samples=effective,
        dataset_item_ids=None,
        mode=f"{phase}:{strategy}",
        dataset_size=dataset_size,
    )
