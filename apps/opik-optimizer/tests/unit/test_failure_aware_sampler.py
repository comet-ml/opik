import random

import pytest

from opik_optimizer_framework.optimizers.gepa_v2.failure_aware_sampler import (
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


class TestWithFailureData:
    def test_failed_items_prioritized(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=2,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        sampler.update_scores({
            "item-0": {"score": 0.0},
            "item-1": {"score": 0.0},
            "item-2": {"score": 1.0},
            "item-3": {"score": 1.0},
            "item-4": {"score": 0.5},
            "item-5": {"score": 1.0},
            "item-6": {"score": 1.0},
            "item-7": {"score": 1.0},
            "item-8": {"score": 1.0},
            "item-9": {"score": 1.0},
        })

        failed_indices = {0, 1, 4}
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            failed_in_batch = [idx for idx in ids if idx in failed_indices]
            assert len(failed_in_batch) >= 2

    def test_all_items_failed(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=2,
            rng=random.Random(42),
        )
        loader = _make_loader(5)
        sampler._ensure_mapping(loader)

        sampler.update_scores({f"item-{i}": {"score": 0.0} for i in range(5)})

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

        sampler.update_scores({
            "item-0": {"score": 0.0},
            "item-1": {"score": 1.0},
            "item-2": {"score": 1.0},
            "item-3": {"score": 1.0},
            "item-4": {"score": 1.0},
        })

        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3
        assert 0 in ids


class TestUnseenItems:
    def test_unseen_items_included(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=1,
            min_unseen_per_batch=1,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        scores = {f"item-{i}": {"score": 0.0} for i in range(3)}
        scores.update({f"item-{i}": {"score": 1.0} for i in range(3, 10)})
        sampler.update_scores(scores)
        sampler.mark_seen([f"item-{i}" for i in range(8)])

        unseen_indices = {8, 9}
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            unseen_in_batch = [idx for idx in ids if idx in unseen_indices]
            assert len(unseen_in_batch) >= 1

    def test_no_unseen_left(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=1,
            min_unseen_per_batch=1,
            rng=random.Random(42),
        )
        loader = _make_loader(5)
        sampler._ensure_mapping(loader)

        sampler.update_scores({f"item-{i}": {"score": 0.5} for i in range(5)})
        sampler.mark_seen([f"item-{i}" for i in range(5)])

        ids = sampler.next_minibatch_ids(loader, FakeState())
        assert len(ids) == 3


class TestMarkSeen:
    def test_mark_seen_tracks_items(self):
        sampler = FailureAwareBatchSampler(minibatch_size=3, rng=random.Random(42))
        sampler.mark_seen(["item-0", "item-1"])
        assert "item-0" in sampler._seen_item_ids
        assert "item-1" in sampler._seen_item_ids
        assert "item-2" not in sampler._seen_item_ids


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

    def test_custom_failure_threshold(self):
        sampler = FailureAwareBatchSampler(
            minibatch_size=3,
            min_failed_per_batch=2,
            failure_threshold=0.5,
            rng=random.Random(42),
        )
        loader = _make_loader(10)
        sampler._ensure_mapping(loader)

        sampler.update_scores({
            "item-0": {"score": 0.3},
            "item-1": {"score": 0.4},
            "item-2": {"score": 0.5},
            "item-3": {"score": 0.8},
            "item-4": {"score": 1.0},
            "item-5": {"score": 1.0},
            "item-6": {"score": 1.0},
            "item-7": {"score": 1.0},
            "item-8": {"score": 1.0},
            "item-9": {"score": 1.0},
        })

        failed_indices = {0, 1}
        for _ in range(20):
            ids = sampler.next_minibatch_ids(loader, FakeState())
            failed_in_batch = [idx for idx in ids if idx in failed_indices]
            assert len(failed_in_batch) >= 2
