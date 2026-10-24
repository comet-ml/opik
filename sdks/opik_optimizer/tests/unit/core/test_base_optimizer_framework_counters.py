"""Unit tests for BaseOptimizer counters."""

from __future__ import annotations

import inspect
from typing import Any

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core import llm_calls
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer


class TestCounterManagement:
    """Tests for counter management methods."""

    def test_counters_start_at_zero(self) -> None:
        """Counters should start at zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_increment_llm_counter(self) -> None:
        """_increment_llm_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()

        assert optimizer.llm_call_counter == 2

    def test_increment_llm_call_tools_counter(self) -> None:
        """_increment_llm_call_tools_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_call_tools_counter()

        assert optimizer.llm_call_tools_counter == 1

    def test_reset_counters(self) -> None:
        """_reset_counters should reset both counters to zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0


class TestOverriddenEntrypointScope:
    """User subclasses that override optimize_prompt keep the run's call scope."""

    def test_overridden_optimize_prompt_binds_call_scope(self) -> None:
        seen: dict[str, Any] = {}

        class Custom(BaseOptimizer):
            def run_optimization(self, context: Any) -> Any:
                raise NotImplementedError

            def optimize_prompt(self, prompt: Any, **kwargs: Any) -> int:  # type: ignore[override]
                """Custom docs."""
                seen["active"] = llm_calls._active_optimizer.get()
                llm_calls._increment_llm_counter_if_in_optimizer()
                return self.llm_call_counter

        optimizer = Custom(model="gpt-4")
        assert optimizer.optimize_prompt(None) == 1
        assert seen["active"] is optimizer
        assert llm_calls._active_optimizer.get() is None
        assert Custom.optimize_prompt.__doc__ == "Custom docs."
        assert list(inspect.signature(Custom.optimize_prompt).parameters) == [
            "self",
            "prompt",
            "kwargs",
        ]

    def test_entrypoints_are_wrapped_once(self) -> None:
        class Custom(BaseOptimizer):
            def run_optimization(self, context: Any) -> Any:
                raise NotImplementedError

            @llm_calls.bind_optimizer
            def optimize_parameter(self, *args: Any, **kwargs: Any) -> Any:
                return llm_calls._active_optimizer.get()

        class Child(Custom):
            pass

        assert Child.optimize_parameter is Custom.optimize_parameter
        inner = Custom.optimize_parameter.__wrapped__  # type: ignore[attr-defined]
        assert not getattr(inner, "_opik_optimizer_bound", False)
        child = Child(model="gpt-4")
        assert child.optimize_parameter() is child
