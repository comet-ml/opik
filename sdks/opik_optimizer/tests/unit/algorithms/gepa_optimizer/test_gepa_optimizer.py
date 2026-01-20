# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt, GepaOptimizer, OptimizationResult
from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_optimization_context,
    STANDARD_DATASET_ITEMS,
)


class TestGepaOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
            ),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        """Test optimizer initialization with defaults and custom params."""
        optimizer = GepaOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value


class TestGepaOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = simple_chat_prompt
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        mock_gepa_result = MagicMock()
        mock_gepa_result.best_prompt = prompt.copy()
        mock_gepa_result.best_score = 0.85
        mock_gepa_result.history = []
        mock_gepa_result.pareto_front = []
        mock_gepa_result.total_metric_calls = 1

        monkeypatch.setattr("gepa.optimize", lambda **kwargs: mock_gepa_result)

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_optimization_context,
        monkeypatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(
                name="secondary", system="Secondary", user="{input}"
            ),
        }
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        mock_gepa_result = MagicMock()
        mock_gepa_result.best_prompt = list(prompts.values())[0].copy()
        mock_gepa_result.best_score = 0.85
        mock_gepa_result.history = []
        mock_gepa_result.pareto_front = []
        mock_gepa_result.total_metric_calls = 1

        monkeypatch.setattr("gepa.optimize", lambda **kwargs: mock_gepa_result)

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=sample_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)

    def test_invalid_prompt_raises_error(
        self,
        mock_optimization_context,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()
        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=sample_metric,
                max_trials=1,
            )


class TestGepaOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"
        assert result.details["perfect_score"] == 0.95
        assert result.initial_score == result.score
        # Early stop happens before run_optimization, so only baseline was evaluated
        # Framework sets trials_completed to 1 (baseline evaluation counts as 1 trial)
        assert result.details["trials_completed"] == 1

    def test_early_stop_reports_at_least_one_trial(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Verify GepaOptimizer early stop reports at least 1 trial."""
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        # Early stop happens before run_optimization, so only baseline was evaluated
        # The optimizer returns 0 from get_metadata (no optimization trials yet)
        # The base class defaults this to 1 to reflect the baseline evaluation
        assert result.details["trials_completed"] == 1
        assert len(result.history) == 1


class TestGepaOptimizerAgentUsage:
    """Test that self.agent is properly set and used by GEPA adapter."""

    def test_agent_set_inpre_optimize(self, monkeypatch) -> None:
        """
        Verify that self.agent is set during pre_optimize.

        This test ensures that when pre_optimize is called,
        self.agent is properly assigned from context.agent for use
        in OpikGEPAAdapter and reflection operations.
        """
        from opik_optimizer.agents.optimizable_agent import OptimizableAgent
        from opik_optimizer.core.state import OptimizationContext

        optimizer = GepaOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=42,
        )

        # Verify agent is not set initially
        assert not hasattr(optimizer, "agent") or optimizer.agent is None

        # Create a mock context with an agent
        mock_agent = MagicMock(spec=OptimizableAgent)
        mock_context = MagicMock(spec=OptimizationContext)
        mock_context.agent = mock_agent
        mock_context.extra_params = {}

        # Call pre_optimize
        optimizer.pre_optimize(mock_context)

        # Verify self.agent is now set
        assert hasattr(optimizer, "agent"), "pre_optimize should set self.agent"
        assert optimizer.agent is mock_agent, "self.agent should be context.agent"

    def test_self_agent_available_for_adapter(self) -> None:
        """
        Verify that self.agent is available after optimize_prompt starts.

        This test documents that GepaOptimizer needs self.agent because
        it creates OpikGEPAAdapter which accesses the agent via the optimizer instance.
        The agent is passed in run_optimization to the adapter as self.agent.
        """
        from opik_optimizer.core.state import OptimizationContext

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)

        # Verify agent is not set before pre_optimize
        assert not hasattr(optimizer, "agent") or optimizer.agent is None

        # Create a mock context
        mock_agent = MagicMock()
        mock_context = MagicMock(spec=OptimizationContext)
        mock_context.agent = mock_agent
        mock_context.extra_params = {}

        # Call pre_optimize
        optimizer.pre_optimize(mock_context)

        # Verify self.agent is set and can be accessed in run_optimization
        assert optimizer.agent is mock_agent


def test_gepa_adapter_records_per_item_metrics() -> None:
    """Ensure per-item adapter scoring does not override round best scores."""
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = ChatPrompt(system="baseline", user="{question}")

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        return 0.1

    metric_fn.__name__ = "metric_fn"

    context = make_optimization_context(prompt, dataset=dataset, metric=metric_fn)
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=MagicMock(),
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    adapter._record_and_post_candidate(
        prompt_variants=context.prompts,
        score=1.0,
        metrics={"adapter_metric": 1.0},
    )

    history = optimizer.get_history_entries()
    assert history
    entry = history[0]
    trials = entry.get("trials") or []
    candidates = entry.get("candidates") or []
    assert trials
    assert candidates
    assert trials[0].get("score") is None
    assert trials[0].get("metrics", {}).get("per_item_score") == 1.0
    assert candidates[0].get("metrics", {}).get("per_item_score") == 1.0
    assert trials[0].get("extra", {}).get("score_label") == "per_item"
    # This confirms that adapter creation in run_optimization
    # can access self.agent successfully
