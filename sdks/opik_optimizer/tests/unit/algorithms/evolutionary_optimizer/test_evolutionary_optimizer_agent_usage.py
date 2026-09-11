# mypy: disable-error-code=no-untyped-def

from unittest.mock import MagicMock

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from tests.unit.test_helpers import (
    STANDARD_DATASET_ITEMS,
    make_mock_dataset,
    make_simple_metric,
)


class TestEvolutionaryOptimizerAgentUsage:
    """Test that self.agent is properly set and used during evaluation."""

    def test_uses_self_agent_in_evaluation(self, monkeypatch) -> None:
        """
        Verify that EvolutionaryOptimizer.agent is set during optimization.

        This test documents why pre_optimize sets self.agent - it's
        used during evaluation through the base class evaluate method.
        """
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            population_size=2,
            num_generations=1,
            skip_perfect_score=False,
        )

        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        mock_agent = MagicMock()
        mock_agent.invoke.return_value = "test output"

        agent_set_during_eval = [False]

        def mock_evaluate(_context, *args, **kwargs):
            _ = (args, kwargs)
            agent_set_during_eval[0] = (
                hasattr(optimizer, "agent") and optimizer.agent is not None
            )
            return 0.6

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: 0.5)
        monkeypatch.setattr(optimizer, "evaluate", mock_evaluate)

        prompt = ChatPrompt(system="test", user="{question}")
        try:
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=make_simple_metric(),
                agent=mock_agent,
                max_trials=10,
            )
        except Exception:
            # May fail due to mocking, but we just need to verify agent setup
            pass

        assert hasattr(optimizer, "agent")
        assert agent_set_during_eval[0], (
            "optimizer.agent should be set during evaluation"
        )

    def test_agent_set_in_pre_optimize(self, monkeypatch) -> None:
        """
        Verify that self.agent is set during pre_optimize.

        This test ensures that when pre_optimize is called,
        self.agent is properly assigned from context.agent.
        """
        from opik_optimizer.agents.optimizable_agent import OptimizableAgent
        from opik_optimizer.core.state import OptimizationContext

        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            population_size=2,
            num_generations=1,
        )

        assert not hasattr(optimizer, "agent") or optimizer.agent is None

        mock_agent = MagicMock(spec=OptimizableAgent)
        mock_context = MagicMock(spec=OptimizationContext)
        mock_context.agent = mock_agent

        optimizer.pre_optimize(mock_context)

        assert hasattr(optimizer, "agent"), "pre_optimize should set self.agent"
        assert optimizer.agent is mock_agent, "self.agent should be context.agent"
