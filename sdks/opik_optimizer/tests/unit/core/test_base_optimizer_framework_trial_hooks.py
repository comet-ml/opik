"""Unit tests for BaseOptimizer evaluate() trial hook wiring."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.core.state import OptimizationContext
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context


def test_pre_trial_invoked_during_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.pre_trial_called = False

        def pre_trial(
            self, context: OptimizationContext, candidate: Any, round_handle=None
        ):
            self.pre_trial_called = True
            return candidate

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.pre_trial_called is True


def test_on_trial_called_after_evaluation(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.on_trial_called = False

        def on_trial(
            self,
            context: OptimizationContext,
            prompts: dict[str, chat_prompt.ChatPrompt],
            score: float,
            prev_best_score: float | None = None,
        ) -> None:
            _ = context, prompts, score, prev_best_score
            self.on_trial_called = True

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.on_trial_called is True


def test_post_trial_not_called_by_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.post_trial_called = False

        def post_trial(
            self,
            context: OptimizationContext,
            candidate_handle: Any,
            *,
            score: float | None,
            **kwargs: Any,
        ) -> None:
            _ = context, candidate_handle, score, kwargs
            self.post_trial_called = True

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.post_trial_called is False
