"""Ensure shared RNG helpers are deterministic and predictable."""

from opik_optimizer.utils import rng


def test_make_rng_reproducible() -> None:
    """Same seed/tag should give identical samples."""
    r1 = rng.make_rng(42, "tag")
    r2 = rng.make_rng(42, "tag")
    assert r1.sample([1, 2, 3], 2) == r2.sample([1, 2, 3], 2)


def test_derive_rng_isolated() -> None:
    """Deriving RNGs with different tags should diverge."""
    parent = rng.make_rng(7)
    child_a = rng.derive_rng(parent, "a")
    child_b = rng.derive_rng(parent, "b")
    assert child_a.sample(list(range(10)), 3) != child_b.sample(list(range(10)), 3)


def test_batched_without_shuffle() -> None:
    """Batch without shuffle preserves order and indexes increment."""
    batches = list(rng.batched([1, 2, 3, 4, 5], 2))
    assert [b.items for b in batches] == [[1, 2], [3, 4], [5]]
    assert [b.index for b in batches] == [0, 1, 2]


def test_batched_with_shuffle_reproducible() -> None:
    """Shuffled batches should be reproducible under the same seed."""
    r = rng.make_rng(123, "batch")
    batches = list(rng.batched([1, 2, 3, 4], 2, rng=r))
    batches2 = list(rng.batched([1, 2, 3, 4], 2, rng=rng.make_rng(123, "batch")))
    assert [b.items for b in batches] == [b.items for b in batches2]
