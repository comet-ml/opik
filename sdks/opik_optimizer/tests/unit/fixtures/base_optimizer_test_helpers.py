"""
Shared helpers for BaseOptimizer unit tests.

Keeping these definitions in one place avoids duplicating the same concrete
optimizer + display spies across multiple test modules when we split files.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import AbstractContextManager, contextmanager
from typing import Any, TYPE_CHECKING, Protocol
from unittest.mock import MagicMock

from opik_optimizer import ChatPrompt
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext

if TYPE_CHECKING:  # pragma: no cover
    from opik import Dataset
    from opik_optimizer.agents import OptimizableAgent
    from opik_optimizer.api_objects import chat_prompt
    from opik_optimizer.api_objects.types import MetricFunction
    from opik_optimizer.core.results import OptimizationResult


class ConcreteOptimizer(BaseOptimizer):
    """Concrete implementation for testing the abstract BaseOptimizer."""

    n_threads: int = 12

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.n_threads = 12

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict[str, Any] | None = None,
        n_samples: int | float | str | None = None,
        n_samples_minibatch: int | None = None,
        n_samples_strategy: str | None = None,
        auto_continue: bool = False,
        project_name: str | None = None,
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        allow_tool_use: bool = True,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        _ = (
            prompt,
            dataset,
            metric,
            agent,
            experiment_config,
            n_samples,
            n_samples_minibatch,
            n_samples_strategy,
            auto_continue,
            project_name,
            optimization_id,
            validation_dataset,
            max_trials,
            allow_tool_use,
            args,
            kwargs,
        )
        return MagicMock()

    def run_optimization(self, context: OptimizationContext) -> OptimizationResult:
        _ = context
        return MagicMock()

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        _ = context
        return {"optimizer": "ConcreteOptimizer"}

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {"test_param": "test_value", "count": 42}


class _ToolFlagAgent(MagicMock):
    def __init__(self) -> None:
        super().__init__()
        self.last_allow_tool_use: bool | None = None

    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        _ = prompts, dataset_item, seed
        self.last_allow_tool_use = allow_tool_use
        return "ok"


class _BaselineReporter(Protocol):
    def set_score(self, score: float) -> None: ...


class _DisplaySpy:
    def __init__(self) -> None:
        self.header_calls: list[tuple[str, str | None]] = []

    def show_header(
        self,
        *,
        algorithm: str,
        optimization_id: str | None,
        dataset_id: str | None = None,
    ) -> None:
        _ = dataset_id
        self.header_calls.append((algorithm, optimization_id))

    def show_configuration(self, *, prompt: Any, optimizer_config: Any) -> None:
        _ = prompt, optimizer_config

    def baseline_evaluation(
        self, context: Any
    ) -> AbstractContextManager[_BaselineReporter]:
        _ = context

        @contextmanager
        def _cm() -> Iterator[_BaselineReporter]:
            class Reporter:
                def set_score(self, score: float) -> None:
                    _ = score

            yield Reporter()

        return _cm()

    def evaluation_progress(
        self,
        *,
        context: Any,
        prompts: Any,
        score: float,
        display_info: Any | None = None,
    ) -> None:
        _ = context, prompts, score, display_info

    def show_final_result(
        self, *, initial_score: float, best_score: float, prompt: Any
    ) -> None:
        _ = initial_score, best_score, prompt
