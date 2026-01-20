"""
Deterministic builders for optimizer unit tests.

This module is the single source of truth for common test helpers (dataset mocks,
LLM response mocks, OptimizationContext builders, etc.). Keep logic here so
`conftest.py` stays thin and individual test files don't reimplement helpers.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, TYPE_CHECKING
from unittest.mock import MagicMock

from opik import Dataset
from opik_optimizer import ChatPrompt
from opik_optimizer.core.state import OptimizationContext

if TYPE_CHECKING:  # pragma: no cover
    from opik_optimizer.api_objects.types import MetricFunction


# Standard dataset items used across multiple unit tests
STANDARD_DATASET_ITEMS: list[dict[str, Any]] = [{"id": "1", "question": "Q1", "answer": "A1"}]


def make_mock_dataset(
    items: list[dict[str, Any]] | None = None,
    *,
    name: str = "test-dataset",
    dataset_id: str = "dataset-123",
) -> MagicMock:
    """
    Create a mock `opik.Dataset` compatible with the optimizer code.

    Supports both `get_items()` and `get_items(nb_samples=N)`.
    """
    if items is None:
        items = STANDARD_DATASET_ITEMS

    mock = MagicMock(spec=Dataset)
    mock.name = name
    mock.id = dataset_id

    def get_items_impl(nb_samples: int | None = None):
        if nb_samples is not None:
            return items[:nb_samples]
        return items

    mock.get_items = MagicMock(side_effect=get_items_impl)
    return mock


def make_simple_metric() -> "MetricFunction":
    """Create a trivial metric that always returns 1.0 (with a stable __name__)."""

    def metric(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 1.0

    metric.__name__ = "simple_metric"
    return metric  # type: ignore[return-value]


def make_mock_response(
    content: str,
    *,
    finish_reason: str = "stop",
    model: str = "gpt-4",
    parsed: Any | None = None,
) -> MagicMock:
    """
    Create a minimal LiteLLM-like response object used by `opik_optimizer.core.llm_calls`.
    """
    mock_response = MagicMock()
    mock_choice = MagicMock()
    mock_message = MagicMock()
    mock_message.content = content
    mock_message.parsed = parsed
    mock_choice.message = mock_message
    mock_choice.finish_reason = finish_reason
    mock_response.choices = [mock_choice]
    mock_response.model = model
    return mock_response


def make_candidate_agent(
    *,
    candidates: list[str] | None = None,
    single_output: str = "bad",
    logprobs: list[float] | None = None,
) -> Any:
    """
    Create a small agent-like object that supports both single output and candidates.

    This intentionally avoids patching LiteLLM/global call sites: pass the returned
    object via `agent=` to keep tests deterministic and fast.
    """
    if candidates is None:
        candidates = ["bad", "good"]

    class CandidateAgent:
        _last_candidate_logprobs: list[float] | None = None

        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use: bool = False, seed: int | None = None
        ):
            _ = prompts, dataset_item, allow_tool_use, seed
            if logprobs is not None:
                self._last_candidate_logprobs = logprobs
            return candidates

        def invoke_agent(
            self, prompts, dataset_item, allow_tool_use: bool = False, seed: int | None = None
        ) -> str:
            _ = prompts, dataset_item, allow_tool_use, seed
            return single_output

    return CandidateAgent()


def make_fake_evaluator(
    *,
    expected_output: str | None = None,
    return_score: float = 1.0,
    assert_output: Callable[[dict[str, Any]], None] | None = None,
) -> Callable[..., float]:
    """
    Build a fake `evaluate(...)` function that executes `evaluated_task` once.

    Used for testing BaseOptimizer integration with the evaluation wrapper without
    requiring Opik evaluator execution.
    """

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
        **kwargs,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
            kwargs,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        if assert_output is not None:
            assert_output(output)
        elif expected_output is not None:
            assert output["llm_output"] == expected_output
        return return_score

    return fake_evaluate


def make_fake_llm_call(
    response: str | list[str] | None = None,
    *,
    raises: Exception | None = None,
    side_effect: Callable[..., str] | list[str] | None = None,
) -> Callable[..., str]:
    """
    Create a fake `call_model(**kwargs)` implementation for patching.

    Prefer injecting a fake agent where possible, but this helper is useful for
    tests that specifically validate our llm_calls wrapper behavior.
    """
    if raises is not None:

        def fake(**kwargs: Any) -> str:
            _ = kwargs
            raise raises

        return fake

    if side_effect is not None:
        if callable(side_effect):
            return side_effect
        call_count = {"n": 0}

        def fake(**kwargs: Any) -> str:
            _ = kwargs
            idx = call_count["n"] % len(side_effect)
            call_count["n"] += 1
            return side_effect[idx]

        return fake

    if response is None:
        response = "test response"
    if isinstance(response, list):
        return lambda **kwargs: response[0]
    return lambda **kwargs: response


def make_optimization_context(
    prompt: ChatPrompt | dict[str, ChatPrompt],
    *,
    dataset: Dataset | None = None,
    evaluation_dataset: Dataset | None = None,
    validation_dataset: Dataset | None = None,
    metric: Callable[..., Any] | None = None,
    agent: Any | None = None,
    optimization: Any | None = None,
    optimization_id: str | None = None,
    experiment_config: dict[str, Any] | None = None,
    n_samples: int | None = None,
    max_trials: int = 10,
    project_name: str = "Test",
    baseline_score: float | None = None,
    current_best_score: float | None = None,
    allow_tool_use: bool = True,
    **extra_params: Any,
) -> OptimizationContext:
    """
    Create an `OptimizationContext` with sensible defaults for unit tests.
    """
    if dataset is None:
        dataset = make_mock_dataset()
    if evaluation_dataset is None:
        evaluation_dataset = dataset
    if metric is None:
        metric = MagicMock(__name__="test_metric")
    if agent is None:
        agent = MagicMock()

    if isinstance(prompt, ChatPrompt):
        prompts = {prompt.name: prompt}
        initial_prompts = {prompt.name: prompt}
        is_single = True
    else:
        prompts = prompt
        initial_prompts = prompt
        is_single = False

    ctx = OptimizationContext(
        prompts=prompts,
        initial_prompts=initial_prompts,
        is_single_prompt_optimization=is_single,
        dataset=dataset,
        evaluation_dataset=evaluation_dataset,
        validation_dataset=validation_dataset,
        metric=metric,  # type: ignore[arg-type]
        agent=agent,
        optimization=optimization,
        optimization_id=optimization_id,
        experiment_config=experiment_config,
        n_samples=n_samples,
        max_trials=max_trials,
        project_name=project_name,
        allow_tool_use=allow_tool_use,
        baseline_score=baseline_score,
        current_best_score=current_best_score,
        **extra_params,
    )
    return ctx

