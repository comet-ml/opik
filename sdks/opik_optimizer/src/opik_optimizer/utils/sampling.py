"""Sampling helpers to keep dataset selection consistent across optimizers."""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any

DEFAULT_CAP = 100


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


def resolve_sampling(
    dataset: Any,
    n_samples: int | str | None,
    dataset_item_ids: list[str] | None = None,
    *,
    default_cap: int = DEFAULT_CAP,
    phase: str = "train",
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
            mode=phase,
            dataset_size=dataset_size,
        )

    if normalized is None:
        # None -> full dataset
        return SamplingPlan(
            nb_samples=None,
            dataset_item_ids=None,
            mode=f"{phase}:full",
            dataset_size=dataset_size,
        )

    effective = normalized

    if dataset_size is not None and effective > dataset_size:
        effective = dataset_size

    return SamplingPlan(
        nb_samples=effective,
        dataset_item_ids=None,
        mode=f"{phase}:sampled",
        dataset_size=dataset_size,
    )


def with_ids(plan: SamplingPlan, dataset_item_ids: list[str]) -> SamplingPlan:
    """Attach explicit ids to a plan."""
    return replace(
        plan,
        dataset_item_ids=list(dataset_item_ids),
        nb_samples=len(dataset_item_ids),
    )
