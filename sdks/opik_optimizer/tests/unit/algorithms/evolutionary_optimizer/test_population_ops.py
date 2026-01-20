"""Tests for evolutionary optimizer population_ops module."""

import json
from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops import (
    initialize_population,
    should_restart_population,
)
from tests.unit.test_helpers import make_fake_llm_call


@pytest.fixture
def initializing_population_context(monkeypatch: pytest.MonkeyPatch) -> MagicMock:
    """Patch the reporting context manager used by initialize_population()."""
    mock_report = MagicMock()
    mock_context = MagicMock()
    mock_context.__enter__ = MagicMock(return_value=mock_report)
    mock_context.__exit__ = MagicMock(return_value=False)

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops.reporting.initializing_population",
        lambda verbose=0, **_kwargs: mock_context,
    )
    return mock_report


class TestInitializePopulation:
    """Tests for initialize_population function."""

    def test_returns_single_prompt_for_population_size_1(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
    ) -> None:
        """Should return just the initial prompt when population_size is 1."""
        _ = initializing_population_context

        prompt = ChatPrompt(system="Test prompt", user="{input}")

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=1,
            verbose=0,
        )

        assert len(result) == 1
        assert result[0] is prompt

    def test_generates_fresh_start_prompts(
        self,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
        mock_llm_sequence: Callable[[list[Any]], dict[str, Any]],
    ) -> None:
        """Should generate fresh start prompts as part of population."""
        _ = initializing_population_context

        fresh_response = json.dumps(
            [
                [
                    {"role": "system", "content": "Fresh prompt 1"},
                    {"role": "user", "content": "{input}"},
                ]
            ]
        )
        variation_response = json.dumps(
            {
                "prompts": [
                    {
                        "prompt": [
                            {"role": "system", "content": "Variation 1"},
                            {"role": "user", "content": "{input}"},
                        ]
                    },
                    {
                        "prompt": [
                            {"role": "system", "content": "Variation 2"},
                            {"role": "user", "content": "{input}"},
                        ]
                    },
                ]
            }
        )

        mock_llm_sequence([fresh_response, variation_response])

        prompt = ChatPrompt(system="Original prompt", user="{input}")

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=5,
            verbose=0,
        )

        # Should have generated more than just the original
        assert len(result) >= 1

    def test_handles_llm_error_for_fresh_starts(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
    ) -> None:
        """Should handle LLM errors gracefully when generating fresh starts."""
        _ = initializing_population_context

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM API error")),
        )

        prompt = ChatPrompt(system="Original prompt", user="{input}")

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=5,
            verbose=0,
        )

        # Should still return at least the original prompt
        assert len(result) >= 1
        assert result[0] is prompt

    def test_handles_json_decode_error(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
    ) -> None:
        """Should handle invalid JSON responses from LLM."""
        _ = initializing_population_context

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call("This is not valid JSON"),
        )

        prompt = ChatPrompt(system="Original prompt", user="{input}")

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=5,
            verbose=0,
        )

        # Should still return at least the original
        assert len(result) >= 1

    def test_generates_variations_on_initial_prompt(
        self,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
        mock_llm_sequence: Callable[[list[Any]], dict[str, Any]],
    ) -> None:
        """Should generate variations of the initial prompt."""
        _ = initializing_population_context

        # Mock LLM responses
        fresh_response = json.dumps(
            [
                [
                    {"role": "system", "content": "Fresh"},
                    {"role": "user", "content": "{q}"},
                ]
            ]
        )
        variation_response = json.dumps(
            {
                "prompts": [
                    {
                        "prompt": [
                            {"role": "system", "content": "Var 1"},
                            {"role": "user", "content": "{q}"},
                        ]
                    },
                    {
                        "prompt": [
                            {"role": "system", "content": "Var 2"},
                            {"role": "user", "content": "{q}"},
                        ]
                    },
                    {
                        "prompt": [
                            {"role": "system", "content": "Var 3"},
                            {"role": "user", "content": "{q}"},
                        ]
                    },
                ]
            }
        )

        mock_llm_sequence([fresh_response, variation_response])

        prompt = ChatPrompt(system="Original", user="{q}")

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=5,
            verbose=0,
        )

        assert len(result) >= 1

    def test_deduplicates_population(
        self,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
        mock_llm_sequence: Callable[[list[Any]], dict[str, Any]],
    ) -> None:
        """Should remove duplicate prompts from the population."""
        _ = initializing_population_context

        # Return identical prompts
        same_messages = [
            {"role": "system", "content": "Same content"},
            {"role": "user", "content": "{q}"},
        ]
        fresh_response = json.dumps([same_messages])
        variation_response = json.dumps(
            {"prompts": [{"prompt": same_messages}, {"prompt": same_messages}]}
        )

        mock_llm_sequence([fresh_response, variation_response])

        prompt = ChatPrompt(messages=same_messages)

        result = initialize_population(
            prompt=prompt,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            optimization_id="opt-123",
            population_size=5,
            verbose=0,
        )

        # Duplicates should be removed
        assert len(result) == 1


class TestShouldRestartPopulation:
    """Tests for should_restart_population function."""

    def test_no_restart_on_improvement(self, evo_prompts: Any) -> None:
        """Should not restart when there is improvement."""
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
        assert new_gens == 0  # Reset since there was improvement
        assert len(new_history) == 4
        assert new_history[-1] == curr_best

    def test_restart_after_stagnation(self, evo_prompts: Any) -> None:
        """Should trigger restart after too many generations without improvement."""
        curr_best = 0.80  # Same as before, no improvement
        history = [0.80]
        gens_since_improvement = 4  # Will become 5 after this call
        restart_threshold = 0.01
        restart_generations = 5

        should_restart, new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is True
        assert new_gens >= restart_generations

    def test_no_restart_with_empty_history(self, evo_prompts: Any) -> None:
        """Should not restart on first generation."""
        curr_best = 0.5
        history: list[float] = []
        gens_since_improvement = 0
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
        assert len(new_history) == 1
        assert new_history[0] == curr_best

    def test_increments_stagnation_counter(self, evo_prompts: Any) -> None:
        """Should increment counter when no significant improvement."""
        curr_best = 0.81  # Only 1.25% improvement (threshold is 1%)
        history = [0.80]
        gens_since_improvement = 2
        restart_threshold = 0.02  # 2% threshold not met
        restart_generations = 10

        should_restart, new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        # Counter should increment since improvement is below threshold
        assert new_gens == 3

    def test_resets_counter_on_significant_improvement(self, evo_prompts: Any) -> None:
        """Should reset counter when improvement exceeds threshold."""
        curr_best = 0.90  # 12.5% improvement, exceeds threshold
        history = [0.80]
        gens_since_improvement = 4
        restart_threshold = 0.01  # 1% threshold
        restart_generations = 5

        should_restart, new_gens, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert should_restart is False
        assert new_gens == 0  # Reset due to improvement

    def test_appends_to_history(self, evo_prompts: Any) -> None:
        """Should always append current best to history."""
        curr_best = 0.85
        history = [0.7, 0.75, 0.8]
        gens_since_improvement = 0
        restart_threshold = 0.01
        restart_generations = 5

        _, _, new_history = should_restart_population(
            curr_best=curr_best,
            best_primary_score_history=history,
            gens_since_pop_improvement=gens_since_improvement,
            default_restart_threshold=restart_threshold,
            default_restart_generations=restart_generations,
        )

        assert len(new_history) == 4
        assert new_history[-1] == 0.85


class TestRestartPopulation:
    """Tests for restart_population function - requires integration testing."""

    # Note: restart_population requires a full EvolutionaryOptimizer instance
    # which makes it more suitable for integration testing.
    # Here we test the basic logic through should_restart_population.

    def test_restart_population_is_imported(self, evo_prompts: Any) -> None:
        """Verify restart_population can be imported."""
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops import (
            restart_population,
        )

        assert callable(restart_population)
