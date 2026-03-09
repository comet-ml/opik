import random

import pytest

from opik_optimizer_framework.optimizers.gepa.failure_aware_sampler import (
    FailureAwareBatchSampler,
)


class FakeLoader:
    def __init__(self, items: list[dict]):
        self._items = items

    def all_ids(self):
        return list(range(len(self._items)))

    def fetch(self, ids):
        return [self._items[i] for i in ids]

    def __len__(self):
        return len(self._items)


class FakeState:
    def __init__(self, i: int = 0):
        self.i = i


def _make_loader(n: int) -> FakeLoader:
    return FakeLoader([{"id": f"item-{i}"} for i in range(n)])


class TestNoFailureData:
    def test_returns_correct_size(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3, rng=random.Random(42))
        loader = _make_loader(10)
        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3

    def test_returns_valid_ids(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3, rng=random.Random(42))
        loader = _make_loader(10)
        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert all(0 <= idx < 10 for idx in ids)

    def test_no_duplicates(self):
        sampler = FailureAwareBatchSampler(minibatch_size=5, rng=random.Random(42))
        loader = _make_loader(10)
        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(set(ids)) == 5

    def test_clamps_to_dataset_size(self):
        sampler = FailureAwareBatchSampler(minibatch_size=10, rng=random.Random(42))
        loader = _make_loader(3)
        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3


def _make_assertion_feedback(failed_items: dict[str, list[str]], all_items: int) -> dict:
    """Build per-item feedback where failed_items maps item_id -> list of failing assertion names."""
    feedback: dict[str, dict] = {}
    for i in range(all_items):
        item_id = f"item-{i}"
        failing = failed_items.get(item_id, [])
        assertions = [{"name": name, "value": 0.0} for name in failing]
        assertions.append({"name": "always_pass", "value": 1.0})
        feedback[item_id] = {
            "score": 0.0 if failing else 1.0,
            "runs": [{"assertions": assertions}],
        }
    return feedback


class TestBalancedSampling:
    def test_failed_items_prioritized(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=4,
            min_failed_per_batch=2,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        feedback = _make_assertion_feedback(
            {"item-0": ["a1"], "item-1": ["a1"], "item-4": ["a2"]},
            all_items=10,
        )
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        failed_indices = {0, 1, 4}
        passed_indices = {2, 3, 5, 6, 7, 8, 9}
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            failed_in_batch = [idx for idx in ids if idx in failed_indices]
            passed_in_batch = [idx for idx in ids if idx in passed_indices]
            assert len(failed_in_batch) >= 2
            assert len(passed_in_batch) >= 1

    def test_balanced_split(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=6,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        feedback = _make_assertion_feedback(
            {f"item-{i}": ["a1"] for i in range(5)},
            all_items=10,
        )
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        failed_indices = {0, 1, 2, 3, 4}
        passed_indices = {5, 6, 7, 8, 9}
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            failed_in_batch = [idx for idx in ids if idx in failed_indices]
            passed_in_batch = [idx for idx in ids if idx in passed_indices]
            assert len(failed_in_batch) == 3
            assert len(passed_in_batch) == 3

    def test_all_items_failed(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=2,
            rng=random.Random(42),
        )
        loader = _make_loader(5)
        sampler._ensure_mapping(loader)

        feedback = _make_assertion_feedback(
            {f"item-{i}": ["a1"] for i in range(5)},
            all_items=5,
        )
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3
        assert len(set(ids)) == 3

    def test_fewer_failed_than_min(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=2,
            rng=random.Random(42),
        )
        loader = _make_loader(5)
        sampler._ensure_mapping(loader)

        feedback = _make_assertion_feedback({"item-0": ["a1"]}, all_items=5)
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3
        assert 0 in ids

    def test_top_tier_prioritized(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=2,
            min_failed_per_batch=2,
            top_failed_fraction=0.5,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        # item-3 has 3 failures, item-1 has 2 — these are top 50%
        # item-0 has 1, item-2 has 1 — bottom 50%
        feedback = _make_assertion_feedback(
            {
                "item-0": ["a1"],
                "item-1": ["a1", "a2"],
                "item-2": ["a1"],
                "item-3": ["a1", "a2", "a3"],
            },
            all_items=10,
        )
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        top_indices = {1, 3}  # 2+ failures = top 50%
        top_count = 0
        for _ in range(30):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            assert len(ids) == 2
            if all(idx in top_indices for idx in ids):
                top_count += 1
        # Top-tier items should dominate (batch size == top tier size)
        assert top_count > 15

    def test_failed_items_shuffled_across_calls(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=3,
            rng=random.Random(42),
        )
        loader = _make_loader(6)
        sampler._ensure_mapping(loader)

        feedback = _make_assertion_feedback(
            {
                "item-0": ["rare_assertion"],
                "item-1": ["common_a", "common_b"],
                "item-2": ["common_a"],
                "item-3": ["common_a", "common_b", "rare_assertion"],
            },
            all_items=6,
        )
        sampler.update_scores(feedback)
        sampler.update_assertion_failures(feedback)

        failed_indices = {0, 1, 2, 3}
        batches = set()
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            assert len(ids) == 3
            assert all(idx in failed_indices for idx in ids)
            batches.add(tuple(sorted(ids)))
        assert len(batches) > 1


class TestUpdateScores:
    def test_update_sets_has_data_flag(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        assert not sampler._has_full_eval_data
        sampler.update_scores({"item-0": {"score": 0.5}})
        assert sampler._has_full_eval_data

    def test_empty_update_no_flag(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        sampler.update_scores({})
        assert not sampler._has_full_eval_data

    def test_update_assertion_failures_increments_streak(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        feedback = {
            "item-0": {
                "score": 0.5,
                "runs": [{"assertions": [
                    {"name": "is empathetic", "value": 1.0},
                    {"name": "mentions deadline", "value": 0.0},
                ]}],
            },
            "item-1": {
                "score": 1.0,
                "runs": [{"assertions": [
                    {"name": "is empathetic", "value": 1.0},
                ]}],
            },
        }
        sampler.update_assertion_failures(feedback)
        assert sampler.get_failure_streak("item-0") == 1
        assert sampler.get_failed_assertions("item-0") == ["mentions deadline"]
        assert sampler.get_failure_streak("item-1") == 0

        sampler.update_assertion_failures(feedback)
        assert sampler.get_failure_streak("item-0") == 2

    def test_streak_resets_on_pass(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        failing = {"item-0": {"score": 0.5, "runs": [{"assertions": [
            {"name": "mentions deadline", "value": 0.0},
        ]}]}}
        sampler.update_assertion_failures(failing)
        assert sampler.get_failure_streak("item-0") == 1

        passing = {"item-0": {"score": 1.0, "runs": [{"assertions": [
            {"name": "mentions deadline", "value": 1.0},
        ]}]}}
        sampler.update_assertion_failures(passing)
        assert sampler.get_failure_streak("item-0") == 0
        assert sampler.get_failed_assertions("item-0") == []

    def test_get_stuck_items(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        feedback = {
            "item-0": {"score": 0.0, "runs": [{"assertions": [
                {"name": "a1", "value": 0.0},
            ]}]},
            "item-1": {"score": 0.0, "runs": [{"assertions": [
                {"name": "a2", "value": 0.0},
            ]}]},
        }
        for _ in range(3):
            sampler.update_assertion_failures(feedback)

        stuck = sampler.get_stuck_items(min_streak=3)
        assert "item-0" in stuck
        assert "item-1" in stuck
        assert stuck["item-0"] == 3

        assert sampler.get_stuck_items(min_streak=4) == {}

    def test_multiple_assertions_tracked(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3)
        feedback = {
            "item-0": {"score": 0.0, "runs": [
                {"assertions": [
                    {"name": "a1", "value": 0.0},
                    {"name": "a2", "value": 1.0},
                ]},
                {"assertions": [
                    {"name": "a1", "value": 1.0},
                    {"name": "a2", "value": 0.0},
                    {"name": "a3", "value": 0.0},
                ]},
            ]},
        }
        sampler.update_assertion_failures(feedback)
        assert sorted(sampler.get_failed_assertions("item-0")) == ["a1", "a2", "a3"]
