"""Pytest fixtures for end-to-end-ish optimize_prompt flow mocking (unit scope)."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_optimization_context(monkeypatch: pytest.MonkeyPatch) -> Callable[..., Any]:
    """Mock Opik optimization creation and updates for optimizer setup flow tests."""

    def _configure(
        *,
        optimization_id: str = "test-opt-123",
        raise_on_create: Exception | None = None,
        raise_on_update: Exception | None = None,
    ) -> Any:
        mock_client = MagicMock()
        mock_optimization = MagicMock()
        mock_optimization.id = optimization_id

        if raise_on_create:
            mock_client.create_optimization.side_effect = raise_on_create
        else:
            mock_client.create_optimization.return_value = mock_optimization

        if raise_on_update:
            mock_optimization.update.side_effect = raise_on_update

        mock_client.get_optimization_by_id.return_value = mock_optimization
        monkeypatch.setattr("opik.Opik", lambda **_kw: mock_client)

        class Context:
            pass

        ctx = Context()
        ctx.client = mock_client  # type: ignore[attr-defined]
        ctx.optimization = mock_optimization  # type: ignore[attr-defined]
        return ctx

    return _configure


@pytest.fixture
def mock_full_optimization_flow(
    monkeypatch: pytest.MonkeyPatch,
    mock_llm_call: Any,
    mock_optimization_context: Any,
    mock_task_evaluator: Any,
) -> Callable[..., Any]:
    """Comprehensive mock for the complete optimize_prompt flow without real API calls."""

    def _configure(
        *,
        llm_response: Any = "Improved prompt content",
        llm_responses: list[Any] | None = None,
        evaluation_score: float = 0.75,
        evaluation_scores: list[float] | None = None,
        optimization_id: str = "test-opt-123",
        raise_on_optimization_create: Exception | None = None,
    ) -> Any:
        if llm_responses is not None:
            call_idx: dict[str, int] = {"n": 0}

            def llm_side_effect(**_kwargs: Any) -> Any:
                idx = min(call_idx["n"], len(llm_responses) - 1)
                call_idx["n"] += 1
                return llm_responses[idx]

            llm_mock = mock_llm_call(side_effect=llm_side_effect)
        else:
            llm_mock = mock_llm_call(llm_response)

        opt_ctx = mock_optimization_context(
            optimization_id=optimization_id,
            raise_on_create=raise_on_optimization_create,
        )

        evaluator = mock_task_evaluator(
            score=evaluation_score,
            scores=evaluation_scores,
        )

        mock_agent_instance = MagicMock()
        mock_agent_instance.invoke_agent.return_value = "Mock agent response"
        mock_agent_instance.invoke_agent_candidates.return_value = [
            "Mock agent response"
        ]

        def mock_litellm_agent_init(*_args: Any, **_kwargs: Any) -> Any:
            return mock_agent_instance

        monkeypatch.setattr(
            "opik_optimizer.agents.LiteLLMAgent", mock_litellm_agent_init
        )

        class Mocks:
            pass

        mocks = Mocks()
        mocks.llm = llm_mock  # type: ignore[attr-defined]
        mocks.optimization_context = opt_ctx  # type: ignore[attr-defined]
        mocks.evaluator = evaluator  # type: ignore[attr-defined]
        mocks.agent = mock_agent_instance  # type: ignore[attr-defined]
        return mocks

    return _configure


@pytest.fixture
def optimizer_test_params() -> dict[str, Any]:
    """Standard test parameters for fast optimizer testing."""
    return {"max_trials": 1, "n_samples": 2, "verbose": 0}
