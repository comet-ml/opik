from __future__ import annotations

from typing import Any

from opik_optimizer.utils import sampling
from tests.unit.fixtures.builders import make_mock_dataset


def _make_sequential_dataset(
    count: int, *, name: str = "seq-dataset", dataset_id: str = "seq-123"
) -> Any:
    items = [{"id": str(i), "value": i} for i in range(count)]
    return make_mock_dataset(items, name=name, dataset_id=dataset_id)


def test_random_sorted_sampling_is_deterministic_per_phase() -> None:
    dataset = _make_sequential_dataset(20)
    plan_round_0 = sampling.resolve_sampling(
        dataset=dataset,
        n_samples=5,
        phase="eval:round:0",
        seed=42,
    )
    plan_round_0_repeat = sampling.resolve_sampling(
        dataset=dataset,
        n_samples=5,
        phase="eval:round:0",
        seed=42,
    )

    plan_round_1 = sampling.resolve_sampling(
        dataset=dataset,
        n_samples=5,
        phase="eval:round:1",
        seed=42,
    )

    assert plan_round_0.dataset_item_ids == plan_round_0_repeat.dataset_item_ids
    assert plan_round_0.dataset_item_ids != plan_round_1.dataset_item_ids


def test_resolve_sampling_accepts_full_or_all_aliases() -> None:
    dataset = _make_sequential_dataset(6)

    plan_full = sampling.resolve_sampling(
        dataset=dataset,
        n_samples="full",
        phase="train",
        seed=7,
    )
    plan_all = sampling.resolve_sampling(
        dataset=dataset,
        n_samples="all",
        phase="train",
        seed=7,
    )

    assert plan_full.nb_samples is None
    assert plan_full.dataset_item_ids is None
    assert plan_full.mode.endswith(":full")
    assert plan_all.nb_samples is None
    assert plan_all.dataset_item_ids is None
    assert plan_all.mode.endswith(":full")


def test_resolve_sampling_clamps_to_dataset_size() -> None:
    dataset = _make_sequential_dataset(3)
    plan = sampling.resolve_sampling(
        dataset=dataset,
        n_samples=10,
        phase="train",
        seed=99,
    )

    assert plan.nb_samples == 3
    assert plan.dataset_item_ids is not None
    assert len(plan.dataset_item_ids) == 3
