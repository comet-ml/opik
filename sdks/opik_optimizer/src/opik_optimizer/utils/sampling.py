"""Sampling helpers to keep dataset selection consistent across optimizers.

`n_samples` accepts:
- integer counts (e.g., 50)
- fractions between 0 and 1 (e.g., 0.1 = 10%)
- percent strings (e.g., "10%")
- the strings "all"/"full" or None for the full dataset
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import math
import random

from .. import constants
from . import rng as rng_utils

DEFAULT_STRATEGY = constants.DEFAULT_N_SAMPLES_STRATEGY

__all__ = ["SamplingPlan", "resolve_sampling", "DEFAULT_STRATEGY"]


@dataclass(frozen=True)
class SamplingPlan:
    """Normalized sampling parameters for a dataset evaluation phase.

    - nb_samples: number of items to evaluate (None means full dataset).
    - dataset_item_ids: explicit item IDs to evaluate (mutually exclusive with nb_samples).
    - mode: string describing the sampling mode (e.g. "eval:full", "eval:epoch_shuffled").
    - dataset_size: total dataset size when available.
    """

    nb_samples: int | None
    dataset_item_ids: list[str] | None
    mode: str
    dataset_size: int | None


def _normalize_n_samples(n_samples: int | float | str | None) -> int | float | None:
    """Normalize user-provided n_samples into an integer count or fraction.

    Accepts:
    - int > 0: evaluate exactly that many items.
    - float in (0, 1]: fraction of dataset to evaluate (e.g., 0.1 = 10%).
    - percent strings like "10%".
    - "all" / "full" (case-insensitive) or None: evaluate the full dataset.
    """
    if isinstance(n_samples, str):
        value = n_samples.strip()
        if value.lower() in {"full", "all"}:
            return None
        if value.endswith("%"):
            raw = value[:-1].strip()
            try:
                percent = float(raw)
            except ValueError as exc:
                raise ValueError(
                    "n_samples percent must be a number like '10%'"
                ) from exc
            if not math.isfinite(percent) or percent <= 0:
                raise ValueError("n_samples percent must be > 0")
            if percent > 100:
                raise ValueError("n_samples percent must be <= 100")
            return percent / 100.0
        try:
            parsed = int(value)
        except ValueError:
            try:
                fraction = float(value)
            except ValueError as exc:
                raise ValueError(
                    "n_samples must be an int, a fraction, a percent string, "
                    "'full', 'all', or None"
                ) from exc
            if not math.isfinite(fraction) or fraction <= 0:
                raise ValueError("n_samples fraction must be > 0")
            if fraction > 1:
                raise ValueError("n_samples fraction must be <= 1")
            return fraction
        if parsed <= 0:
            raise ValueError("n_samples must be > 0 when provided as int")
        return parsed

    if isinstance(n_samples, int):
        if n_samples <= 0:
            raise ValueError("n_samples must be > 0 when provided as int")
        return n_samples
    if isinstance(n_samples, float):
        if not math.isfinite(n_samples) or n_samples <= 0:
            raise ValueError("n_samples fraction must be > 0")
        if n_samples > 1:
            raise ValueError("n_samples fraction must be <= 1")
        return n_samples

    return None


def _extract_ids(dataset: Any) -> list[str]:
    """Extract dataset item IDs as strings, returning an empty list on failure."""
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


def _random_sorted_ids(
    *,
    dataset: Any,
    nb_samples: int,
    rng: random.Random,
) -> list[str]:
    """Deterministically shuffle item IDs and return the first nb_samples IDs."""
    ids = _extract_ids(dataset)
    if not ids:
        return []
    ids_sorted = sorted(ids)
    rng.shuffle(ids_sorted)
    return ids_sorted[:nb_samples]


def resolve_sampling(
    dataset: Any,
    n_samples: int | float | str | None,
    *,
    dataset_item_ids: list[str] | None = None,
    strategy: str = DEFAULT_STRATEGY,
    phase: str = "train",
    seed: int | None = None,
) -> SamplingPlan:
    """Normalize sampling inputs into a single plan.

    Dataset item IDs take precedence over n_samples. For n_samples, only the
    default strategy ("epoch_shuffled") is supported today.

    Fractional n_samples values are converted to a count using
    ceil(dataset_size * fraction), with a minimum of 1 when the dataset is non-empty.
    """
    normalized = _normalize_n_samples(n_samples)
    dataset_size: int | None = None
    try:
        dataset_size = len(dataset.get_items())
    except Exception:
        dataset_size = None

    if dataset_item_ids is not None:
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
    if isinstance(normalized, float):
        if normalized >= 1.0:
            return SamplingPlan(
                nb_samples=None,
                dataset_item_ids=None,
                mode=f"{phase}:full",
                dataset_size=dataset_size,
            )
        if dataset_size is None:
            raise ValueError("n_samples fraction requires dataset size")
        if dataset_size <= 0:
            effective = 0
        else:
            effective = max(1, math.ceil(dataset_size * normalized))
        normalized = effective

    effective = normalized
    if dataset_size is not None and effective > dataset_size:
        effective = dataset_size

    rng = rng_utils.make_rng(seed, phase, strategy, effective or 0)
    if strategy != DEFAULT_STRATEGY:
        raise ValueError(f"Unsupported sampling strategy: {strategy}")

    sampled_ids = _random_sorted_ids(dataset=dataset, nb_samples=effective, rng=rng)
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
