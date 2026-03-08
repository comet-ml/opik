import pytest

from opik_optimizer_framework.sampler import sample_split


class TestSampler:
    def test_80_20_split(self):
        ids = [f"item-{i}" for i in range(10)]
        result = sample_split(ids, seed=42)
        assert len(result.train_item_ids) == 8
        assert len(result.validation_item_ids) == 2
        assert result.dataset_size == 10
        assert result.seed == 42

    def test_deterministic_same_seed(self):
        ids = [f"item-{i}" for i in range(20)]
        result_a = sample_split(ids, seed=123)
        result_b = sample_split(ids, seed=123)
        assert result_a.train_item_ids == result_b.train_item_ids
        assert result_a.validation_item_ids == result_b.validation_item_ids

    def test_different_seed_different_split(self):
        ids = [f"item-{i}" for i in range(20)]
        result_a = sample_split(ids, seed=1)
        result_b = sample_split(ids, seed=2)
        assert result_a.train_item_ids != result_b.train_item_ids

    def test_at_least_one_per_split(self):
        ids = ["a", "b"]
        result = sample_split(ids, seed=42)
        assert len(result.train_item_ids) >= 1
        assert len(result.validation_item_ids) >= 1

    def test_empty_input_raises(self):
        with pytest.raises(ValueError, match="empty"):
            sample_split([], seed=42)

    def test_single_item_goes_to_train(self):
        result = sample_split(["only-one"], seed=42)
        assert result.train_item_ids == ["only-one"]
        assert result.validation_item_ids == []
        assert result.dataset_size == 1
        assert result.seed == 42

    def test_no_overlap(self):
        ids = [f"item-{i}" for i in range(50)]
        result = sample_split(ids, seed=99)
        train_set = set(result.train_item_ids)
        val_set = set(result.validation_item_ids)
        assert train_set.isdisjoint(val_set)
        assert train_set | val_set == set(ids)

    def test_custom_split_ratio(self):
        ids = [f"item-{i}" for i in range(10)]
        result = sample_split(ids, seed=42, split_ratio=0.5)
        assert len(result.train_item_ids) == 5
        assert len(result.validation_item_ids) == 5

    def test_large_dataset(self):
        ids = [f"item-{i}" for i in range(1000)]
        result = sample_split(ids, seed=42)
        assert len(result.train_item_ids) == 800
        assert len(result.validation_item_ids) == 200
