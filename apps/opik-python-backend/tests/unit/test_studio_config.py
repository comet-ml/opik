"""Unit tests for opik_backend.studio.config sizing policy."""

import pytest

from opik_backend.studio.config import (
    GEPA_REFLECTION_MINIBATCH_ENV,
    resolve_reflection_minibatch_size,
)


class TestResolveReflectionMinibatchSize:
    """OPIK-7511: the reflection mini-batch scales with dataset size so coarse
    0/1 metrics get a usable gradient, within the SDK's max_trials clamp."""

    @pytest.mark.parametrize(
        "dataset_size,max_trials,expected",
        [
            # Tiny dataset: capped at the dataset itself.
            (3, 10, 3),
            # Small dataset: the floor of 5 (previous fixed value) holds.
            (10, 10, 5),
            (25, 10, 5),
            # 20% scaling kicks in above the floor.
            (30, 10, 6),
            (40, 10, 8),
            # Capped at max_trials — above it GEPA reflection would not run.
            (50, 10, 10),
            (1000, 10, 10),
            # Higher max_trials lets the scaled value through.
            (100, 25, 20),
            # max_trials below the floor still wins the clamp.
            (100, 3, 3),
        ],
    )
    def test_policy(self, dataset_size, max_trials, expected, monkeypatch):
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        assert (
            resolve_reflection_minibatch_size(
                dataset_size=dataset_size, max_trials=max_trials
            )
            == expected
        )

    def test_env_override_wins_verbatim(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "7")
        assert resolve_reflection_minibatch_size(dataset_size=1000, max_trials=10) == 7

    def test_env_override_clamped_to_at_least_one(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "0")
        assert resolve_reflection_minibatch_size(dataset_size=50, max_trials=10) == 1

    def test_blank_env_falls_back_to_policy(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "  ")
        assert resolve_reflection_minibatch_size(dataset_size=50, max_trials=10) == 10

    def test_never_below_one(self, monkeypatch):
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        assert resolve_reflection_minibatch_size(dataset_size=0, max_trials=10) == 1
