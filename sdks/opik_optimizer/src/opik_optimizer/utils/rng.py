"""Deterministic RNG helpers shared across optimizers."""

from __future__ import annotations

import hashlib
import random
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")


def _hash_seed(seed: int | None, *parts: object) -> int | None:
    """Derive a deterministic seed from a base seed and contextual tags."""
    if seed is None and not parts:
        return None
    namespace = "|".join(str(part) for part in (seed, *parts))
    digest = hashlib.sha256(namespace.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


def make_rng(seed: int | None, *tags: object) -> random.Random:
    """Create a deterministic RNG optionally namespaced by tags."""
    derived = _hash_seed(seed, *tags)
    return random.Random(derived)


def derive_rng(parent: random.Random, *tags: object) -> random.Random:
    """Derive a child RNG from a parent RNG and contextual tags."""
    # Consume a small amount of entropy from the parent to keep derivations stable
    parent_entropy = parent.getrandbits(64)
    derived = _hash_seed(parent_entropy, *tags)
    return random.Random(derived)


def sample_ids(rng: random.Random, ids: Sequence[T], k: int) -> list[T]:
    """Sample up to k ids deterministically without modifying the source."""
    if k <= 0:
        raise ValueError("k must be > 0")
    if k >= len(ids):
        return list(ids)
    return rng.sample(list(ids), k)


def shuffle(rng: random.Random, seq: Sequence[T]) -> list[T]:
    """Return a shuffled copy of seq using the provided RNG."""
    items = list(seq)
    rng.shuffle(items)
    return items


@dataclass(frozen=True)
class Batch(Generic[T]):
    """Represents a batch slice."""

    index: int
    items: list[T]


def batched(
    seq: Sequence[T],
    batch_size: int,
    *,
    rng: random.Random | None = None,
    drop_last: bool = False,
) -> Iterator[Batch[T]]:
    """Yield deterministic batches of a sequence."""
    if batch_size <= 0:
        raise ValueError("batch_size must be > 0")

    items = list(seq)
    if rng is not None:
        rng.shuffle(items)

    batch_index = 0
    for start in range(0, len(items), batch_size):
        batch = items[start : start + batch_size]
        if drop_last and len(batch) < batch_size:
            break
        yield Batch(index=batch_index, items=batch)
        batch_index += 1
