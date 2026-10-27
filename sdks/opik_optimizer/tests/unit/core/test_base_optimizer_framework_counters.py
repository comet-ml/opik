"""Unit tests for BaseOptimizer counters."""

from __future__ import annotations

import inspect
from typing import Any

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core import llm_calls


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
