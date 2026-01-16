# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer, OptimizationResult
from opik_optimizer.algorithms.evolutionary_optimizer.ops import (
    population_ops,
)


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestEvolutionaryOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = EvolutionaryOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
            enable_moo=True,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123
        assert optimizer.enable_moo is True


class TestEvolutionaryOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)
        assert isinstance(result.initial_prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(
                name="secondary", system="Secondary", user="{input}"
            ),
        }
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)
        assert isinstance(result.initial_prompt, dict)

    def test_invalid_prompt_raises_error(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow()
        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = _make_dataset()

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )

    def test_result_contains_required_fields(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "EvolutionaryOptimizer"
        assert result.prompt is not None
        assert result.initial_prompt is not None
        assert isinstance(result.score, (int, float))
        assert hasattr(result, "history")
        assert hasattr(result, "details")


class TestEvolutionaryOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o", perfect_score=0.95, enable_moo=False
        )

        # Mock the base class's evaluate_prompt for baseline computation
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            population_ops,
            "initialize_population",
            lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"
        assert result.details["perfect_score"] == 0.95
        assert result.initial_score == result.score

    def test_early_stop_reports_at_least_one_trial(
        self,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify EvolutionaryOptimizer early stop reports at least 1 trial."""
        dataset = _make_dataset()
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o",
            skip_perfect_score=True,
            perfect_score=0.95,
        )

        # Mock the base class's evaluate_prompt for baseline computation
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        # Early stop happens before _run_optimization, so only baseline was evaluated
        # The optimizer returns 0 from get_metadata (no optimization trials yet)
        # The base class defaults this to 1 to reflect the baseline evaluation
        assert result.details["trials_completed"] == 1
        assert result.details["rounds_completed"] == 1

    def test_optimization_tracks_trials_and_rounds(
        self,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify EvolutionaryOptimizer tracks trials/rounds during actual optimization."""
        dataset = _make_dataset()
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o",
            skip_perfect_score=False,  # Don't early stop
            population_size=2,  # Small population for fast test
            num_generations=2,  # Run 2 generations
        )

        # Track evaluate_prompt calls
        evaluation_count = [0]

        def mock_evaluate_prompt(**kwargs):
            evaluation_count[0] += 1
            return 0.6  # Return a score

        # Mock evaluate_prompt for both baseline and optimization loop
        # This lets the framework's trial counting work properly
        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate_prompt)

        prompt = ChatPrompt(system="test", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=10,  # Allow up to 10 trials
        )

        # The optimizer should have tracked the actual number of trials
        # baseline (1) + initial population (2) + some from generations
        assert result.details["trials_completed"] > 1
        assert result.details["rounds_completed"] > 0
        # Verify that evaluate_prompt was called during optimization
        assert evaluation_count[0] > 1  # At least baseline + some evaluations


def test_uses_validation_dataset_when_provided(monkeypatch: pytest.MonkeyPatch) -> None:
    """EvolutionaryOptimizer should evaluate against the validation dataset when supplied."""
    dataset_train = _make_dataset()
    dataset_val = _make_dataset()
    dataset_val.name = "validation-ds"

    optimizer = EvolutionaryOptimizer(
        model="gpt-4o",
        skip_perfect_score=False,
        population_size=2,
        num_generations=1,
    )

    calls: list[Any] = []

    def mock_evaluate_prompt(**kwargs):
        calls.append(kwargs.get("dataset"))
        return 0.5

    monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate_prompt)

    prompt = ChatPrompt(system="test", user="{question}")
    optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset_train,
        validation_dataset=dataset_val,
        metric=_metric,
        max_trials=3,
    )

    assert calls, "evaluate_prompt should be invoked"
    assert all(call is dataset_val for call in calls)


class TestEvolutionaryOptimizerAgentUsage:
    """Test that self.agent is properly set and used during evaluation."""

    def test_uses_self_agent_in_evaluation(self, monkeypatch) -> None:
        """
        Verify that EvolutionaryOptimizer.agent is set during optimization.

        This test documents why pre_optimization sets self.agent - it's
        used during evaluation through the base class evaluate method.
        """
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            population_size=2,
            num_generations=1,
            skip_perfect_score=False,  # Disable early stop
        )

        dataset = _make_dataset()
        mock_agent = MagicMock()
        mock_agent.invoke.return_value = "test output"

        # Track if optimizer.agent is set during evaluation
        agent_set_during_eval = [False]

        def mock_evaluate(*args, **kwargs):
            # During evaluation, optimizer.agent should be set
            agent_set_during_eval[0] = (
                hasattr(optimizer, "agent") and optimizer.agent is not None
            )
            return 0.6

        # Mock evaluate_prompt for baseline, and evaluate for optimization loop
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)
        monkeypatch.setattr(optimizer, "evaluate", mock_evaluate)

        prompt = ChatPrompt(system="test", user="{question}")

        try:
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=_metric,
                agent=mock_agent,
                max_trials=10,
            )
        except Exception:
            # May fail due to mocking, but we just need to verify agent setup
            pass

        # After pre_optimization, the optimizer should have self.agent set
        assert hasattr(optimizer, "agent")
        # Agent should have been set during evaluation
        assert agent_set_during_eval[0], (
            "optimizer.agent should be set during evaluation"
        )

    def test_agent_set_inpre_optimization(self, monkeypatch) -> None:
        """
        Verify that self.agent is set during pre_optimization.

        This test ensures that when pre_optimization is called,
        self.agent is properly assigned from context.agent.
        """
        from opik_optimizer.agents.optimizable_agent import OptimizableAgent
        from opik_optimizer.base_optimizer import OptimizationContext

        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            population_size=2,
            num_generations=1,
        )

        # Verify agent is not set initially
        assert not hasattr(optimizer, "agent") or optimizer.agent is None

        # Create a mock context with an agent
        mock_agent = MagicMock(spec=OptimizableAgent)
        mock_context = MagicMock(spec=OptimizationContext)
        mock_context.agent = mock_agent

        # Call pre_optimization
        optimizer.pre_optimization(mock_context)

        # Verify self.agent is now set
        assert hasattr(optimizer, "agent"), "pre_optimization should set self.agent"
        assert optimizer.agent is mock_agent, "self.agent should be context.agent"
