"""
Test counter functionality across optimizers.
"""

from typing import Any

from opik_optimizer import (
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    EvolutionaryOptimizer,
    GepaOptimizer,
)
import opik_optimizer


class TestCounterFunctionality:
    """Test that counters work correctly across all optimizers."""

    def test_base_optimizer_counters_initialized(self) -> None:
        """Test that base optimizer initializes counters correctly."""
        from opik_optimizer.base_optimizer import BaseOptimizer

        # Create a mock optimizer that inherits from BaseOptimizer
        class MockOptimizer(BaseOptimizer):
            def optimize_prompt(
                self,
                prompt: Any,
                dataset: Any,
                metric: Any,
                experiment_config: dict[Any, Any] | None = None,
                n_samples: int | None = None,
                auto_continue: bool = False,
                agent_class: type[Any] | None = None,
                project_name: str = "Optimization",
                *args: Any,
                **kwargs: Any,
            ) -> Any:
                pass

            def optimize_mcp(
                self,
                prompt: Any,
                dataset: Any,
                metric: Any,
                *,
                tool_name: str,
                second_pass: Any,
                experiment_config: dict[Any, Any] | None = None,
                n_samples: int | None = None,
                auto_continue: bool = False,
                agent_class: type[Any] | None = None,
                fallback_invoker: Any | None = None,
                fallback_arguments: Any | None = None,
                allow_tool_use_on_second_pass: bool = False,
                **kwargs: Any,
            ) -> Any:
                pass

        optimizer = MockOptimizer(model="gpt-4o-mini")

        # Check that counters are initialized
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

        # Test counter methods
        optimizer._increment_llm_counter()
        assert optimizer.llm_call_counter == 1

        optimizer._increment_tool_counter()
        assert optimizer.tool_call_counter == 1

        optimizer._reset_counters()
        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_gepa_optimizer_counters(self) -> None:
        """Test that GepaOptimizer has proper counters."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

        # Test counter methods
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_meta_prompt_optimizer_counters(self) -> None:
        """Test that MetaPromptOptimizer has proper counters."""
        optimizer = MetaPromptOptimizer(model="gpt-4o-mini")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

        # Test counter methods
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_few_shot_bayesian_optimizer_counters(self) -> None:
        """Test that FewShotBayesianOptimizer has proper counters."""
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

        # Test counter methods
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_evolutionary_optimizer_counters(self) -> None:
        """Test that EvolutionaryOptimizer has proper counters."""
        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

        # Test counter methods
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter == 1
        assert optimizer.tool_call_counter == 1

    def test_reset_counters_method(self) -> None:
        """Test that reset_counters method works correctly."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")

        # Increment counters
        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter == 2
        assert optimizer.tool_call_counter == 1

        # Reset counters
        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_agent_has_optimizer_reference(self) -> None:
        """Test that agents created by optimizers have access to the optimizer."""
        optimizer = GepaOptimizer(model="gpt-4o-mini")

        # Create a mock prompt
        prompt = opik_optimizer.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        # Setup agent class
        agent_class = optimizer._setup_agent_class(prompt)
        agent = agent_class(prompt)

        # Check that agent has optimizer reference
        assert hasattr(agent, "optimizer")
        assert agent.optimizer is optimizer
