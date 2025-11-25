"""Sanity checks for sampling.resolve_sampling edge cases and defaults."""

import pytest

from opik_optimizer.utils import sampling


class _MockDataset:
    def __init__(self, ids: list[str]) -> None:
        self._ids = ids

    def get_items(self, n: int | None = None) -> list[dict[str, str]]:
        items = [{"id": _id} for _id in self._ids]
        return items if n is None else items[:n]

    def __len__(self) -> int:
        return len(self._ids)


def test_resolve_sampling_full_modes() -> None:
    """Full dataset modes should yield nb_samples=None and no ids."""
    dataset = _MockDataset(["a", "b", "c"])

    for val in (None, "full", "all"):
        plan = sampling.resolve_sampling(dataset, val, phase="final")
        assert plan.nb_samples is None
        assert plan.dataset_item_ids is None
        assert plan.mode.startswith("final")


@pytest.mark.parametrize(
    ("n_samples", "expected"),
    [
        (1, 1),
        (5, 3),  # clamp to dataset size
    ],
)
def test_resolve_sampling_ints(n_samples: int, expected: int) -> None:
    """Integer sampling clamps to dataset size and preserves intent."""
    dataset = _MockDataset(["a", "b", "c"])
    plan = sampling.resolve_sampling(dataset, n_samples)
    assert plan.nb_samples == expected
    assert plan.dataset_item_ids is None


def test_resolve_sampling_invalid() -> None:
    """Bad inputs should raise ValueError."""
    dataset = _MockDataset(["a"])
    with pytest.raises(ValueError):
        sampling.resolve_sampling(dataset, 0)
    with pytest.raises(ValueError):
        sampling.resolve_sampling(dataset, "nope")


def test_resolve_sampling_with_ids_overrides() -> None:
    """Explicit ids override n_samples."""
    dataset = _MockDataset(["a", "b", "c"])
    plan = sampling.resolve_sampling(dataset, None, dataset_item_ids=["b", "c"])
    assert plan.dataset_item_ids == ["b", "c"]
    assert plan.nb_samples == 2


def test_default_cap_when_unknown_size() -> None:
    """Unknown dataset size defaults to full (nb_samples=None)."""
    class _NoLenDataset:
        def get_items(self) -> list[dict[str, str]]:
            return [{"id": "x"}]

    plan = sampling.resolve_sampling(_NoLenDataset(), None)
    assert plan.nb_samples is None
    assert plan.mode.endswith("full")
