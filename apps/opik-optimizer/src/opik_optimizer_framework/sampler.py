from __future__ import annotations

import hashlib
import random

from opik_optimizer_framework.types import SplitResult


def _derive_seed(seed: int, item_count: int) -> int:
    """Derive a deterministic integer seed from the user seed, phase, and item count."""
    key = f"{seed}split{item_count}"
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


def sample_split(
    item_ids: list[str],
    seed: int,
    split_ratio: float = 0.8,
) -> SplitResult:
    """Split dataset item IDs into training and validation sets.

    Sorts alphabetically, shuffles with a deterministic seed derived from
    SHA256(str(seed) + "split" + str(len(ids))), then splits at the boundary.
    Guarantees at least 1 item per split.
    """
    if not item_ids:
        raise ValueError("Cannot split an empty list of item IDs")

    if len(item_ids) == 1:
        raise ValueError("Cannot split a single item into two non-empty sets")

    sorted_ids = sorted(item_ids)
    derived = _derive_seed(seed, len(sorted_ids))
    rng = random.Random(derived)
    rng.shuffle(sorted_ids)

    boundary = max(1, min(len(sorted_ids) - 1, round(len(sorted_ids) * split_ratio)))

    return SplitResult(
        train_item_ids=sorted_ids[:boundary],
        validation_item_ids=sorted_ids[boundary:],
        dataset_size=len(item_ids),
        seed=seed,
    )
