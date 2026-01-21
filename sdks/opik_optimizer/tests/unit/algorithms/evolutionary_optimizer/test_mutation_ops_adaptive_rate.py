"""Unit tests for evolutionary mutation adaptive rate helpers."""

from __future__ import annotations

import pytest

from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestAdaptiveMutationRate:
    """Tests for compute_adaptive_mutation_rate function."""

    def test_increases_rate_when_diversity_low(self) -> None:
        current_rate = 0.2
        best_fitness_history = [1.0, 1.0]
        current_population = [{"prompt": "same"} for _ in range(3)]

        adjusted_rate, generations = mutation_ops.compute_adaptive_mutation_rate(
            current_rate=current_rate,
            best_fitness_history=best_fitness_history,
            current_population=current_population,
            generations_without_improvement=0,
            adaptive_mutation=True,
            restart_threshold=0.01,
            restart_generations=5,
            min_rate=0.05,
            max_rate=1.0,
            diversity_threshold=0.7,
        )

        assert adjusted_rate > current_rate
        assert adjusted_rate <= 1.0
        assert generations == 1

    def test_uses_base_rate_when_diversity_high_and_improving(self) -> None:
        current_rate = 0.4
        best_fitness_history = [1.0, 1.2]
        current_population = [{"prompt": "one"}, {"prompt": "two completely different"}]

        adjusted_rate, generations = mutation_ops.compute_adaptive_mutation_rate(
            current_rate=current_rate,
            best_fitness_history=best_fitness_history,
            current_population=current_population,
            generations_without_improvement=2,
            adaptive_mutation=True,
            restart_threshold=0.01,
            restart_generations=5,
            min_rate=0.1,
            max_rate=1.0,
            diversity_threshold=0.1,
        )

        assert adjusted_rate == max(current_rate * 0.8, 0.1)
        assert generations == 0

