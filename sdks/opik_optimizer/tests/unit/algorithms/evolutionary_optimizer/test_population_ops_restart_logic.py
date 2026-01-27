"""Tests for evolutionary optimizer should_restart_population()."""

from __future__ import annotations

from opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops import (
    should_restart_population,
)


class TestShouldRestartPopulation:
    def test_no_restart_on_improvement(self) -> None:
        curr_best = 0.9
        history = [0.7, 0.75, 0.8]
        gens_since_improvement = 2
        restart_threshold = 0.01
        restart_generations = 5

        should_restart, new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is False
        assert new_gens == 0
        assert len(new_history) == 4
        assert new_history[-1] == curr_best

    def test_restart_after_stagnation(self) -> None:
        curr_best = 0.80
        history = [0.80]
        gens_since_improvement = 4
        restart_threshold = 0.01
        restart_generations = 5

        should_restart, new_gens, _new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is True
        assert new_gens >= restart_generations

    def test_no_restart_with_empty_history(self) -> None:
        curr_best = 0.5
        history: list[float] = []
        gens_since_improvement = 0
        restart_threshold = 0.01
        restart_generations = 5

        should_restart, _new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is False
        assert len(new_history) == 1
        assert new_history[0] == curr_best

    def test_increments_stagnation_counter(self) -> None:
        curr_best = 0.81
        history = [0.80]
        gens_since_improvement = 2
        restart_threshold = 0.02
        restart_generations = 10

        _should_restart, new_gens, _new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert new_gens == 3

    def test_resets_counter_on_significant_improvement(self) -> None:
        curr_best = 0.90
        history = [0.80]
        gens_since_improvement = 4
        restart_threshold = 0.01
        restart_generations = 5

        should_restart, new_gens, _new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is False
        assert new_gens == 0

    def test_appends_to_history(self) -> None:
        curr_best = 0.85
        history = [0.7, 0.75, 0.8]
        gens_since_improvement = 0
        restart_threshold = 0.01
        restart_generations = 5

        _should_restart, _new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert len(new_history) == 4
        assert new_history[-1] == 0.85
